package com.openclaude.core.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaude.core.config.OpenClaudeEffort;
import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.instructions.AgentsInstructionsLoader;
import com.openclaude.core.provider.OpenClaudeState;
import com.openclaude.core.provider.ProviderFailureClassifier;
import com.openclaude.core.provider.ProviderConnectionState;
import com.openclaude.core.provider.ProviderRegistry;
import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.FileReadState;
import com.openclaude.core.session.SessionAttachment;
import com.openclaude.core.session.ConversationSessionStore;
import com.openclaude.core.session.SessionCompaction;
import com.openclaude.core.session.SessionMessage;
import com.openclaude.core.sessionmemory.SessionMemoryService;
import com.openclaude.core.tools.DefaultToolRuntime;
import com.openclaude.core.tools.ToolExecutionRequest;
import com.openclaude.core.tools.ToolExecutionResult;
import com.openclaude.core.tools.ToolExecutionUpdate;
import com.openclaude.core.tools.ToolHooksExecutor;
import com.openclaude.core.tools.ToolModelProgress;
import com.openclaude.core.tools.ToolModelRequest;
import com.openclaude.core.tools.ToolModelResponse;
import com.openclaude.core.tools.ToolPermissionGateway;
import com.openclaude.core.tools.ToolRuntime;
import com.openclaude.core.tools.ShellPermissionPolicy;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.PromptContentBlock;
import com.openclaude.provider.spi.PromptEvent;
import com.openclaude.provider.spi.PromptExecutionContext;
import com.openclaude.provider.spi.PromptMessage;
import com.openclaude.provider.spi.PromptMessageRole;
import com.openclaude.provider.spi.PromptRequest;
import com.openclaude.provider.spi.PromptResult;
import com.openclaude.provider.spi.ProviderToolDefinition;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderPlugin;
import com.openclaude.provider.spi.ReasoningDeltaEvent;
import com.openclaude.provider.spi.TextDeltaEvent;
import com.openclaude.provider.spi.ToolCallEvent;
import com.openclaude.provider.spi.ToolPermissionEvent;
import com.openclaude.provider.spi.ToolResultContentBlock;
import com.openclaude.provider.spi.ToolUseDiscoveredEvent;
import com.openclaude.provider.spi.ToolUseContentBlock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class QueryEngine {
    private static final ObjectMapper TOOL_INPUT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are OpenClaude, an interactive coding assistant running in a terminal on the user's machine.
            You have access to local tools exposed in this session.
            When the user asks about local files, folders, repositories, the current workspace, or command output,
            use the available tools to inspect the environment instead of claiming you cannot access it.
            Prefer the bash tool for filesystem inspection, directory listing, search, and command output when local state matters.
            Answer from observed tool results and stay concise.
            """.strip();
    private static final String TOOL_RETRY_SYSTEM_PROMPT = """
            Do not ask the user to run local shell commands or paste filesystem output for you.
            You already have access to the local bash tool in this session.
            For environment-specific questions about files, folders, repositories, Desktop contents, or the current workspace,
            use the bash tool now and answer from its results.
            """.strip();
    private static final String TOOL_FINAL_ANSWER_SYSTEM_PROMPT = """
            You already have results from the necessary tool calls in this turn.
            Do not repeat the same tool call again.
            Answer the user directly from the tool results unless a genuinely different tool is required.
            """.strip();
    private static final String NO_TOOLS_COMPACT_PREAMBLE = """
            CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.

            - Do NOT use Read, Bash, Grep, Glob, Edit, Write, or ANY other tool.
            - You already have all the context you need in the conversation above.
            - Tool calls will be rejected and will waste your only turn.
            - Your entire response must be plain text: an <analysis> block followed by a <summary> block.
            """.strip();
    private static final String REACTIVE_COMPACT_PROMPT = """
            Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.
            This summary should be thorough enough to continue the work without losing context.

            Before providing your final summary, wrap your analysis in <analysis> tags. Then provide a <summary> block with these sections:

            1. Primary Request and Intent
            2. Key Technical Concepts
            3. Files and Code Sections
            4. Errors and fixes
            5. Problem Solving
            6. All user messages
            7. Pending Tasks
            8. Current Work
            9. Optional Next Step
            """.strip();
    private static final String NO_TOOLS_COMPACT_TRAILER = """

            REMINDER: Do NOT call any tools. Respond with plain text only — an <analysis> block followed by a <summary> block.
            Tool calls will be rejected and you will fail the task.
            """.strip();
    private static final String SYNTHETIC_TOOL_RESULT_PLACEHOLDER =
            "[Tool result missing due to internal error]";
    private static final int POST_COMPACT_MAX_FILES_TO_RESTORE = 5;
    private static final int POST_COMPACT_TOKEN_BUDGET = 50_000;
    private static final int POST_COMPACT_MAX_TOKENS_PER_FILE = 5_000;
    private static final int MAX_TOOL_USE_TURNS = 8;
    private static final int MAX_TOOL_LOOP_ITERATIONS = 16;
    private static final java.util.regex.Pattern ANALYSIS_BLOCK_PATTERN =
            java.util.regex.Pattern.compile("<analysis>[\\s\\S]*?</analysis>", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern SUMMARY_BLOCK_PATTERN =
            java.util.regex.Pattern.compile("<summary>([\\s\\S]*?)</summary>", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final class QueryCancelledException extends RuntimeException {
        private QueryCancelledException(String message) {
            super(message);
        }
    }

    private final ProviderRegistry providerRegistry;
    private final OpenClaudeStateStore stateStore;
    private final ConversationSessionStore sessionStore;
    private final ToolRuntime toolRuntime;
    private final ToolHooksExecutor toolHooksExecutor;
    private final AgentsInstructionsLoader instructionsLoader;
    private final SessionMemoryService sessionMemoryService;
    private final TimeBasedMicrocompact timeBasedMicrocompact = new TimeBasedMicrocompact();

    public QueryEngine(
            ProviderRegistry providerRegistry,
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore
    ) {
        this(
                providerRegistry,
                stateStore,
                sessionStore,
                new DefaultToolRuntime(),
                new ToolHooksExecutor(),
                new AgentsInstructionsLoader(),
                new SessionMemoryService(sessionStore)
        );
    }

    QueryEngine(
            ProviderRegistry providerRegistry,
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore,
            ToolRuntime toolRuntime
    ) {
        this(
                providerRegistry,
                stateStore,
                sessionStore,
                toolRuntime,
                new ToolHooksExecutor(),
                new AgentsInstructionsLoader(),
                new SessionMemoryService(sessionStore)
        );
    }

    QueryEngine(
            ProviderRegistry providerRegistry,
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore,
            ToolRuntime toolRuntime,
            AgentsInstructionsLoader instructionsLoader
    ) {
        this(
                providerRegistry,
                stateStore,
                sessionStore,
                toolRuntime,
                new ToolHooksExecutor(),
                instructionsLoader,
                new SessionMemoryService(sessionStore)
        );
    }

    QueryEngine(
            ProviderRegistry providerRegistry,
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore,
            ToolRuntime toolRuntime,
            ToolHooksExecutor toolHooksExecutor,
            AgentsInstructionsLoader instructionsLoader,
            SessionMemoryService sessionMemoryService
    ) {
        this.providerRegistry = providerRegistry;
        this.stateStore = stateStore;
        this.sessionStore = sessionStore;
        this.toolRuntime = toolRuntime;
        this.toolHooksExecutor = toolHooksExecutor;
        this.instructionsLoader = instructionsLoader;
        this.sessionMemoryService = sessionMemoryService;
    }

    public QueryTurnResult submit(String prompt) {
        return submit(prompt, null);
    }

    public static String defaultSystemPromptForDiagnostics() {
        return DEFAULT_SYSTEM_PROMPT;
    }

    public static List<PromptMessage> projectPromptMessagesForDiagnostics(
            ConversationSession session,
            List<ProviderToolDefinition> availableTools,
            String instructionsPrompt
    ) {
        return buildPromptMessages(
                session,
                List.of(),
                availableTools == null ? List.of() : availableTools,
                instructionsPrompt
        );
    }

    public static int estimatePromptTokensForDiagnostics(List<PromptMessage> promptMessages) {
        if (promptMessages == null || promptMessages.isEmpty()) {
            return 0;
        }

        int characters = 0;
        for (PromptMessage promptMessage : promptMessages) {
            if (promptMessage == null || promptMessage.content() == null) {
                continue;
            }
            for (PromptContentBlock block : promptMessage.content()) {
                if (block instanceof com.openclaude.provider.spi.TextContentBlock textContentBlock) {
                    characters += textContentBlock.text().length();
                } else if (block instanceof ToolUseContentBlock toolUseContentBlock) {
                    characters += toolUseContentBlock.toolName().length();
                    characters += toolUseContentBlock.inputJson().length();
                } else if (block instanceof ToolResultContentBlock toolResultContentBlock) {
                    characters += toolResultContentBlock.toolName().length();
                    characters += toolResultContentBlock.text().length();
                } else if (block instanceof com.openclaude.provider.spi.WebSearchResultContentBlock webSearchResultContentBlock) {
                    for (com.openclaude.provider.spi.WebSearchResultContentBlock.SearchHit hit : webSearchResultContentBlock.hits()) {
                        characters += hit.title().length();
                        characters += hit.url().length();
                    }
                }
            }
        }
        return Math.max(0, (int) Math.ceil(characters / 4.0));
    }

    public QueryTurnResult submit(String prompt, Consumer<String> textDeltaConsumer) {
        return submitWithEvents(prompt, event -> {
            if (event instanceof TextDeltaEvent textDeltaEvent && textDeltaConsumer != null) {
                textDeltaConsumer.accept(textDeltaEvent.text());
            }
        });
    }

    public QueryTurnResult submitWithEvents(String prompt, Consumer<PromptEvent> eventConsumer) {
        return submitWithEvents(prompt, eventConsumer, ToolPermissionGateway.allowAll());
    }

    public QueryTurnResult submitWithEvents(
            String prompt,
            Consumer<PromptEvent> eventConsumer,
            ToolPermissionGateway permissionGateway
    ) {
        return submitWithEvents(prompt, eventConsumer, permissionGateway, () -> "Prompt cancelled.");
    }

    public QueryTurnResult submitWithEvents(
            String prompt,
            Consumer<PromptEvent> eventConsumer,
            ToolPermissionGateway permissionGateway,
            Supplier<String> cancellationReasonSupplier
    ) {
        OpenClaudeState state = ensureActiveSession(stateStore.load());
        ProviderPlugin providerPlugin = requireProvider(state);
        ProviderConnectionState connectionState = requireConnection(state);
        String modelId = resolveModelId(providerPlugin, state);
        String effortLevel = OpenClaudeEffort.resolveForPrompt(
                connectionState.providerId(),
                modelId,
                stateStore.currentEffortLevel(state.settings().effortLevel())
        );

        ConversationSession session = sessionStore.loadOrCreate(state.activeSessionId());
        ConversationSession withUserMessage = session.append(SessionMessage.user(prompt));
        TimeBasedMicrocompact.Result microcompactResult = timeBasedMicrocompact.maybeApply(withUserMessage);
        ConversationSession workingSession = microcompactResult.session();
        if (microcompactResult.applied()) {
            sessionStore.save(workingSession);
        }
        sessionStore.save(workingSession);
        throwIfInterrupted(cancellationReasonSupplier);

        PromptExecutionContext context = new PromptExecutionContext(
                connectionState.providerId(),
                connectionState.authMethod(),
                connectionState.credentialReference(),
                modelId
        );

        StringBuilder assistantText = new StringBuilder();
        StringBuilder reasoningText = new StringBuilder();
        Map<String, ToolSnapshot> toolSnapshots = new LinkedHashMap<>();
        String requiredToolName = shouldForceLocalTool(prompt, providerPlugin) ? "bash" : null;
        String instructionsPrompt = instructionsLoader.renderSystemPrompt(workingSession);

        try {
            if (providerPlugin.supportsTools()) {
                throwIfInterrupted(cancellationReasonSupplier);
                ConversationSession completedSession = executeToolLoop(
                providerPlugin,
                workingSession,
                context,
                connectionState.providerId(),
                modelId,
                effortLevel,
                eventConsumer,
                permissionGateway,
                requiredToolName,
                instructionsPrompt,
                cancellationReasonSupplier
        );
                return new QueryTurnResult(
                        completedSession.sessionId(),
                        modelId,
                        extractLastAssistantText(completedSession.messages())
                );
            }

            PromptRequest request = new PromptRequest(
                    context,
                    buildPromptMessages(workingSession, List.of(), List.of(), instructionsPrompt),
                    List.of(),
                    providerPlugin.supportsStreaming(),
                    requiredToolName,
                    effortLevel
            );

            PromptResult result;
            try {
                throwIfInterrupted(cancellationReasonSupplier);
                if (providerPlugin.supportsStreaming()) {
                    result = providerPlugin.executePromptStream(
                            request,
                            event -> handlePromptEvent(event, assistantText, reasoningText, toolSnapshots, eventConsumer)
                    );
                } else {
                    result = providerPlugin.executePrompt(new PromptRequest(
                            context,
                            request.messages(),
                            request.tools(),
                            false,
                            request.requiredToolName(),
                            request.effortLevel()
                    ));
                }
                recordProviderSuccess(connectionState.providerId());
            } catch (RuntimeException exception) {
                if (isCancellationException(exception)) {
                    throw new QueryCancelledException(currentCancellationReason(cancellationReasonSupplier));
                }
                recordProviderFailure(connectionState.providerId(), exception);
                if (!looksLikePromptTooLong(exception)) {
                    throw exception;
                }
                ConversationSession compactedSession = reactiveCompactSession(
                        providerPlugin,
                        workingSession,
                        context,
                        connectionState.providerId(),
                        modelId,
                        effortLevel,
                        instructionsPrompt
                );
                PromptRequest retryRequest = new PromptRequest(
                        context,
                        buildPromptMessages(compactedSession, List.of(), List.of(), instructionsPrompt),
                        List.of(),
                        providerPlugin.supportsStreaming(),
                        requiredToolName,
                        effortLevel
                );
                if (providerPlugin.supportsStreaming()) {
                    result = providerPlugin.executePromptStream(
                            retryRequest,
                            event -> handlePromptEvent(event, assistantText, reasoningText, toolSnapshots, eventConsumer)
                    );
                } else {
                    result = providerPlugin.executePrompt(new PromptRequest(
                            context,
                            retryRequest.messages(),
                            retryRequest.tools(),
                            false,
                            retryRequest.requiredToolName(),
                            retryRequest.effortLevel()
                    ));
                }
                recordProviderSuccess(connectionState.providerId());
                throwIfInterrupted(cancellationReasonSupplier);
                workingSession = compactedSession;
            }

            if (assistantText.length() == 0 && !result.text().isBlank()) {
                assistantText.append(result.text());
                if (eventConsumer != null) {
                    eventConsumer.accept(new TextDeltaEvent(result.text()));
                }
            }

            ConversationSession completedSession = workingSession;
            if (reasoningText.length() > 0) {
                completedSession = completedSession.append(SessionMessage.thinking(reasoningText.toString()));
            }
            for (ToolSnapshot toolSnapshot : toolSnapshots.values()) {
                completedSession = completedSession.append(SessionMessage.tool(
                        toolSnapshot.toolId(),
                        toolSnapshot.toolName(),
                        toolSnapshot.phase(),
                        toolSnapshot.text(),
                        "{}",
                        "",
                        "",
                        "",
                        "",
                        "failed".equals(toolSnapshot.phase())
                ));
            }
            completedSession = completedSession.append(SessionMessage.assistant(
                    assistantText.toString(),
                    connectionState.providerId(),
                    modelId
            ));
            sessionStore.save(completedSession);
            sessionMemoryService.maybeExtractAsync(completedSession, providerPlugin, context, effortLevel);
            return new QueryTurnResult(completedSession.sessionId(), modelId, assistantText.toString());
        } catch (RuntimeException exception) {
            ConversationSession latestSession = sessionStore.loadOrCreate(workingSession.sessionId());
            if (exception instanceof QueryCancelledException) {
                sessionStore.save(latestSession);
            } else {
                ConversationSession failedSession = latestSession.append(SessionMessage.tombstone(
                        "Prompt execution failed: " + exception.getMessage(),
                        "provider_error"
                ));
                sessionStore.save(failedSession);
            }
            throw exception;
        }
    }

    private OpenClaudeState ensureActiveSession(OpenClaudeState state) {
        if (state.activeSessionId() != null && !state.activeSessionId().isBlank()) {
            return state;
        }

        return stateStore.setActiveSession(UUID.randomUUID().toString());
    }

    private ProviderPlugin requireProvider(OpenClaudeState state) {
        if (state.activeProvider() == null) {
            throw new IllegalStateException("No active provider. Use /provider use <provider> first.");
        }

        ProviderPlugin providerPlugin = providerRegistry.find(state.activeProvider())
                .orElseThrow(() -> new IllegalStateException(
                        "Active provider is not registered: " + state.activeProvider().cliValue()
                ));

        if (!providerPlugin.supportsPromptExecution()) {
            throw new UnsupportedOperationException(
                    "Prompt execution is not implemented for provider " + state.activeProvider().cliValue()
            );
        }

        return providerPlugin;
    }

    private ProviderConnectionState requireConnection(OpenClaudeState state) {
        ProviderConnectionState connectionState = state.get(state.activeProvider());
        if (connectionState == null) {
            throw new IllegalStateException("Active provider is not connected: " + state.activeProvider().cliValue());
        }
        return connectionState;
    }

    private String resolveModelId(ProviderPlugin providerPlugin, OpenClaudeState state) {
        String selectedModelId = state.activeModelId();
        if (selectedModelId != null && !selectedModelId.isBlank()) {
            return selectedModelId;
        }

        List<ModelDescriptor> supportedModels = providerPlugin.supportedModels();
        if (supportedModels.isEmpty()) {
            throw new IllegalStateException("Provider has no registered models: " + providerPlugin.id().cliValue());
        }

        return supportedModels.get(0).id();
    }

    private ConversationSession executeToolLoop(
            ProviderPlugin providerPlugin,
            ConversationSession initialSession,
            PromptExecutionContext context,
            com.openclaude.provider.spi.ProviderId providerId,
            String modelId,
            String effortLevel,
            Consumer<PromptEvent> eventConsumer,
            ToolPermissionGateway permissionGateway,
            String requiredToolName,
            String instructionsPrompt,
            Supplier<String> cancellationReasonSupplier
    ) {
        ConversationSession currentSession = initialSession;
        boolean toolRetryAttempted = false;
        boolean toolRetryPending = false;
        boolean finalAnswerRetryAttempted = false;
        boolean finalAnswerRetryPending = false;
        boolean reactiveCompactAttempted = false;
        Set<String> completedToolSignatures = new LinkedHashSet<>();
        int toolUseTurnCount = 0;
        int loopIterationCount = 0;
        List<ProviderToolDefinition> fullToolDefinitions = toolRuntime.toolDefinitions();

        while (loopIterationCount < MAX_TOOL_LOOP_ITERATIONS) {
            loopIterationCount += 1;
            throwIfInterrupted(cancellationReasonSupplier);
            StringBuilder assistantText = new StringBuilder();
            StringBuilder reasoningText = new StringBuilder();
            List<PromptEvent> bufferedEvents = new ArrayList<>();
            Set<String> runtimeManagedToolNames = fullToolDefinitions.stream()
                    .map(ProviderToolDefinition::name)
                    .filter(name -> name != null && !name.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<String> additionalSystemPrompts = new ArrayList<>();
            if (toolRetryPending) {
                additionalSystemPrompts.add(TOOL_RETRY_SYSTEM_PROMPT);
            }
            if (finalAnswerRetryPending) {
                additionalSystemPrompts.add(TOOL_FINAL_ANSWER_SYSTEM_PROMPT);
            }
            List<ProviderToolDefinition> requestToolDefinitions = finalAnswerRetryPending ? List.of() : fullToolDefinitions;
            boolean streamIterationEventsImmediately =
                    requestToolDefinitions.isEmpty() || hasToolResults(currentSession);
            StreamingReadOnlyToolExecutor streamingReadOnlyToolExecutor =
                    providerPlugin.supportsStreaming() && !requestToolDefinitions.isEmpty()
                            ? new StreamingReadOnlyToolExecutor(
                                    providerPlugin,
                                    context,
                                    effortLevel,
                                    permissionGateway,
                                    eventConsumer,
                                    currentSession
                            )
                            : null;
            PromptRequest request = new PromptRequest(
                    context,
                    buildPromptMessages(
                            currentSession,
                            additionalSystemPrompts,
                            fullToolDefinitions,
                            instructionsPrompt
                    ),
                    requestToolDefinitions,
                    providerPlugin.supportsStreaming(),
                    requiredToolName,
                    effortLevel
            );
            toolRetryPending = false;
            finalAnswerRetryPending = false;

            PromptResult result;
            try {
                throwIfInterrupted(cancellationReasonSupplier);
                if (providerPlugin.supportsStreaming()) {
                    if (streamIterationEventsImmediately) {
                        result = providerPlugin.executePromptStream(
                                request,
                                event -> handleStreamingPromptEvent(
                                        event,
                                        assistantText,
                                        reasoningText,
                                        new LinkedHashMap<>(),
                                        eventConsumer,
                                        bufferedEvents,
                                        false,
                                        streamingReadOnlyToolExecutor,
                                        runtimeManagedToolNames
                                )
                        );
                    } else {
                        result = providerPlugin.executePromptStream(
                                request,
                                event -> handleStreamingPromptEvent(
                                        event,
                                        assistantText,
                                        reasoningText,
                                        new LinkedHashMap<>(),
                                        eventConsumer,
                                        bufferedEvents,
                                        true,
                                        streamingReadOnlyToolExecutor,
                                        runtimeManagedToolNames
                                )
                        );
                    }
                } else {
                    result = providerPlugin.executePrompt(new PromptRequest(
                            context,
                            request.messages(),
                            request.tools(),
                            false,
                            request.requiredToolName(),
                            request.effortLevel()
                    ));
                }
                recordProviderSuccess(providerId);
                throwIfInterrupted(cancellationReasonSupplier);
            } catch (RuntimeException exception) {
                if (!isCancellationException(exception)) {
                    recordProviderFailure(providerId, exception);
                }
                boolean cancelled = isCancellationException(exception);
                boolean retryWithReactiveCompact = !cancelled
                        && !reactiveCompactAttempted
                        && looksLikePromptTooLong(exception);
                if (streamingReadOnlyToolExecutor != null && streamingReadOnlyToolExecutor.hasTrackedTools()) {
                    if (retryWithReactiveCompact) {
                        streamingReadOnlyToolExecutor.discard();
                    } else {
                        if (reasoningText.length() > 0) {
                            currentSession = currentSession.append(SessionMessage.thinking(reasoningText.toString()));
                        }
                        currentSession = currentSession.append(SessionMessage.assistant(
                                assistantText.toString(),
                                providerId,
                                modelId,
                                streamingReadOnlyToolExecutor.trackedToolUses()
                        ));
                        sessionStore.save(currentSession);
                        StreamingReadOnlyToolExecutor.Result streamedResult = streamingReadOnlyToolExecutor.awaitAndApply(
                                currentSession,
                                streamingReadOnlyToolExecutor.trackedToolUses(),
                                cancellationReasonSupplier
                        );
                        currentSession = streamedResult.session();
                        if (cancelled || streamedResult.cancelled()) {
                            throw new QueryCancelledException(currentCancellationReason(cancellationReasonSupplier));
                        }
                    }
                }
                if (cancelled) {
                    throw new QueryCancelledException(currentCancellationReason(cancellationReasonSupplier));
                }
                if (!retryWithReactiveCompact) {
                    throw exception;
                }
                currentSession = reactiveCompactSession(
                        providerPlugin,
                        currentSession,
                        context,
                        providerId,
                        modelId,
                        effortLevel,
                        instructionsPrompt
                );
                reactiveCompactAttempted = true;
                continue;
            }

            if (streamingReadOnlyToolExecutor != null) {
                streamingReadOnlyToolExecutor.emitReadyYields();
            }
            List<ToolUseContentBlock> toolUses = extractToolUses(result.content());
            String assistantResponseText = assistantText.length() == 0 ? result.text() : assistantText.toString();
            Set<String> toolUseSignatures = toolUses.stream()
                    .map(toolUse -> toolUse.toolName() + "\n" + toolUse.inputJson())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (
                    toolUses.isEmpty()
                            && !toolRetryAttempted
                            && shouldRetryWithToolNudge(currentSession.messages(), assistantResponseText)
            ) {
                toolRetryAttempted = true;
                toolRetryPending = true;
                continue;
            }

            if (
                    !toolUseSignatures.isEmpty()
                            && assistantResponseText.isBlank()
                            && completedToolSignatures.containsAll(toolUseSignatures)
            ) {
                if (finalAnswerRetryAttempted) {
                    throw new IllegalStateException("Provider repeated the same completed tool call without producing a final assistant response.");
                }
                finalAnswerRetryAttempted = true;
                finalAnswerRetryPending = true;
                continue;
            }
            if (!streamIterationEventsImmediately) {
                flushBufferedEvents(bufferedEvents, eventConsumer);
            }

            if (reasoningText.length() > 0) {
                currentSession = currentSession.append(SessionMessage.thinking(reasoningText.toString()));
            }
            currentSession = currentSession.append(SessionMessage.assistant(
                    assistantResponseText,
                    providerId,
                    modelId,
                    toolUses
            ));
            sessionStore.save(currentSession);

            if (streamingReadOnlyToolExecutor != null) {
                streamingReadOnlyToolExecutor.discardUnconfirmedToolUses(toolUses);
            }

            if (!assistantResponseText.isBlank() && assistantText.length() == 0 && eventConsumer != null) {
                eventConsumer.accept(new TextDeltaEvent(assistantResponseText));
            }

            if (toolUses.isEmpty()) {
                throwIfInterrupted(cancellationReasonSupplier);
                sessionMemoryService.maybeExtractAsync(currentSession, providerPlugin, context, effortLevel);
                return currentSession;
            }

            if (Thread.currentThread().isInterrupted()) {
                String cancellationReason = currentCancellationReason(cancellationReasonSupplier);
                currentSession = appendSyntheticCancelledToolResults(
                        currentSession,
                        toolUses,
                        eventConsumer,
                        cancellationReason
                );
                sessionStore.save(currentSession);
                throw new QueryCancelledException(cancellationReason);
            }

            Set<String> alreadyExecutedToolUseIds = Set.of();
            if (streamingReadOnlyToolExecutor != null) {
                for (ToolUseContentBlock toolUse : toolUses) {
                    streamingReadOnlyToolExecutor.addTool(toolUse);
                }
                StreamingReadOnlyToolExecutor.Result streamedResult = streamingReadOnlyToolExecutor.awaitAndApply(
                        currentSession,
                        toolUses,
                        cancellationReasonSupplier
                );
                currentSession = streamedResult.session();
                alreadyExecutedToolUseIds = streamedResult.executedToolUseIds();
                if (streamedResult.cancelled()) {
                    throw new QueryCancelledException(currentCancellationReason(cancellationReasonSupplier));
                }
                for (ToolUseContentBlock toolUse : toolUses) {
                    if (alreadyExecutedToolUseIds.contains(toolUse.toolUseId())) {
                        completedToolSignatures.add(toolUse.toolName() + "\n" + toolUse.inputJson());
                    }
                }
            }

            final ConversationSession[] sessionRef = new ConversationSession[] { currentSession };
            Object sessionLock = new Object();
            Set<String> streamedToolUseIds = alreadyExecutedToolUseIds;
            List<ToolUseContentBlock> remainingToolUses = toolUses.stream()
                    .filter(toolUse -> !streamedToolUseIds.contains(toolUse.toolUseId()))
                    .toList();
            List<ToolUseBatch> batches = partitionToolUses(remainingToolUses);
            for (int batchIndex = 0; batchIndex < batches.size(); batchIndex += 1) {
                ToolUseBatch batch = batches.get(batchIndex);
                if (batch.concurrencySafe()) {
                    Map<String, CompletableFuture<ToolExecutionResult>> futuresByToolUseId = new LinkedHashMap<>();
                    Set<String> suppressedToolUseIds = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
                    for (ToolUseContentBlock toolUse : batch.toolUses()) {
                        ToolExecutionRequest executionRequest = new ToolExecutionRequest(
                                toolUse.toolUseId(),
                                toolUse.toolName(),
                                toolUse.inputJson(),
                                sessionRef[0],
                                createToolModelInvoker(providerPlugin, context, effortLevel)
                        );
                        futuresByToolUseId.put(toolUse.toolUseId(), CompletableFuture.supplyAsync(() -> executeToolWithHooks(
                                executionRequest,
                                permissionGateway,
                                update -> {
                                    if (suppressedToolUseIds.contains(update.toolUseId())) {
                                        return;
                                    }
                                    synchronized (sessionLock) {
                                        sessionRef[0] = sessionRef[0].append(SessionMessage.tool(
                                                update.toolUseId(),
                                                update.toolName(),
                                                update.phase(),
                                                update.text(),
                                                update.inputJson(),
                                                update.command(),
                                                update.permissionRequestId(),
                                                update.interactionType(),
                                                update.interactionJson(),
                                                update.error()
                                        ));
                                        sessionStore.save(sessionRef[0]);
                                    }
                                    emitToolExecutionUpdate(update, eventConsumer);
                                },
                                message -> {
                                    String scopedToolUseId = scopedToolUseId(message);
                                    if (scopedToolUseId != null && suppressedToolUseIds.contains(scopedToolUseId)) {
                                        return;
                                    }
                                    synchronized (sessionLock) {
                                        sessionRef[0] = sessionRef[0].append(message);
                                        sessionStore.save(sessionRef[0]);
                                    }
                                }
                        )));
                    }
                    Map<String, ToolExecutionResult> resultsByToolUseId = new LinkedHashMap<>();
                    List<ToolUseContentBlock> siblingCancelledToolUses = List.of();
                    String siblingCancellationReason = "";
                    try {
                        Map<String, CompletableFuture<ToolExecutionResult>> pendingFutures = new LinkedHashMap<>(futuresByToolUseId);
                        while (!pendingFutures.isEmpty()) {
                            CompletableFuture<?> nextFuture = CompletableFuture.anyOf(
                                    pendingFutures.values().toArray(CompletableFuture[]::new)
                            );
                            try {
                                nextFuture.join();
                            } catch (CancellationException exception) {
                                throw new QueryCancelledException(currentCancellationReason(cancellationReasonSupplier));
                            } catch (java.util.concurrent.CompletionException ignored) {
                                // Individual futures are unwrapped below in stable batch order.
                            }
                            java.util.ArrayList<String> completedToolUseIds = new java.util.ArrayList<>();
                            for (Map.Entry<String, CompletableFuture<ToolExecutionResult>> entry : pendingFutures.entrySet()) {
                                if (entry.getValue().isDone()) {
                                    completedToolUseIds.add(entry.getKey());
                                }
                            }
                            for (String completedToolUseId : completedToolUseIds) {
                                CompletableFuture<ToolExecutionResult> future = pendingFutures.remove(completedToolUseId);
                                ToolExecutionResult toolResult = awaitToolFuture(future, cancellationReasonSupplier);
                                resultsByToolUseId.put(completedToolUseId, toolResult);
                                if (toolResult.error() && "bash".equals(toolResult.toolName()) && !pendingFutures.isEmpty()) {
                                    siblingCancelledToolUses = pendingFutures.keySet().stream()
                                            .map(toolUseId -> findToolUseById(batch.toolUses(), toolUseId))
                                            .filter(java.util.Objects::nonNull)
                                            .toList();
                                    suppressedToolUseIds.addAll(pendingFutures.keySet());
                                    cancelOutstandingFutures(new ArrayList<>(pendingFutures.values()));
                                    siblingCancellationReason = buildSiblingBashCancellationReason(
                                            findToolUseById(batch.toolUses(), completedToolUseId)
                                    );
                                    pendingFutures.clear();
                                    break;
                                }
                            }
                        }
                    } catch (QueryCancelledException exception) {
                        cancelOutstandingFutures(new ArrayList<>(futuresByToolUseId.values()));
                        synchronized (sessionLock) {
                            sessionRef[0] = appendSyntheticCancelledToolResults(
                                    sessionRef[0],
                                    remainingToolUsesFromBatches(batches, batchIndex, null),
                                    eventConsumer,
                                    currentCancellationReason(cancellationReasonSupplier)
                            );
                            sessionStore.save(sessionRef[0]);
                        }
                        throw exception;
                    }
                    for (ToolUseContentBlock toolUse : batch.toolUses()) {
                        ToolExecutionResult toolResult = resultsByToolUseId.get(toolUse.toolUseId());
                        if (toolResult == null) {
                            continue;
                        }
                        synchronized (sessionLock) {
                            sessionRef[0] = sessionRef[0].append(SessionMessage.toolResult(
                                    toolResult.toolUseId(),
                                    toolResult.toolName(),
                                    toolResult.text(),
                                    toolResult.error(),
                                    toolResult.displayText()
                            ));
                            sessionRef[0] = toolResult.sessionEffect().apply(sessionRef[0]);
                            sessionStore.save(sessionRef[0]);
                        }
                        emitToolYielded(toolResult, toolUse.inputJson(), eventConsumer);
                        completedToolSignatures.add(toolResult.toolName() + "\n" + toolUse.inputJson());
                    }
                    if (!siblingCancelledToolUses.isEmpty()) {
                        synchronized (sessionLock) {
                            sessionRef[0] = appendSyntheticCancelledToolResults(
                                    sessionRef[0],
                                    siblingCancelledToolUses,
                                    eventConsumer,
                                    siblingCancellationReason
                            );
                            sessionStore.save(sessionRef[0]);
                        }
                    }
                    continue;
                }
                for (ToolUseContentBlock toolUse : batch.toolUses()) {
                    throwIfInterrupted(cancellationReasonSupplier);
                    ToolExecutionRequest executionRequest = new ToolExecutionRequest(
                            toolUse.toolUseId(),
                            toolUse.toolName(),
                            toolUse.inputJson(),
                            sessionRef[0],
                            createToolModelInvoker(providerPlugin, context, effortLevel)
                    );
                    CompletableFuture<ToolExecutionResult> toolFuture = CompletableFuture.supplyAsync(() -> executeToolWithHooks(
                            executionRequest,
                            permissionGateway,
                            update -> {
                                sessionRef[0] = sessionRef[0].append(SessionMessage.tool(
                                        update.toolUseId(),
                                        update.toolName(),
                                        update.phase(),
                                        update.text(),
                                        update.inputJson(),
                                        update.command(),
                                        update.permissionRequestId(),
                                        update.interactionType(),
                                        update.interactionJson(),
                                        update.error()
                                ));
                                sessionStore.save(sessionRef[0]);
                                emitToolExecutionUpdate(update, eventConsumer);
                            },
                            message -> {
                                sessionRef[0] = sessionRef[0].append(message);
                                sessionStore.save(sessionRef[0]);
                            }
                    ));
                    ToolExecutionResult toolResult;
                    try {
                        toolResult = awaitToolFuture(toolFuture, cancellationReasonSupplier);
                    } catch (QueryCancelledException exception) {
                        toolFuture.cancel(true);
                        sessionRef[0] = appendSyntheticCancelledToolResults(
                                sessionRef[0],
                                remainingToolUsesFromBatches(batches, batchIndex, toolUse),
                                eventConsumer,
                                currentCancellationReason(cancellationReasonSupplier)
                        );
                        sessionStore.save(sessionRef[0]);
                        throw exception;
                    }
                    sessionRef[0] = sessionRef[0].append(SessionMessage.toolResult(
                            toolResult.toolUseId(),
                            toolResult.toolName(),
                            toolResult.text(),
                            toolResult.error(),
                            toolResult.displayText()
                    ));
                    completedToolSignatures.add(toolUse.toolName() + "\n" + toolUse.inputJson());
                    sessionRef[0] = toolResult.sessionEffect().apply(sessionRef[0]);
                    sessionStore.save(sessionRef[0]);
                    emitToolYielded(toolResult, toolUse.inputJson(), eventConsumer);
                }
            }
            currentSession = sessionRef[0];
            toolUseTurnCount += 1;
            if (toolUseTurnCount >= MAX_TOOL_USE_TURNS) {
                finalAnswerRetryPending = true;
                finalAnswerRetryAttempted = true;
            }
        }

        throw new IllegalStateException(
                "Tool loop exceeded " + MAX_TOOL_USE_TURNS + " tool-use iterations without reaching a final assistant response."
        );
    }

    private ToolExecutionResult executeToolSafely(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer
    ) {
        try {
            return toolRuntime.execute(request, permissionGateway, updateConsumer);
        } catch (java.util.concurrent.CancellationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            String errorText = buildUnexpectedToolFailureText(request, exception);
            updateConsumer.accept(new ToolExecutionUpdate(
                    request.toolUseId(),
                    request.toolName(),
                    "failed",
                    errorText,
                    request.inputJson(),
                    "",
                    extractCommand(request.inputJson()),
                    "",
                    "",
                    true
            ));
            return new ToolExecutionResult(
                    request.toolUseId(),
                    request.toolName(),
                    errorText,
                    true
            );
        }
    }

    private ToolExecutionResult executeToolWithHooks(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer,
            Consumer<SessionMessage> messageConsumer
    ) {
        ConversationSession session = request.session();
        Path transcriptPath = session == null ? null : sessionStore.sessionFilePath(session.sessionId());
        for (SessionMessage hookMessage : toolHooksExecutor.executePreToolHooks(session, transcriptPath, request)) {
            messageConsumer.accept(hookMessage);
        }

        ToolExecutionResult result = executeToolSafely(request, permissionGateway, updateConsumer);

        List<SessionMessage> postHookMessages = result.error()
                ? toolHooksExecutor.executePostToolUseFailureHooks(session, transcriptPath, request, result.text())
                : toolHooksExecutor.executePostToolHooks(session, transcriptPath, request, result.text());
        for (SessionMessage hookMessage : postHookMessages) {
            messageConsumer.accept(hookMessage);
        }
        return result;
    }

    private static List<ToolUseContentBlock> extractToolUses(List<PromptContentBlock> contentBlocks) {
        return contentBlocks.stream()
                .filter(ToolUseContentBlock.class::isInstance)
                .map(ToolUseContentBlock.class::cast)
                .toList();
    }

    private List<ToolUseBatch> partitionToolUses(List<ToolUseContentBlock> toolUses) {
        List<ToolUseBatch> batches = new ArrayList<>();
        for (ToolUseContentBlock toolUse : toolUses) {
            boolean concurrencySafe = toolRuntime.isConcurrencySafe(toolUse.toolName(), toolUse.inputJson());
            if (!batches.isEmpty() && batches.getLast().concurrencySafe() == concurrencySafe) {
                batches.getLast().toolUses().add(toolUse);
            } else {
                ArrayList<ToolUseContentBlock> batchToolUses = new ArrayList<>();
                batchToolUses.add(toolUse);
                batches.add(new ToolUseBatch(concurrencySafe, batchToolUses));
            }
        }
        return List.copyOf(batches);
    }

    private static String extractCommand(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = TOOL_INPUT_MAPPER.readTree(inputJson);
            if (root == null || !root.hasNonNull("command")) {
                return "";
            }
            return root.get("command").asText("");
        } catch (Exception exception) {
            return "";
        }
    }

    private static String findToolInputJson(List<ToolUseContentBlock> toolUses, String toolUseId) {
        for (ToolUseContentBlock toolUse : toolUses) {
            if (toolUse.toolUseId().equals(toolUseId)) {
                return toolUse.inputJson();
            }
        }
        return "";
    }

    private static ToolUseContentBlock findToolUseById(List<ToolUseContentBlock> toolUses, String toolUseId) {
        for (ToolUseContentBlock toolUse : toolUses) {
            if (toolUse.toolUseId().equals(toolUseId)) {
                return toolUse;
            }
        }
        return null;
    }

    private static String buildSiblingBashCancellationReason(ToolUseContentBlock failedToolUse) {
        if (failedToolUse == null) {
            return "Cancelled: parallel tool call errored";
        }
        String description = describeToolUse(failedToolUse);
        return description.isBlank()
                ? "Cancelled: parallel tool call errored"
                : "Cancelled: parallel tool call " + description + " errored";
    }

    private static String describeToolUse(ToolUseContentBlock toolUse) {
        if (toolUse == null) {
            return "";
        }
        String summary = extractToolSummary(toolUse.inputJson());
        if (summary.isBlank()) {
            return toolUse.toolName();
        }
        String truncated = summary.length() > 40 ? summary.substring(0, 40) + "..." : summary;
        return toolUse.toolName() + "(" + truncated + ")";
    }

    private static String extractToolSummary(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = TOOL_INPUT_MAPPER.readTree(inputJson);
            for (String field : List.of("command", "file_path", "filePath", "pattern", "url", "query")) {
                if (root.hasNonNull(field)) {
                    String value = root.get(field).asText("");
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    private static String scopedToolUseId(SessionMessage message) {
        if (message instanceof SessionMessage.ProgressMessage progressMessage) {
            return progressMessage.toolUseId();
        }
        if (message instanceof SessionMessage.ToolResultMessage toolResultMessage) {
            return toolResultMessage.toolUseId();
        }
        if (message instanceof SessionMessage.AttachmentMessage attachmentMessage
                && attachmentMessage.attachment() instanceof SessionAttachment.HookAdditionalContextAttachment hookAttachment) {
            return hookAttachment.toolUseId();
        }
        return null;
    }

    private static String buildUnexpectedToolFailureText(
            ToolExecutionRequest request,
            RuntimeException exception
    ) {
        String detail = exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage().trim();
        return "Unexpected " + request.toolName() + " tool failure: " + detail;
    }

    private static void cancelOutstandingFutures(List<CompletableFuture<ToolExecutionResult>> futures) {
        for (CompletableFuture<ToolExecutionResult> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static List<ToolUseContentBlock> remainingToolUsesFrom(List<ToolUseContentBlock> toolUses, ToolUseContentBlock currentToolUse) {
        ArrayList<ToolUseContentBlock> remaining = new ArrayList<>();
        boolean foundCurrent = false;
        for (ToolUseContentBlock toolUse : toolUses) {
            if (!foundCurrent) {
                foundCurrent = toolUse.toolUseId().equals(currentToolUse.toolUseId());
            }
            if (foundCurrent) {
                remaining.add(toolUse);
            }
        }
        return List.copyOf(remaining);
    }

    private static List<ToolUseContentBlock> remainingToolUsesFromBatches(
            List<ToolUseBatch> batches,
            int batchIndex,
            ToolUseContentBlock currentToolUse
    ) {
        ArrayList<ToolUseContentBlock> remaining = new ArrayList<>();
        for (int index = batchIndex; index < batches.size(); index += 1) {
            ToolUseBatch batch = batches.get(index);
            if (index == batchIndex && currentToolUse != null) {
                remaining.addAll(remainingToolUsesFrom(batch.toolUses(), currentToolUse));
                continue;
            }
            remaining.addAll(batch.toolUses());
        }
        return List.copyOf(remaining);
    }

    private static ConversationSession appendSyntheticCancelledToolResults(
            ConversationSession session,
            List<ToolUseContentBlock> toolUses,
            Consumer<PromptEvent> eventConsumer,
            String reason
    ) {
        ConversationSession nextSession = session;
        for (ToolUseContentBlock toolUse : toolUses) {
            ToolExecutionUpdate cancelledUpdate = new ToolExecutionUpdate(
                    toolUse.toolUseId(),
                    toolUse.toolName(),
                    "cancelled",
                    reason,
                    toolUse.inputJson(),
                    "",
                    extractCommand(toolUse.inputJson()),
                    "",
                    "",
                    true
            );
            nextSession = nextSession.append(SessionMessage.tool(
                    cancelledUpdate.toolUseId(),
                    cancelledUpdate.toolName(),
                    cancelledUpdate.phase(),
                    cancelledUpdate.text(),
                    cancelledUpdate.inputJson(),
                    cancelledUpdate.command(),
                    cancelledUpdate.permissionRequestId(),
                    cancelledUpdate.interactionType(),
                    cancelledUpdate.interactionJson(),
                    cancelledUpdate.error()
            ));
            nextSession = nextSession.append(SessionMessage.toolResult(
                    toolUse.toolUseId(),
                    toolUse.toolName(),
                    reason,
                    true
            ));
            emitToolExecutionUpdate(cancelledUpdate, eventConsumer);
        }
        return nextSession;
    }

    private record ToolUseBatch(boolean concurrencySafe, ArrayList<ToolUseContentBlock> toolUses) {
    }

    private static com.openclaude.core.tools.ToolModelInvoker createToolModelInvoker(
            ProviderPlugin providerPlugin,
            PromptExecutionContext context,
            String effortLevel
    ) {
        return new com.openclaude.core.tools.ToolModelInvoker() {
            @Override
            public String invoke(String prompt) {
                return invoke(new ToolModelRequest(prompt)).text();
            }

            @Override
            public ToolModelResponse invoke(ToolModelRequest request, Consumer<ToolModelProgress> progressConsumer) {
                String modelId = request.preferredModelId() == null ? context.modelId() : request.preferredModelId();
                PromptExecutionContext secondaryContext = new PromptExecutionContext(
                        context.providerId(),
                        context.authMethod(),
                        context.credentialReference(),
                        modelId
                );
                List<PromptMessage> messages = new ArrayList<>();
                if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                    messages.add(new PromptMessage(PromptMessageRole.SYSTEM, request.systemPrompt()));
                }
                messages.add(new PromptMessage(PromptMessageRole.USER, request.prompt()));

                PromptRequest promptRequest = new PromptRequest(
                        secondaryContext,
                        messages,
                        request.tools(),
                        request.stream(),
                        request.requiredToolName(),
                        effortLevel
                );
                Consumer<PromptEvent> promptEventConsumer = buildToolModelProgressConsumer(progressConsumer);
                PromptResult result =
                        request.stream() && providerPlugin.supportsStreaming()
                                ? providerPlugin.executePromptStream(promptRequest, promptEventConsumer)
                                : providerPlugin.executePrompt(promptRequest);
                return new ToolModelResponse(result.text(), result.content());
            }

            @Override
            public ProviderId providerId() {
                return context.providerId();
            }

            @Override
            public String currentModelId() {
                return context.modelId();
            }

            @Override
            public com.openclaude.provider.spi.AuthMethod authMethod() {
                return context.authMethod();
            }
        };
    }

    private static Consumer<PromptEvent> buildToolModelProgressConsumer(Consumer<ToolModelProgress> progressConsumer) {
        if (progressConsumer == null) {
            return null;
        }
        return event -> {
            if (event instanceof ToolCallEvent toolCallEvent) {
                String phase = toolCallEvent.phase() == null ? "" : toolCallEvent.phase();
                String text = toolCallEvent.command() == null || toolCallEvent.command().isBlank()
                        ? toolCallEvent.text()
                        : toolCallEvent.command();
                String type = phase;
                int resultCount = 0;
                if ("web_search".equals(toolCallEvent.toolName())) {
                    type = "completed".equals(phase) ? "search_results_received" : "query_update";
                    resultCount = extractLeadingResultCount(toolCallEvent.text());
                }
                progressConsumer.accept(new ToolModelProgress(toolCallEvent.toolId(), type, text, resultCount));
            }
        };
    }

    private static int extractLeadingResultCount(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(value);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String extractLastAssistantText(List<SessionMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index -= 1) {
            SessionMessage message = messages.get(index);
            if (message instanceof SessionMessage.AssistantMessage assistantMessage && !assistantMessage.text().isBlank()) {
                return assistantMessage.text();
            }
        }
        return "";
    }

    private static String currentCancellationReason(Supplier<String> cancellationReasonSupplier) {
        if (cancellationReasonSupplier == null) {
            return "Prompt cancelled.";
        }
        String reason = cancellationReasonSupplier.get();
        return reason == null || reason.isBlank() ? "Prompt cancelled." : reason;
    }

    private static void throwIfInterrupted(Supplier<String> cancellationReasonSupplier) {
        if (Thread.currentThread().isInterrupted()) {
            throw new QueryCancelledException(currentCancellationReason(cancellationReasonSupplier));
        }
    }

    private static boolean isCancellationException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InterruptedException || current instanceof CancellationException) {
                return true;
            }
            current = current.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }

    private static ToolExecutionResult awaitToolFuture(
            CompletableFuture<ToolExecutionResult> future,
            Supplier<String> cancellationReasonSupplier
    ) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new QueryCancelledException(currentCancellationReason(cancellationReasonSupplier));
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof CancellationException) {
                throw new QueryCancelledException(
                        cause.getMessage() == null || cause.getMessage().isBlank()
                                ? currentCancellationReason(cancellationReasonSupplier)
                                : cause.getMessage()
                );
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Tool execution failed.", cause);
        } catch (CancellationException exception) {
            throw new QueryCancelledException(currentCancellationReason(cancellationReasonSupplier));
        }
    }

    private static void handlePromptEvent(
            PromptEvent event,
            StringBuilder assistantText,
            StringBuilder reasoningText,
            Map<String, ToolSnapshot> toolSnapshots,
            Consumer<PromptEvent> eventConsumer
    ) {
        if (event instanceof TextDeltaEvent textDeltaEvent && !textDeltaEvent.text().isEmpty()) {
            assistantText.append(textDeltaEvent.text());
        } else if (event instanceof ReasoningDeltaEvent reasoningDeltaEvent && !reasoningDeltaEvent.text().isEmpty()) {
            reasoningText.append(reasoningDeltaEvent.text());
        } else if (event instanceof ToolCallEvent toolCallEvent) {
            String toolId = toolCallEvent.toolId() == null || toolCallEvent.toolId().isBlank()
                    ? UUID.randomUUID().toString()
                    : toolCallEvent.toolId();
            ToolSnapshot previous = toolSnapshots.get(toolId);
            String mergedText;
            if ("delta".equals(toolCallEvent.phase()) || "progress".equals(toolCallEvent.phase())) {
                mergedText = (previous == null ? "" : previous.text()) + (toolCallEvent.text() == null ? "" : toolCallEvent.text());
            } else {
                mergedText = toolCallEvent.text() == null || toolCallEvent.text().isBlank()
                        ? previous == null ? "" : previous.text()
                        : toolCallEvent.text();
            }
            toolSnapshots.put(toolId, new ToolSnapshot(
                    toolId,
                    toolCallEvent.toolName() == null || toolCallEvent.toolName().isBlank()
                            ? previous == null ? "tool" : previous.toolName()
                            : toolCallEvent.toolName(),
                    toolCallEvent.phase() == null || toolCallEvent.phase().isBlank() ? "status" : toolCallEvent.phase(),
                    mergedText
            ));
        }

        if (eventConsumer != null) {
            eventConsumer.accept(event);
        }
    }

    private static void handleBufferedPromptEvent(
            PromptEvent event,
            StringBuilder assistantText,
            StringBuilder reasoningText,
            List<PromptEvent> bufferedEvents
    ) {
        if (event instanceof TextDeltaEvent textDeltaEvent && !textDeltaEvent.text().isEmpty()) {
            assistantText.append(textDeltaEvent.text());
        } else if (event instanceof ReasoningDeltaEvent reasoningDeltaEvent && !reasoningDeltaEvent.text().isEmpty()) {
            reasoningText.append(reasoningDeltaEvent.text());
        }
        bufferedEvents.add(event);
    }

    private static void handleStreamingPromptEvent(
            PromptEvent event,
            StringBuilder assistantText,
            StringBuilder reasoningText,
            Map<String, ToolSnapshot> toolSnapshots,
            Consumer<PromptEvent> eventConsumer,
            List<PromptEvent> bufferedEvents,
            boolean bufferNonToolEvents,
            StreamingReadOnlyToolExecutor streamingReadOnlyToolExecutor,
            Set<String> runtimeManagedToolNames
    ) {
        if (event instanceof ToolUseDiscoveredEvent toolUseDiscoveredEvent) {
            if (
                    streamingReadOnlyToolExecutor != null
                    && runtimeManagedToolNames.contains(toolUseDiscoveredEvent.toolName())
            ) {
                streamingReadOnlyToolExecutor.addTool(toolUseDiscoveredEvent.toContentBlock());
                return;
            }
        }

        if (event instanceof ToolCallEvent toolCallEvent && runtimeManagedToolNames.contains(toolCallEvent.toolName())) {
            return;
        }

        if (bufferNonToolEvents) {
            handleBufferedPromptEvent(event, assistantText, reasoningText, bufferedEvents);
            if (streamingReadOnlyToolExecutor != null) {
                streamingReadOnlyToolExecutor.emitReadyYields();
            }
            return;
        }

        handlePromptEvent(event, assistantText, reasoningText, toolSnapshots, eventConsumer);
        if (streamingReadOnlyToolExecutor != null) {
            streamingReadOnlyToolExecutor.emitReadyYields();
        }
    }

    private static void emitToolExecutionUpdate(
            ToolExecutionUpdate update,
            Consumer<PromptEvent> eventConsumer
    ) {
        if (eventConsumer == null) {
            return;
        }

        if ("permission_requested".equals(update.phase())) {
            eventConsumer.accept(new ToolPermissionEvent(
                    update.permissionRequestId(),
                    update.toolUseId(),
                    update.toolName(),
                    update.inputJson(),
                    update.command(),
                    update.text(),
                    update.interactionType(),
                    update.interactionJson()
            ));
            return;
        }

        eventConsumer.accept(new ToolCallEvent(
                update.toolUseId(),
                update.toolName(),
                update.phase(),
                update.text(),
                update.command()
        ));
    }

    private static void emitToolYielded(
            ToolExecutionResult toolResult,
            String inputJson,
            Consumer<PromptEvent> eventConsumer
    ) {
        if (eventConsumer == null || toolResult == null) {
            return;
        }
        eventConsumer.accept(new ToolCallEvent(
                toolResult.toolUseId(),
                toolResult.toolName(),
                "yielded",
                toolResult.text(),
                extractCommand(inputJson)
        ));
    }

    private static void flushBufferedEvents(List<PromptEvent> bufferedEvents, Consumer<PromptEvent> eventConsumer) {
        if (eventConsumer == null) {
            return;
        }
        for (PromptEvent event : bufferedEvents) {
            eventConsumer.accept(event);
        }
    }

    private final class StreamingReadOnlyToolExecutor {
        private final ProviderPlugin providerPlugin;
        private final PromptExecutionContext context;
        private final String effortLevel;
        private final ToolPermissionGateway permissionGateway;
        private final Consumer<PromptEvent> eventConsumer;
        private final ConversationSession baseSession;
        private final Map<String, TrackedReadOnlyTool> trackedTools = new LinkedHashMap<>();
        private TrackedToolBatch currentBatch;
        private volatile boolean discarded;

        private StreamingReadOnlyToolExecutor(
                ProviderPlugin providerPlugin,
                PromptExecutionContext context,
                String effortLevel,
                ToolPermissionGateway permissionGateway,
                Consumer<PromptEvent> eventConsumer,
                ConversationSession baseSession
        ) {
            this.providerPlugin = providerPlugin;
            this.context = context;
            this.effortLevel = effortLevel;
            this.permissionGateway = permissionGateway;
            this.eventConsumer = eventConsumer;
            this.baseSession = baseSession;
        }

        void addTool(ToolUseContentBlock toolUse) {
            if (toolUse == null || trackedTools.containsKey(toolUse.toolUseId())) {
                return;
            }

            List<BufferedToolEvent> bufferedEvents = Collections.synchronizedList(new ArrayList<>());
            boolean concurrencySafe = isConcurrencySafe(toolUse);
            TrackedReadOnlyTool trackedTool = new TrackedReadOnlyTool(
                    toolUse,
                    bufferedEvents,
                    concurrencySafe
            );
            ToolExecutionRequest request = new ToolExecutionRequest(
                    toolUse.toolUseId(),
                    toolUse.toolName(),
                    toolUse.inputJson(),
                    discoveredSession(toolUse),
                    createToolModelInvoker(providerPlugin, context, effortLevel)
            );
            CompletableFuture<Void> batchStartGate;
            if (currentBatch != null && currentBatch.concurrencySafe() == concurrencySafe) {
                batchStartGate = currentBatch.startGate();
            } else {
                batchStartGate = currentBatch == null
                        ? CompletableFuture.completedFuture(null)
                        : currentBatch.completionBarrier();
            }
            CompletableFuture<ToolExecutionResult> future = scheduleTrackedTool(
                    trackedTool,
                    request,
                    bufferedEvents,
                    concurrencySafe,
                    batchStartGate
            );
            trackedTool.attachFuture(future);
            future.whenComplete((ignored, ignoredException) -> emitReadyYields());
            CompletableFuture<Void> completionBarrier = concurrencySafe
                    ? combineBarriers(currentBatch, concurrencySafe, future)
                    : future.handle((ignored, ignoredException) -> null);
            currentBatch = new TrackedToolBatch(concurrencySafe, batchStartGate, completionBarrier);
            trackedTools.put(toolUse.toolUseId(), trackedTool);
        }

        private CompletableFuture<ToolExecutionResult> scheduleTrackedTool(
                TrackedReadOnlyTool trackedTool,
                ToolExecutionRequest request,
                List<BufferedToolEvent> bufferedEvents,
                boolean concurrencySafe,
                CompletableFuture<Void> batchStartGate
        ) {
            if (concurrencySafe) {
                return batchStartGate.thenApplyAsync(ignored ->
                        executeTrackedTool(trackedTool, request, bufferedEvents)
                );
            }

            CompletableFuture<Void> dependency = currentBatch != null && !currentBatch.concurrencySafe()
                    ? currentBatch.completionBarrier()
                    : batchStartGate;
            return dependency.thenApplyAsync(ignored ->
                    executeTrackedTool(trackedTool, request, bufferedEvents)
            );
        }

        private ToolExecutionResult executeTrackedTool(
                TrackedReadOnlyTool trackedTool,
                ToolExecutionRequest request,
                List<BufferedToolEvent> bufferedEvents
        ) {
            if (discarded) {
                throw new CancellationException("Streaming fallback - tool execution discarded");
            }
            trackedTool.markExecuting();
            ToolExecutionResult result = executeToolWithHooks(
                    request,
                    permissionGateway,
                    update -> {
                        bufferedEvents.add(new BufferedUpdateEvent(update));
                        emitToolExecutionUpdate(update, eventConsumer);
                    },
                    message -> bufferedEvents.add(new BufferedSessionMessageEvent(message))
            );
            trackedTool.markCompleted(result);
            return result;
        }

        private CompletableFuture<Void> combineBarriers(
                TrackedToolBatch previousBatch,
                boolean concurrencySafe,
                CompletableFuture<ToolExecutionResult> future
        ) {
            CompletableFuture<Void> futureBarrier = future.handle((ignored, ignoredException) -> null);
            if (previousBatch == null || previousBatch.concurrencySafe() != concurrencySafe) {
                return futureBarrier;
            }
            return CompletableFuture.allOf(previousBatch.completionBarrier(), futureBarrier);
        }

        void discard() {
            discarded = true;
            cancelUnresolvedTools();
        }

        void emitReadyYields() {
            if (discarded || eventConsumer == null) {
                return;
            }
            for (TrackedReadOnlyTool trackedTool : trackedTools.values()) {
                if (trackedTool.status() == TrackedToolStatus.YIELDED) {
                    continue;
                }
                if (trackedTool.status() == TrackedToolStatus.COMPLETED && trackedTool.result() != null) {
                    emitToolYielded(trackedTool.result(), trackedTool.toolUse().inputJson(), eventConsumer);
                    trackedTool.markYielded();
                    continue;
                }
                if (
                        (trackedTool.status() == TrackedToolStatus.EXECUTING
                                || trackedTool.status() == TrackedToolStatus.QUEUED)
                                && !trackedTool.concurrencySafe()
                ) {
                    break;
                }
            }
        }

        void discardUnconfirmedToolUses(List<ToolUseContentBlock> confirmedToolUses) {
            LinkedHashSet<String> confirmedToolUseIds = new LinkedHashSet<>();
            if (confirmedToolUses != null) {
                for (ToolUseContentBlock toolUse : confirmedToolUses) {
                    if (toolUse != null && toolUse.toolUseId() != null && !toolUse.toolUseId().isBlank()) {
                        confirmedToolUseIds.add(toolUse.toolUseId());
                    }
                }
            }

            java.util.Iterator<Map.Entry<String, TrackedReadOnlyTool>> iterator = trackedTools.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, TrackedReadOnlyTool> entry = iterator.next();
                if (confirmedToolUseIds.contains(entry.getKey())) {
                    continue;
                }
                CompletableFuture<ToolExecutionResult> future = entry.getValue().future();
                if (future != null) {
                    future.cancel(true);
                }
                iterator.remove();
            }
        }

        private ConversationSession discoveredSession(ToolUseContentBlock toolUse) {
            List<ToolUseContentBlock> discoveredToolUses = new ArrayList<>();
            for (TrackedReadOnlyTool trackedTool : trackedTools.values()) {
                discoveredToolUses.add(trackedTool.toolUse());
            }
            discoveredToolUses.add(toolUse);
            return baseSession.append(SessionMessage.assistant(
                    "",
                    context.providerId(),
                    context.modelId(),
                    discoveredToolUses
            ));
        }

        boolean hasTrackedTools() {
            return !trackedTools.isEmpty();
        }

        List<ToolUseContentBlock> trackedToolUses() {
            return trackedTools.values().stream()
                    .map(TrackedReadOnlyTool::toolUse)
                    .toList();
        }

        private boolean isConcurrencySafe(ToolUseContentBlock toolUse) {
            return toolUse != null && toolRuntime.isConcurrencySafe(toolUse.toolName(), toolUse.inputJson());
        }

        Result awaitAndApply(
                ConversationSession currentSession,
                List<ToolUseContentBlock> orderedToolUses,
                Supplier<String> cancellationReasonSupplier
        ) {
            if (discarded) {
                return new Result(currentSession, Set.of(), false);
            }
            ConversationSession nextSession = currentSession;
            LinkedHashSet<String> executedToolUseIds = new LinkedHashSet<>();
            String cancellationReason = currentCancellationReason(cancellationReasonSupplier);

            for (ToolUseContentBlock toolUse : orderedToolUses) {
                if (Thread.currentThread().isInterrupted()) {
                    cancelUnresolvedTools();
                    nextSession = appendSyntheticCancelledToolResults(
                            nextSession,
                            orderedToolUses.stream()
                                    .filter(candidate -> !executedToolUseIds.contains(candidate.toolUseId()))
                                    .toList(),
                            eventConsumer,
                            cancellationReason
                    );
                    sessionStore.save(nextSession);
                    return new Result(nextSession, Set.copyOf(executedToolUseIds), true);
                }
                TrackedReadOnlyTool trackedTool = trackedTools.get(toolUse.toolUseId());
                if (trackedTool == null) {
                    continue;
                }

                ToolExecutionResult toolResult;
                try {
                    toolResult = awaitToolFuture(trackedTool.future(), cancellationReasonSupplier);
                } catch (QueryCancelledException exception) {
                    cancelUnresolvedTools();
                    for (ToolUseContentBlock remainingToolUse : orderedToolUses) {
                        if (executedToolUseIds.contains(remainingToolUse.toolUseId())) {
                            continue;
                        }
                        TrackedReadOnlyTool remainingTrackedTool = trackedTools.get(remainingToolUse.toolUseId());
                        if (remainingTrackedTool == null) {
                            nextSession = appendSyntheticCancelledToolResults(
                                    nextSession,
                                    List.of(remainingToolUse),
                                    eventConsumer,
                                    cancellationReason
                            );
                            continue;
                        }
                        nextSession = appendBufferedEvents(nextSession, remainingTrackedTool);
                        ToolExecutionResult completedResult = tryCompletedToolResult(remainingTrackedTool.future());
                        if (completedResult != null) {
                            nextSession = nextSession.append(SessionMessage.toolResult(
                                    completedResult.toolUseId(),
                                    completedResult.toolName(),
                                    completedResult.text(),
                                    completedResult.error(),
                                    completedResult.displayText()
                            ));
                            nextSession = completedResult.sessionEffect().apply(nextSession);
                            if (remainingTrackedTool.status() != TrackedToolStatus.YIELDED) {
                                emitToolYielded(completedResult, remainingToolUse.inputJson(), eventConsumer);
                                remainingTrackedTool.markYielded();
                            }
                        } else {
                            nextSession = appendSyntheticCancelledToolResults(
                                    nextSession,
                                    List.of(remainingToolUse),
                                    eventConsumer,
                                    cancellationReason
                            );
                        }
                    }
                    sessionStore.save(nextSession);
                    return new Result(nextSession, Set.copyOf(executedToolUseIds), true);
                }
                nextSession = appendBufferedEvents(nextSession, trackedTool);
                nextSession = nextSession.append(SessionMessage.toolResult(
                        toolResult.toolUseId(),
                        toolResult.toolName(),
                        toolResult.text(),
                        toolResult.error(),
                        toolResult.displayText()
                ));
                nextSession = toolResult.sessionEffect().apply(nextSession);
                sessionStore.save(nextSession);
                if (trackedTool.status() != TrackedToolStatus.YIELDED) {
                    emitToolYielded(toolResult, toolUse.inputJson(), eventConsumer);
                    trackedTool.markYielded();
                }
                executedToolUseIds.add(toolUse.toolUseId());
            }

            return new Result(nextSession, Set.copyOf(executedToolUseIds), false);
        }

        private void cancelUnresolvedTools() {
            for (TrackedReadOnlyTool trackedTool : trackedTools.values()) {
                CompletableFuture<ToolExecutionResult> future = trackedTool.future();
                if (future != null && !future.isDone()) {
                    future.cancel(true);
                }
            }
        }

        private ConversationSession appendBufferedEvents(
                ConversationSession session,
                TrackedReadOnlyTool trackedTool
        ) {
            ConversationSession nextSession = session;
            synchronized (trackedTool.bufferedEvents()) {
                for (BufferedToolEvent bufferedEvent : trackedTool.bufferedEvents()) {
                    if (bufferedEvent instanceof BufferedUpdateEvent bufferedUpdateEvent) {
                        ToolExecutionUpdate update = bufferedUpdateEvent.update();
                        nextSession = nextSession.append(SessionMessage.tool(
                                update.toolUseId(),
                                update.toolName(),
                                update.phase(),
                                update.text(),
                                update.inputJson(),
                                update.command(),
                                update.permissionRequestId(),
                                update.interactionType(),
                                update.interactionJson(),
                                update.error()
                        ));
                        continue;
                    }
                    if (bufferedEvent instanceof BufferedSessionMessageEvent bufferedSessionMessageEvent) {
                        nextSession = nextSession.append(bufferedSessionMessageEvent.message());
                    }
                }
            }
            return nextSession;
        }

        private ToolExecutionResult tryCompletedToolResult(CompletableFuture<ToolExecutionResult> future) {
            if (future == null || !future.isDone() || future.isCancelled() || future.isCompletedExceptionally()) {
                return null;
            }
            try {
                return future.getNow(null);
            } catch (RuntimeException exception) {
                return null;
            }
        }

        private sealed interface BufferedToolEvent permits BufferedUpdateEvent, BufferedSessionMessageEvent {
        }

        private record BufferedUpdateEvent(
                ToolExecutionUpdate update
        ) implements BufferedToolEvent {
        }

        private record BufferedSessionMessageEvent(
                SessionMessage message
        ) implements BufferedToolEvent {
        }

        private final class TrackedReadOnlyTool {
            private final ToolUseContentBlock toolUse;
            private final List<BufferedToolEvent> bufferedEvents;
            private final boolean concurrencySafe;
            private volatile CompletableFuture<ToolExecutionResult> future;
            private volatile TrackedToolStatus status = TrackedToolStatus.QUEUED;
            private volatile ToolExecutionResult result;

            private TrackedReadOnlyTool(
                    ToolUseContentBlock toolUse,
                    List<BufferedToolEvent> bufferedEvents,
                    boolean concurrencySafe
            ) {
                this.toolUse = toolUse;
                this.bufferedEvents = bufferedEvents;
                this.concurrencySafe = concurrencySafe;
            }

            private ToolUseContentBlock toolUse() {
                return toolUse;
            }

            private List<BufferedToolEvent> bufferedEvents() {
                return bufferedEvents;
            }

            private boolean concurrencySafe() {
                return concurrencySafe;
            }

            private CompletableFuture<ToolExecutionResult> future() {
                return future;
            }

            private void attachFuture(CompletableFuture<ToolExecutionResult> future) {
                this.future = future;
            }

            private TrackedToolStatus status() {
                return status;
            }

            private ToolExecutionResult result() {
                return result;
            }

            private void markExecuting() {
                this.status = TrackedToolStatus.EXECUTING;
            }

            private void markCompleted(ToolExecutionResult result) {
                this.result = result;
                this.status = TrackedToolStatus.COMPLETED;
            }

            private void markYielded() {
                this.status = TrackedToolStatus.YIELDED;
            }
        }

        private record TrackedToolBatch(
                boolean concurrencySafe,
                CompletableFuture<Void> startGate,
                CompletableFuture<Void> completionBarrier
        ) {
        }

        private record Result(
                ConversationSession session,
                Set<String> executedToolUseIds,
                boolean cancelled
        ) {
        }

        private enum TrackedToolStatus {
            QUEUED,
            EXECUTING,
            COMPLETED,
            YIELDED
        }
    }

    private static List<PromptMessage> buildPromptMessages(
            ConversationSession session,
            List<String> additionalSystemPrompts,
            List<ProviderToolDefinition> availableTools,
            String instructionsPrompt
    ) {
        List<SessionMessage> messages = SessionCompaction.messagesAfterCompactBoundary(session.messages());
        List<PromptMessage> normalized = new ArrayList<>();
        normalized.add(new PromptMessage(PromptMessageRole.SYSTEM, DEFAULT_SYSTEM_PROMPT));
        if (instructionsPrompt != null && !instructionsPrompt.isBlank()) {
            normalized.add(new PromptMessage(PromptMessageRole.SYSTEM, instructionsPrompt));
        }
        for (String additionalSystemPrompt : additionalSystemPrompts) {
            if (additionalSystemPrompt != null && !additionalSystemPrompt.isBlank()) {
                normalized.add(new PromptMessage(PromptMessageRole.SYSTEM, additionalSystemPrompt));
            }
        }
        Set<String> allowedToolNames = availableTools.stream()
                .map(ProviderToolDefinition::name)
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        PendingToolTrajectory pendingTrajectory = null;

        for (SessionMessage message : messages) {
            if (message instanceof SessionMessage.AssistantMessage assistantMessage) {
                List<ToolUseContentBlock> retainedToolUses = assistantMessage.toolUses().stream()
                        .filter(toolUse -> toolUse.toolUseId() != null && !toolUse.toolUseId().isBlank())
                        .filter(toolUse -> toolUse.toolName() != null && !toolUse.toolName().isBlank())
                        .filter(toolUse -> allowedToolNames.contains(toolUse.toolName()))
                        .toList();
                String assistantText = assistantMessage.text();
                if (pendingTrajectory != null) {
                    normalized.addAll(pendingTrajectory.messages());
                    pendingTrajectory = null;
                }

                if (retainedToolUses.isEmpty()) {
                    if (!assistantText.isBlank()) {
                        normalized.add(new PromptMessage(
                                PromptMessageRole.ASSISTANT,
                                List.of(new com.openclaude.provider.spi.TextContentBlock(assistantText))
                        ));
                    }
                    continue;
                }

                List<PromptContentBlock> content = new ArrayList<>();
                if (!assistantText.isBlank()) {
                    content.add(new com.openclaude.provider.spi.TextContentBlock(assistantText));
                }
                content.addAll(retainedToolUses);
                pendingTrajectory = new PendingToolTrajectory(
                        new PromptMessage(PromptMessageRole.ASSISTANT, content),
                        retainedToolUses
                );
                continue;
            }

            if (message instanceof SessionMessage.ToolResultMessage toolResultMessage) {
                if (pendingTrajectory == null) {
                    continue;
                }
                pendingTrajectory.accept(toolResultMessage);
                continue;
            }

            if (message instanceof SessionMessage.AttachmentMessage attachmentMessage) {
                if (pendingTrajectory != null && pendingTrajectory.accept(attachmentMessage)) {
                    continue;
                }
                if (pendingTrajectory != null) {
                    normalized.addAll(pendingTrajectory.messages());
                    pendingTrajectory = null;
                }
                normalized.addAll(message.toPromptMessages());
                continue;
            }

            if (message instanceof SessionMessage.UserMessage
                    || message instanceof SessionMessage.SystemMessage) {
                if (pendingTrajectory != null) {
                    normalized.addAll(pendingTrajectory.messages());
                    pendingTrajectory = null;
                }
                normalized.addAll(message.toPromptMessages());
            }
        }

        if (pendingTrajectory != null) {
            normalized.addAll(pendingTrajectory.messages());
        }

        return List.copyOf(normalized);
    }

    private static boolean shouldRetryWithToolNudge(List<SessionMessage> messages, String assistantResponseText) {
        if (assistantResponseText == null || assistantResponseText.isBlank()) {
            return false;
        }
        if (!looksLikeToolRefusal(assistantResponseText)) {
            return false;
        }

        String latestUserPrompt = latestUserPrompt(messages);
        return looksLikeLocalEnvironmentQuestion(latestUserPrompt);
    }

    private static boolean looksLikeToolRefusal(String assistantResponseText) {
        String normalized = assistantResponseText
                .replace('’', '\'')
                .toLowerCase();
        return normalized.contains("can't access")
                || normalized.contains("cannot access")
                || normalized.contains("can't read")
                || normalized.contains("cannot read")
                || normalized.contains("can't inspect")
                || normalized.contains("don't have access")
                || normalized.contains("do not have access")
                || normalized.contains("can't directly")
                || normalized.contains("cannot directly")
                || normalized.contains("please run this")
                || normalized.contains("paste the output")
                || normalized.contains("share the output");
    }

    private static boolean looksLikeLocalEnvironmentQuestion(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String normalized = prompt
                .replace('’', '\'')
                .toLowerCase();
        return normalized.contains("~/")
                || normalized.contains("desktop")
                || normalized.contains("folder")
                || normalized.contains("folders")
                || normalized.contains("directory")
                || normalized.contains("directories")
                || normalized.contains("repo")
                || normalized.contains("repository")
                || normalized.contains("workspace")
                || normalized.contains("current dir")
                || normalized.contains("current directory")
                || normalized.contains("file")
                || normalized.contains("files")
                || normalized.contains("project names")
                || normalized.contains("ls ")
                || normalized.contains("pwd");
    }

    private static boolean shouldForceLocalTool(String prompt, ProviderPlugin providerPlugin) {
        return providerPlugin.supportsTools() && looksLikeLocalEnvironmentQuestion(prompt);
    }

    private static boolean hasToolResults(ConversationSession session) {
        return session.messages().stream().anyMatch(SessionMessage.ToolResultMessage.class::isInstance);
    }

    private ConversationSession reactiveCompactSession(
            ProviderPlugin providerPlugin,
            ConversationSession session,
            PromptExecutionContext context,
            com.openclaude.provider.spi.ProviderId providerId,
            String modelId,
            String effortLevel,
            String instructionsPrompt
    ) {
        sessionMemoryService.waitForPendingExtraction(session.sessionId());
        ConversationSession latestSession = sessionStore.loadOrCreate(session.sessionId());
        SessionCompaction.SegmentSlice slice = SessionCompaction.sliceForCompaction(latestSession.messages());
        if (slice.segment().size() < 2) {
            throw new IllegalStateException("Prompt is too long and reactive compact could not reduce context further.");
        }

        List<SessionMessage> messagesToKeep = List.of();
        boolean usePreservedSegment = false;
        String formattedSummary;

        java.util.Optional<SessionMemoryService.CompactionCandidate> sessionMemoryCandidate =
                sessionMemoryService.tryBuildCompactionCandidate(latestSession);
        if (sessionMemoryCandidate.isPresent()) {
            SessionMemoryService.CompactionCandidate candidate = sessionMemoryCandidate.get();
            formattedSummary = candidate.summaryText();
            messagesToKeep = candidate.messagesToKeep();
            usePreservedSegment = !messagesToKeep.isEmpty();
        } else {
            List<SessionMessage> historyToSummarize = slice.segment();
            if (historyToSummarize.getLast() instanceof SessionMessage.UserMessage) {
                messagesToKeep = List.of(historyToSummarize.getLast());
                historyToSummarize = historyToSummarize.subList(0, historyToSummarize.size() - 1);
                usePreservedSegment = !messagesToKeep.isEmpty();
            }
            if (historyToSummarize.isEmpty()) {
                throw new IllegalStateException("Prompt is too long and reactive compact could not preserve the latest turn.");
            }
            formattedSummary = getCompactUserSummaryMessage(executeReactiveCompactSummary(
                    providerPlugin,
                    context,
                    effortLevel,
                    historyToSummarize,
                    instructionsPrompt
            ));
        }

        SessionMessage.UserMessage compactSummaryMessage = SessionMessage.compactSummary(formattedSummary);
        SessionMessage.CompactBoundaryMessage boundary = SessionMessage.compactBoundary(
                "auto",
                estimateTokens(slice.segment()),
                slice.segment().size()
        );
        if (usePreservedSegment) {
            boundary = SessionCompaction.annotateBoundaryWithPreservedSegment(boundary, compactSummaryMessage.id(), messagesToKeep);
        }

        List<SessionMessage> compactedMessages = new ArrayList<>(latestSession.messages());
        compactedMessages.addAll(SessionCompaction.buildPostCompactMessages(
                boundary,
                List.of(compactSummaryMessage),
                messagesToKeep,
                createPostCompactAttachments(latestSession),
                List.of()
        ));
        ConversationSession compactedSession = latestSession
                .withMessages(compactedMessages)
                .withReadFileState(Map.of())
                .withSessionMemoryState(latestSession.sessionMemoryState().afterCompaction(compactSummaryMessage.id()));
        sessionStore.save(compactedSession);
        return compactedSession;
    }

    private String executeReactiveCompactSummary(
            ProviderPlugin providerPlugin,
            PromptExecutionContext context,
            String effortLevel,
            List<SessionMessage> historyToSummarize,
            String instructionsPrompt
    ) {
        List<PromptMessage> promptMessages = new ArrayList<>();
        promptMessages.add(new PromptMessage(PromptMessageRole.SYSTEM, NO_TOOLS_COMPACT_PREAMBLE));
        if (instructionsPrompt != null && !instructionsPrompt.isBlank()) {
            promptMessages.add(new PromptMessage(PromptMessageRole.SYSTEM, instructionsPrompt));
        }
        promptMessages.addAll(toCompactPromptMessages(historyToSummarize));
        promptMessages.add(new PromptMessage(PromptMessageRole.USER, REACTIVE_COMPACT_PROMPT + System.lineSeparator() + NO_TOOLS_COMPACT_TRAILER));
        try {
            String text = providerPlugin.executePrompt(new PromptRequest(
                    context,
                    promptMessages,
                    List.of(),
                    false,
                    null,
                    effortLevel
            )).text();
            recordProviderSuccess(context.providerId());
            return text;
        } catch (RuntimeException exception) {
            recordProviderFailure(context.providerId(), exception);
            throw exception;
        }
    }

    private void recordProviderSuccess(ProviderId providerId) {
        stateStore.updateConnectionDiagnostics(providerId, diagnostics -> diagnostics.recordSuccess(java.time.Instant.now()));
    }

    private void recordProviderFailure(ProviderId providerId, RuntimeException exception) {
        ProviderFailureClassifier.ProviderFailure failure = ProviderFailureClassifier.classify(exception, providerId);
        stateStore.updateConnectionDiagnostics(providerId, diagnostics -> diagnostics.recordFailure(
                java.time.Instant.now(),
                failure.category(),
                failure.message(),
                failure.limitState()
        ));
    }

    private static List<PromptMessage> toCompactPromptMessages(List<SessionMessage> messages) {
        List<PromptMessage> promptMessages = new ArrayList<>();
        for (SessionMessage message : messages) {
            if (message instanceof SessionMessage.ThinkingMessage
                    || message instanceof SessionMessage.ToolInvocationMessage
                    || message instanceof SessionMessage.ProgressMessage
                    || message instanceof SessionMessage.TombstoneMessage
                    || message instanceof SessionMessage.CompactBoundaryMessage) {
                continue;
            }
            if (message instanceof SessionMessage.AssistantMessage assistantMessage
                    && assistantMessage.text().isBlank()
                    && assistantMessage.toolUses().isEmpty()) {
                continue;
            }
            promptMessages.addAll(message.toPromptMessages());
        }
        return List.copyOf(promptMessages);
    }

    private static int estimateTokens(List<? extends SessionMessage> messages) {
        int characters = 0;
        for (SessionMessage message : messages) {
            if (message == null || message.text() == null) {
                continue;
            }
            characters += message.text().length();
        }
        return Math.max(1, characters / 4);
    }

    private static boolean looksLikePromptTooLong(RuntimeException exception) {
        if (exception == null || exception.getMessage() == null) {
            return false;
        }
        String normalized = exception.getMessage().toLowerCase();
        return normalized.contains("prompt is too long")
                || normalized.contains("context length")
                || normalized.contains("maximum context")
                || normalized.contains("too many input tokens")
                || normalized.contains("413");
    }

    private static String getCompactUserSummaryMessage(String summary) {
        String formattedSummary = formatCompactSummary(summary);
        return """
                This session is being continued from a previous conversation that ran out of context. The summary below covers the earlier portion of the conversation.

                %s
                """.formatted(formattedSummary).trim();
    }

    static String formatCompactSummary(String summary) {
        String normalized = summary == null ? "" : summary.trim();
        normalized = ANALYSIS_BLOCK_PATTERN.matcher(normalized).replaceAll("").trim();
        java.util.regex.Matcher summaryMatcher = SUMMARY_BLOCK_PATTERN.matcher(normalized);
        if (summaryMatcher.find()) {
            String summaryContent = summaryMatcher.group(1) == null ? "" : summaryMatcher.group(1).trim();
            normalized = summaryMatcher.replaceFirst("Summary:\n" + java.util.regex.Matcher.quoteReplacement(summaryContent));
        }
        return normalized.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static List<SessionMessage> createPostCompactAttachments(ConversationSession session) {
        List<SessionMessage> attachments = new ArrayList<>();
        attachments.addAll(createPostCompactFileAttachments(session.readFileState()));
        if (session.planMode()) {
            attachments.add(SessionMessage.attachment(new SessionAttachment.PlanModeAttachment()));
        }
        return List.copyOf(attachments);
    }

    private static List<SessionMessage> createPostCompactFileAttachments(Map<String, FileReadState> readFileState) {
        if (readFileState == null || readFileState.isEmpty()) {
            return List.of();
        }

        List<Map.Entry<String, FileReadState>> recentFiles = readFileState.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null)
                .sorted(Comparator.comparingLong((Map.Entry<String, FileReadState> entry) -> entry.getValue().timestamp()).reversed())
                .limit(POST_COMPACT_MAX_FILES_TO_RESTORE)
                .toList();

        List<SessionMessage> attachments = new ArrayList<>();
        int usedTokens = 0;
        for (Map.Entry<String, FileReadState> entry : recentFiles) {
            SessionAttachment attachment = buildFileAttachment(entry.getKey(), entry.getValue());
            int attachmentTokens = estimateTokens(List.of(SessionMessage.attachment(attachment)));
            if (usedTokens + attachmentTokens > POST_COMPACT_TOKEN_BUDGET) {
                attachments.add(SessionMessage.attachment(new SessionAttachment.CompactFileReferenceAttachment(entry.getKey())));
                continue;
            }
            attachments.add(SessionMessage.attachment(attachment));
            usedTokens += attachmentTokens;
        }
        return List.copyOf(attachments);
    }

    private static SessionAttachment buildFileAttachment(String filePath, FileReadState readState) {
        Path absolutePath = Path.of(filePath).toAbsolutePath().normalize();
        String absolutePathString = absolutePath.toString();
        try {
            if (!Files.exists(absolutePath) || Files.isDirectory(absolutePath)) {
                return new SessionAttachment.CompactFileReferenceAttachment(absolutePathString);
            }

            String rawContent = Files.readString(absolutePath);
            List<String> lines = Files.readAllLines(absolutePath);
            int startLine = Math.max(1, readState.offset() == null ? 1 : readState.offset());
            int effectiveLimit = Math.max(1, readState.limit() == null ? lines.size() : readState.limit());
            int startIndex = Math.min(lines.size(), startLine - 1);
            int endIndex = Math.min(lines.size(), startIndex + effectiveLimit);

            StringBuilder builder = new StringBuilder();
            if (lines.isEmpty()) {
                builder.append("<system-reminder>Warning: the file exists but the contents are empty.</system-reminder>");
            } else if (startIndex >= lines.size()) {
                builder.append("<system-reminder>Warning: the file exists but is shorter than the previously read offset (")
                        .append(startLine)
                        .append(").</system-reminder>");
            } else {
                for (int lineIndex = startIndex; lineIndex < endIndex; lineIndex += 1) {
                    if (builder.length() > 0) {
                        builder.append(System.lineSeparator());
                    }
                    builder.append(lineIndex + 1).append('\t').append(lines.get(lineIndex));
                    if ((builder.length() / 4) >= POST_COMPACT_MAX_TOKENS_PER_FILE) {
                        break;
                    }
                }
            }

            boolean truncated = (rawContent.length() / 4) > POST_COMPACT_MAX_TOKENS_PER_FILE || endIndex < lines.size();
            if (builder.length() == 0) {
                return new SessionAttachment.CompactFileReferenceAttachment(absolutePathString);
            }
            return new SessionAttachment.RestoredFileAttachment(
                    absolutePathString,
                    builder.toString(),
                    readState.offset(),
                    readState.limit(),
                    truncated
            );
        } catch (IOException exception) {
            return new SessionAttachment.CompactFileReferenceAttachment(absolutePathString);
        }
    }

    private static String latestUserPrompt(List<SessionMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index -= 1) {
            SessionMessage message = messages.get(index);
            if (message instanceof SessionMessage.UserMessage userMessage) {
                return userMessage.text();
            }
        }
        return "";
    }

    private record ToolSnapshot(
            String toolId,
            String toolName,
            String phase,
            String text
    ) {
    }

    private static final class PendingToolTrajectory {
        private final PromptMessage assistantMessage;
        private final LinkedHashMap<String, ToolUseContentBlock> expectedToolUses = new LinkedHashMap<>();
        private final LinkedHashMap<String, ToolResultContentBlock> deliveredToolResults = new LinkedHashMap<>();
        private final ArrayList<PromptMessage> preToolAttachments = new ArrayList<>();
        private final ArrayList<PromptMessage> postToolAttachments = new ArrayList<>();

        private PendingToolTrajectory(PromptMessage assistantMessage, List<ToolUseContentBlock> toolUses) {
            this.assistantMessage = assistantMessage;
            for (ToolUseContentBlock toolUse : toolUses) {
                expectedToolUses.put(toolUse.toolUseId(), toolUse);
            }
        }

        private void accept(SessionMessage.ToolResultMessage toolResultMessage) {
            String toolUseId = toolResultMessage.toolUseId();
            if (toolUseId == null || toolUseId.isBlank()) {
                return;
            }
            ToolUseContentBlock toolUse = expectedToolUses.get(toolUseId);
            if (toolUse == null || deliveredToolResults.containsKey(toolUseId)) {
                return;
            }
            deliveredToolResults.put(toolUseId, new ToolResultContentBlock(
                    toolUseId,
                    toolUse.toolName(),
                    toolResultMessage.text(),
                    toolResultMessage.isError()
            ));
        }

        private boolean accept(SessionMessage.AttachmentMessage attachmentMessage) {
            if (!(attachmentMessage.attachment() instanceof SessionAttachment.HookAdditionalContextAttachment hookAttachment)) {
                return false;
            }

            String toolUseId = hookAttachment.toolUseId();
            if (toolUseId == null || toolUseId.isBlank() || !expectedToolUses.containsKey(toolUseId)) {
                return false;
            }

            List<PromptMessage> promptMessages = attachmentMessage.toPromptMessages();
            String hookEvent = hookAttachment.hookEvent();
            if ("PreToolUse".equals(hookEvent) || "PermissionRequest".equals(hookEvent)) {
                preToolAttachments.addAll(promptMessages);
            } else {
                postToolAttachments.addAll(promptMessages);
            }
            return true;
        }

        private List<PromptMessage> messages() {
            if (expectedToolUses.isEmpty()) {
                return List.of(assistantMessage);
            }

            List<PromptContentBlock> resultBlocks = new ArrayList<>();
            for (var entry : expectedToolUses.entrySet()) {
                ToolResultContentBlock toolResult = deliveredToolResults.get(entry.getKey());
                if (toolResult == null) {
                    ToolUseContentBlock toolUse = entry.getValue();
                    toolResult = new ToolResultContentBlock(
                            toolUse.toolUseId(),
                            toolUse.toolName(),
                            SYNTHETIC_TOOL_RESULT_PLACEHOLDER,
                            true
                    );
                }
                resultBlocks.add(toolResult);
            }

            ArrayList<PromptMessage> messages = new ArrayList<>();
            messages.add(assistantMessage);
            messages.addAll(preToolAttachments);
            messages.add(new PromptMessage(PromptMessageRole.USER, resultBlocks));
            messages.addAll(postToolAttachments);
            return List.copyOf(messages);
        }
    }
}
