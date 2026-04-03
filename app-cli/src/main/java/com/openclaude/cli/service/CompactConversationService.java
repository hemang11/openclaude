package com.openclaude.cli.service;

import com.openclaude.core.config.OpenClaudeEffort;
import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.instructions.AgentsInstructionsLoader;
import com.openclaude.core.provider.OpenClaudeState;
import com.openclaude.core.provider.ProviderConnectionState;
import com.openclaude.core.provider.ProviderRegistry;
import com.openclaude.core.query.QueryEngine;
import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.ConversationSessionStore;
import com.openclaude.core.session.FileReadState;
import com.openclaude.core.session.SessionAttachment;
import com.openclaude.core.session.SessionCompaction;
import com.openclaude.core.session.SessionMessage;
import com.openclaude.core.sessionmemory.SessionMemoryService;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.PromptEvent;
import com.openclaude.provider.spi.PromptExecutionContext;
import com.openclaude.provider.spi.PromptMessage;
import com.openclaude.provider.spi.PromptMessageRole;
import com.openclaude.provider.spi.PromptRequest;
import com.openclaude.provider.spi.PromptResult;
import com.openclaude.provider.spi.ProviderPlugin;
import com.openclaude.provider.spi.TextDeltaEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class CompactConversationService {
    private static final String NO_TOOLS_PREAMBLE = """
            CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.

            - Do NOT use Read, Bash, Grep, Glob, Edit, Write, or ANY other tool.
            - You already have all the context you need in the conversation above.
            - Tool calls will be rejected and will waste your only turn.
            - Your entire response must be plain text: an <analysis> block followed by a <summary> block.
            """.strip();
    private static final String BASE_COMPACT_PROMPT = """
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

            There may be additional compact instructions appended below. Follow them when creating the summary.
            """.strip();
    private static final String NO_TOOLS_TRAILER = """

            REMINDER: Do NOT call any tools. Respond with plain text only — an <analysis> block followed by a <summary> block.
            Tool calls will be rejected and you will fail the task.
            """.strip();
    private static final int POST_COMPACT_MAX_FILES_TO_RESTORE = 5;
    private static final int POST_COMPACT_TOKEN_BUDGET = 50_000;
    private static final int POST_COMPACT_MAX_TOKENS_PER_FILE = 5_000;
    private static final Pattern ANALYSIS_BLOCK_PATTERN = Pattern.compile("<analysis>[\\s\\S]*?</analysis>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUMMARY_BLOCK_PATTERN = Pattern.compile("<summary>([\\s\\S]*?)</summary>", Pattern.CASE_INSENSITIVE);

    private final OpenClaudeStateStore stateStore;
    private final ConversationSessionStore sessionStore;
    private final ProviderRegistry providerRegistry;
    private final AgentsInstructionsLoader instructionsLoader;
    private final CompactHooksExecutor compactHooksExecutor;
    private final PostCompactCleanup postCompactCleanup;
    private final SessionMemoryService sessionMemoryService;

    public CompactConversationService(
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore,
            ProviderRegistry providerRegistry
    ) {
        this(
                stateStore,
                sessionStore,
                providerRegistry,
                new AgentsInstructionsLoader(),
                new CompactHooksExecutor(),
                new PostCompactCleanup(),
                new SessionMemoryService(sessionStore)
        );
    }

    CompactConversationService(
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore,
            ProviderRegistry providerRegistry,
            AgentsInstructionsLoader instructionsLoader
    ) {
        this(
                stateStore,
                sessionStore,
                providerRegistry,
                instructionsLoader,
                new CompactHooksExecutor(),
                new PostCompactCleanup(),
                new SessionMemoryService(sessionStore)
        );
    }

    CompactConversationService(
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore,
            ProviderRegistry providerRegistry,
            AgentsInstructionsLoader instructionsLoader,
            CompactHooksExecutor compactHooksExecutor,
            PostCompactCleanup postCompactCleanup,
            SessionMemoryService sessionMemoryService
    ) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry");
        this.instructionsLoader = Objects.requireNonNull(instructionsLoader, "instructionsLoader");
        this.compactHooksExecutor = Objects.requireNonNull(compactHooksExecutor, "compactHooksExecutor");
        this.postCompactCleanup = Objects.requireNonNull(postCompactCleanup, "postCompactCleanup");
        this.sessionMemoryService = Objects.requireNonNull(sessionMemoryService, "sessionMemoryService");
    }

    public CompactResult compact(String rawInstructions) {
        OpenClaudeState state = stateStore.load();
        String activeSessionId = state.activeSessionId();
        if (activeSessionId == null || activeSessionId.isBlank()) {
            throw new IllegalStateException("No active session.");
        }

        ProviderPlugin providerPlugin = requireProvider(state);
        ProviderConnectionState connectionState = requireConnection(state);
        String modelId = resolveModelId(providerPlugin, state);
        String effortLevel = OpenClaudeEffort.resolveForPrompt(
                connectionState.providerId(),
                modelId,
                stateStore.currentEffortLevel(state.settings().effortLevel())
        );

        sessionMemoryService.waitForPendingExtraction(activeSessionId);
        ConversationSession session = sessionStore.loadOrCreate(activeSessionId);
        SessionCompaction.SegmentSlice slice = SessionCompaction.sliceForCompaction(session.messages());
        if (slice.segment().isEmpty()) {
            throw new IllegalStateException("Not enough messages to compact.");
        }
        long visibleMessageCount = slice.segment().stream()
                .filter(message -> message instanceof SessionMessage.UserMessage
                        || message instanceof SessionMessage.AssistantMessage
                        || message instanceof SessionMessage.ToolResultMessage)
                .count();
        if (visibleMessageCount < 2) {
            throw new IllegalStateException("Not enough messages to compact.");
        }

        PromptExecutionContext context = new PromptExecutionContext(
                connectionState.providerId(),
                connectionState.authMethod(),
                connectionState.credentialReference(),
                modelId
        );

        String instructions = rawInstructions == null ? "" : rawInstructions.trim();
        Path transcriptPath = sessionStore.sessionFilePath(session.sessionId());
        CompactHooksExecutor.PreCompactHookResult preCompactHookResult = compactHooksExecutor.executePreCompactHooks(
                session,
                transcriptPath,
                "manual",
                instructions.isBlank() ? null : instructions
        );
        instructions = mergeCompactInstructions(instructions, preCompactHookResult.newCustomInstructions());
        List<SessionMessage> messagesToKeep = List.of();
        boolean projectMessagesToKeepFromHistory = false;
        String formattedSummary;
        int preTokens;
        java.util.Optional<SessionMemoryService.CompactionCandidate> sessionMemoryCandidate =
                instructions.isBlank() ? sessionMemoryService.tryBuildCompactionCandidate(session) : java.util.Optional.empty();

        if (sessionMemoryCandidate.isPresent()) {
            SessionMemoryService.CompactionCandidate candidate = sessionMemoryCandidate.get();
            formattedSummary = candidate.summaryText();
            messagesToKeep = candidate.messagesToKeep();
            preTokens = candidate.preCompactTokenCount();
            projectMessagesToKeepFromHistory = session.sessionMemoryState().lastSummarizedMessageId() != null
                    && !session.sessionMemoryState().lastSummarizedMessageId().isBlank();
        } else {
            List<PromptMessage> promptMessages = new ArrayList<>();
            promptMessages.add(new PromptMessage(PromptMessageRole.SYSTEM, NO_TOOLS_PREAMBLE));
            String instructionsPrompt = instructionsLoader.renderSystemPrompt(session);
            if (instructionsPrompt != null && !instructionsPrompt.isBlank()) {
                promptMessages.add(new PromptMessage(PromptMessageRole.SYSTEM, instructionsPrompt));
            }
            promptMessages.addAll(toProviderMessages(slice.segment()));
            promptMessages.add(new PromptMessage(
                    PromptMessageRole.USER,
                    instructions.isBlank()
                            ? BASE_COMPACT_PROMPT + "\n\n" + NO_TOOLS_TRAILER
                            : BASE_COMPACT_PROMPT + "\n\n## Compact Instructions\n" + instructions + "\n\n" + NO_TOOLS_TRAILER
            ));

            StringBuilder streamedText = new StringBuilder();
            PromptRequest request = new PromptRequest(
                    context,
                    promptMessages,
                    List.of(),
                    providerPlugin.supportsStreaming(),
                    null,
                    effortLevel
            );

            PromptResult result;
            if (providerPlugin.supportsStreaming()) {
                result = providerPlugin.executePromptStream(request, event -> {
                    if (event instanceof TextDeltaEvent textDeltaEvent && !textDeltaEvent.text().isEmpty()) {
                        streamedText.append(textDeltaEvent.text());
                    }
                });
            } else {
                result = providerPlugin.executePrompt(new PromptRequest(
                        context,
                        promptMessages,
                        List.of(),
                        false,
                        null,
                        effortLevel
                ));
            }

            String rawSummary = streamedText.length() == 0 ? result.text() : streamedText.toString();
            formattedSummary = getCompactUserSummaryMessage(rawSummary);
            preTokens = estimateTokens(slice.segment());
        }
        List<SessionMessage> postCompactAttachments = createPostCompactAttachments(session);
        CompactHooksExecutor.SessionStartHookResult sessionStartHookResult = compactHooksExecutor.processSessionStartHooks(
                session,
                transcriptPath,
                modelId
        );
        CompactHooksExecutor.PostCompactHookResult postCompactHookResult = compactHooksExecutor.executePostCompactHooks(
                session,
                transcriptPath,
                "manual",
                formattedSummary
        );

        SessionMessage.UserMessage compactSummaryMessage = SessionMessage.compactSummary(formattedSummary);
        SessionMessage.CompactBoundaryMessage boundaryMessage = SessionMessage.compactBoundary("manual", preTokens, slice.segment().size());
        String preservedAnchorId = session.sessionMemoryState().lastSummarizedMessageId();
        if (!messagesToKeep.isEmpty() && projectMessagesToKeepFromHistory && preservedAnchorId != null && !preservedAnchorId.isBlank()) {
            boundaryMessage = SessionCompaction.annotateBoundaryWithPreservedSegment(
                    boundaryMessage,
                    preservedAnchorId,
                    messagesToKeep
            );
        }

        List<SessionMessage> compactedMessages = new ArrayList<>(session.messages());
        compactedMessages.addAll(SessionCompaction.buildPostCompactMessages(
                boundaryMessage,
                List.of(compactSummaryMessage),
                projectMessagesToKeepFromHistory ? List.of() : messagesToKeep,
                postCompactAttachments,
                sessionStartHookResult.hookMessages()
        ));

        ConversationSession compacted = postCompactCleanup.apply(
                session.withMessages(compactedMessages)
                        .withSessionMemoryState(session.sessionMemoryState().afterCompaction(compactSummaryMessage.id()))
        );
        int preProjectedTokens = estimateProjectedContextTokens(session);
        int postProjectedTokens = estimateProjectedContextTokens(compacted);
        if (postProjectedTokens >= preProjectedTokens) {
            return new CompactResult(
                    session,
                    "Skipped compaction because it would not reduce context (" + preProjectedTokens + " -> " + postProjectedTokens + " estimated tokens)."
            );
        }
        sessionStore.save(compacted);

        return new CompactResult(
                compacted,
                buildCompactResultMessage(
                        sessionMemoryCandidate.isPresent()
                                ? "Compacted conversation history using session memory."
                                : "Compacted conversation history into a summary.",
                        preCompactHookResult.userDisplayMessage(),
                        postCompactHookResult.userDisplayMessage()
                )
        );
    }

    private ProviderPlugin requireProvider(OpenClaudeState state) {
        if (state.activeProvider() == null) {
            throw new IllegalStateException("No active provider. Use /provider first.");
        }
        return providerRegistry.findExecutable(state.activeProvider())
                .orElseThrow(() -> new IllegalStateException(
                        "Active provider is not executable: " + state.activeProvider().cliValue()
                ));
    }

    private static ProviderConnectionState requireConnection(OpenClaudeState state) {
        ProviderConnectionState connectionState = state.get(state.activeProvider());
        if (connectionState == null) {
            throw new IllegalStateException("Active provider is not connected: " + state.activeProvider().cliValue());
        }
        return connectionState;
    }

    private static String resolveModelId(ProviderPlugin providerPlugin, OpenClaudeState state) {
        if (state.activeModelId() != null && !state.activeModelId().isBlank()) {
            return state.activeModelId();
        }
        List<ModelDescriptor> supportedModels = providerPlugin.supportedModels();
        if (supportedModels.isEmpty()) {
            throw new IllegalStateException("Provider has no registered models: " + providerPlugin.id().cliValue());
        }
        return supportedModels.getFirst().id();
    }

    private static List<PromptMessage> toProviderMessages(List<SessionMessage> messages) {
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

    private static int estimateTokens(List<SessionMessage> messages) {
        int characters = messages.stream()
                .map(SessionMessage::text)
                .filter(Objects::nonNull)
                .mapToInt(String::length)
                .sum();
        return Math.max(1, characters / 4);
    }

    private int estimateProjectedContextTokens(ConversationSession session) {
        String instructionsPrompt = instructionsLoader.renderSystemPrompt(session);
        return QueryEngine.estimatePromptTokensForDiagnostics(
                QueryEngine.projectPromptMessagesForDiagnostics(session, List.of(), instructionsPrompt)
        );
    }

    private static String mergeCompactInstructions(String baseInstructions, String hookInstructions) {
        String base = baseInstructions == null ? "" : baseInstructions.trim();
        String extra = hookInstructions == null ? "" : hookInstructions.trim();
        if (base.isBlank()) {
            return extra;
        }
        if (extra.isBlank()) {
            return base;
        }
        return base + System.lineSeparator() + System.lineSeparator() + extra;
    }

    private static String buildCompactResultMessage(String baseMessage, String... displayMessages) {
        List<String> details = java.util.Arrays.stream(displayMessages)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(detail -> !detail.isBlank())
                .toList();
        if (details.isEmpty()) {
            return baseMessage;
        }
        return baseMessage + System.lineSeparator() + System.lineSeparator() + String.join(System.lineSeparator(), details);
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
                .sorted((left, right) -> Long.compare(right.getValue().timestamp(), left.getValue().timestamp()))
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
        java.nio.file.Path absolutePath = java.nio.file.Path.of(filePath).toAbsolutePath().normalize();
        String absolutePathString = absolutePath.toString();
        try {
            if (!java.nio.file.Files.exists(absolutePath) || java.nio.file.Files.isDirectory(absolutePath)) {
                return new SessionAttachment.CompactFileReferenceAttachment(absolutePathString);
            }

            String rawContent = java.nio.file.Files.readString(absolutePath);
            List<String> lines = java.nio.file.Files.readAllLines(absolutePath);
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

    public record CompactResult(
            ConversationSession session,
            String message
    ) {}
}
