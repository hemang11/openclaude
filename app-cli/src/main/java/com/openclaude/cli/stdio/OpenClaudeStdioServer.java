package com.openclaude.cli.stdio;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openclaude.cli.service.CommandService;
import com.openclaude.cli.service.ModelService;
import com.openclaude.cli.service.PermissionRulesStore;
import com.openclaude.cli.service.ProviderService;
import com.openclaude.cli.service.SessionService;
import com.openclaude.core.config.OpenClaudeSettings;
import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.provider.OpenClaudeState;
import com.openclaude.core.provider.ProviderFailureClassifier;
import com.openclaude.core.provider.PromptRouter;
import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.SessionCompaction;
import com.openclaude.core.session.ConversationSessionStore;
import com.openclaude.core.session.SessionMessage;
import com.openclaude.core.session.TodoItem;
import com.openclaude.core.tools.ShellPermissionPolicy;
import com.openclaude.core.tools.ToolPermissionDecision;
import com.openclaude.core.tools.ToolPermissionGateway;
import com.openclaude.core.tools.ToolPermissionRequest;
import com.openclaude.core.tools.ToolPermissionRule;
import com.openclaude.provider.spi.PromptEvent;
import com.openclaude.provider.spi.PromptStatusEvent;
import com.openclaude.provider.spi.ReasoningDeltaEvent;
import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.TextDeltaEvent;
import com.openclaude.provider.spi.ToolCallEvent;
import com.openclaude.provider.spi.ToolPermissionEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OpenClaudeStdioServer {
    private final PromptRouter promptRouter;
    private final ProviderService providerService;
    private final ModelService modelService;
    private final CommandService commandService;
    private final SessionService sessionService;
    private final OpenClaudeStateStore stateStore;
    private final ConversationSessionStore sessionStore;
    private final PermissionRulesStore permissionRulesStore;
    private final ObjectMapper objectMapper;
    private final Object writerLock = new Object();
    private final ConcurrentHashMap<String, CompletableFuture<ToolPermissionDecision>> pendingPermissionDecisions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ToolPermissionRequest> pendingPermissionRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActivePromptRun> activePromptRuns = new ConcurrentHashMap<>();

    public OpenClaudeStdioServer(
            PromptRouter promptRouter,
            ProviderService providerService,
            ModelService modelService,
            CommandService commandService,
            SessionService sessionService,
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore
    ) {
        this.promptRouter = Objects.requireNonNull(promptRouter, "promptRouter");
        this.providerService = Objects.requireNonNull(providerService, "providerService");
        this.modelService = Objects.requireNonNull(modelService, "modelService");
        this.commandService = Objects.requireNonNull(commandService, "commandService");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.permissionRulesStore = new PermissionRulesStore(stateStore);
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public void run(InputStream inputStream, OutputStream outputStream, OutputStream errorStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);
             PrintWriter errorWriter = new PrintWriter(errorStream, true, StandardCharsets.UTF_8);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                RequestEnvelope request;
                try {
                    request = objectMapper.readValue(line, RequestEnvelope.class);
                } catch (Exception exception) {
                    errorWriter.println("Invalid stdio request: " + exception.getMessage());
                    write(writer, ResponseEnvelope.failure(
                            "unknown",
                            new ProtocolError("invalid_request", "Failed to parse request JSON.")
                    ));
                    continue;
                }

                if ("prompt.submit".equals(request.method())) {
                    submitPromptAsync(executor, request, writer);
                } else {
                    handleSafely(request, writer);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to run OpenClaude stdio server", exception);
        }
    }

    private void handleSafely(RequestEnvelope request, PrintWriter writer) {
        try {
            handle(request, writer);
        } catch (RuntimeException exception) {
            ProtocolError protocolError = protocolErrorFor(exception);
            write(writer, ResponseEnvelope.failure(
                    request.id(),
                    protocolError
            ));
        }
    }

    private void handle(RequestEnvelope request, PrintWriter writer) {
        requireRequest(request);
        switch (request.method()) {
            case "initialize", "state.snapshot" -> write(writer, ResponseEnvelope.success(request.id(), objectMapper.valueToTree(snapshot())));
            case "providers.list" -> write(writer, ResponseEnvelope.success(
                    request.id(),
                    objectMapper.valueToTree(new ProvidersResult(snapshot().providers()))
            ));
            case "provider.connect" -> handleProviderConnect(request, writer);
            case "provider.use" -> {
                ProviderSelection selection = requireParams(request, ProviderSelection.class);
                String message = providerService.useProvider(parseProviderId(selection.providerId()));
                write(writer, ResponseEnvelope.success(request.id(), objectMapper.valueToTree(new MutationResult(message, snapshot()))));
            }
            case "provider.disconnect" -> {
                ProviderSelection selection = requireParams(request, ProviderSelection.class);
                String message = providerService.disconnect(parseProviderId(selection.providerId()));
                write(writer, ResponseEnvelope.success(request.id(), objectMapper.valueToTree(new MutationResult(message, snapshot()))));
            }
            case "models.list" -> write(writer, ResponseEnvelope.success(
                    request.id(),
                    objectMapper.valueToTree(new ModelsResult(snapshot().models()))
            ));
            case "models.select" -> {
                ModelSelection selection = requireParams(request, ModelSelection.class);
                String message = modelService.selectModel(parseProviderId(selection.providerId()), selection.modelId());
                write(writer, ResponseEnvelope.success(request.id(), objectMapper.valueToTree(new MutationResult(message, snapshot()))));
            }
            case "sessions.list" -> write(writer, ResponseEnvelope.success(
                    request.id(),
                    objectMapper.valueToTree(new SessionsResult(
                            sessionService.listCurrentScopeSessions().stream().map(SessionListItemView::from).toList()
                    ))
            ));
            case "sessions.resume" -> {
                SessionSelectionParams params = requireParams(request, SessionSelectionParams.class);
                String message = sessionService.resumeSession(params.sessionId());
                write(writer, ResponseEnvelope.success(request.id(), objectMapper.valueToTree(new MutationResult(message, snapshot()))));
            }
            case "sessions.rewind" -> {
                SessionRewindParams params = requireParams(request, SessionRewindParams.class);
                String message = sessionService.rewindToMessage(params.messageId());
                write(writer, ResponseEnvelope.success(request.id(), objectMapper.valueToTree(new MutationResult(message, snapshot()))));
            }
            case "sessions.rename" -> {
                SessionRenameParams params = requireParams(request, SessionRenameParams.class);
                String message = sessionService.renameActiveSession(params.title());
                write(writer, ResponseEnvelope.success(request.id(), objectMapper.valueToTree(new MutationResult(message, snapshot()))));
            }
            case "sessions.plan_mode" -> {
                SessionPlanModeParams params = requireParams(request, SessionPlanModeParams.class);
                String message = sessionService.setPlanMode(params.enabled());
                write(writer, ResponseEnvelope.success(request.id(), objectMapper.valueToTree(new MutationResult(message, snapshot()))));
            }
            case "sessions.clear" -> {
                String message = sessionService.clearConversation();
                write(writer, ResponseEnvelope.success(request.id(), objectMapper.valueToTree(new MutationResult(message, snapshot()))));
            }
            case "commands.list" -> write(writer, ResponseEnvelope.success(
                    request.id(),
                    objectMapper.valueToTree(new CommandsResult(snapshot().commands()))
            ));
            case "command.execute", "command.run" -> {
                CommandRunParams params = requireParams(request, CommandRunParams.class);
                CommandService.CommandRunResult result = commandService.run(params.commandName(), params.args());
                write(writer, ResponseEnvelope.success(
                        request.id(),
                        objectMapper.valueToTree(new CommandResult(
                                result.message(),
                                result.panel() == null ? null : PanelView.from(result.panel()),
                                snapshot()
                        ))
                ));
            }
            case "permissions.editor.snapshot" -> write(writer, ResponseEnvelope.success(
                    request.id(),
                    objectMapper.valueToTree(commandService.permissionEditorSnapshot())
            ));
            case "permissions.editor.mutate" -> {
                PermissionEditorMutationParams params = requireParams(request, PermissionEditorMutationParams.class);
                CommandService.PermissionEditorMutationResult result = commandService.permissionEditorMutate(new CommandService.PermissionEditorMutationRequest(
                        params.action(),
                        params.source(),
                        params.behavior(),
                        params.rule()
                ));
                write(writer, ResponseEnvelope.success(request.id(), objectMapper.valueToTree(result)));
            }
            case "settings.update" -> {
                SettingsUpdateParams params = requireParams(request, SettingsUpdateParams.class);
                commandService.updateSettings(new CommandService.SettingsPatch(
                        params.fastMode(),
                        params.verboseOutput(),
                        params.reasoningVisible(),
                        params.alwaysCopyFullResponse(),
                        params.effortLevel()
                ));
                write(writer, ResponseEnvelope.success(
                        request.id(),
                        objectMapper.valueToTree(new MutationResult("Settings updated.", snapshot()))
                ));
            }
            case "prompt.cancel" -> handlePromptCancel(request, writer);
            case "permission.respond" -> handlePermissionRespond(request, writer);
            case "prompt.submit" -> handlePromptSubmit(request, writer);
            default -> write(writer, ResponseEnvelope.failure(
                    request.id(),
                    new ProtocolError("unknown_method", "Unsupported stdio method: " + request.method())
            ));
        }
    }

    private void handleProviderConnect(RequestEnvelope request, PrintWriter writer) {
        ProviderConnectParams params = requireParams(request, ProviderConnectParams.class);
        ProviderId providerId = parseProviderId(params.providerId());
        AuthMethod authMethod = parseAuthMethod(params.authMethod());
        String message = switch (authMethod) {
            case API_KEY -> providerService.connectApiKeyEnv(providerId, params.apiKeyEnv());
            case AWS_CREDENTIALS -> providerService.connectAwsProfile(providerId, params.awsProfile());
            case BROWSER_SSO -> providerService.connectBrowser(providerId, status ->
                    write(writer, EventEnvelope.of(
                            request.id(),
                            "provider.connect.status",
                            objectMapper.valueToTree(new StatusEvent(status))
                    )));
        };

        write(writer, ResponseEnvelope.success(request.id(), objectMapper.valueToTree(new MutationResult(message, snapshot()))));
    }

    private void handlePromptSubmit(RequestEnvelope request, PrintWriter writer) {
        PromptSubmitParams params = requireParams(request, PromptSubmitParams.class);
        if (params.text() == null || params.text().isBlank()) {
            write(writer, ResponseEnvelope.failure(
                    request.id(),
                    new ProtocolError("invalid_prompt", "Prompt text must not be blank.")
            ));
            return;
        }

        write(writer, EventEnvelope.of(
                request.id(),
                "prompt.started",
                objectMapper.valueToTree(new StatusEvent("Prompt started"))
        ));

        ToolPermissionGateway permissionGateway = createPermissionGateway(request.id(), writer);
        try {
            ActivePromptRun activePromptRun = activePromptRuns.get(request.id());
            var result = promptRouter.executeWithEvents(
                    params.text(),
                    event -> emitPromptEvent(request.id(), writer, event),
                    permissionGateway,
                    activePromptRun == null ? () -> "Prompt cancelled." : activePromptRun::cancelReason
            );
            activePromptRun = activePromptRuns.remove(request.id());
            if (activePromptRun != null && !activePromptRun.complete()) {
                return;
            }

            write(writer, ResponseEnvelope.success(
                    request.id(),
                    objectMapper.valueToTree(new PromptSubmitResult(result.sessionId(), result.modelId(), result.text(), snapshot()))
            ));
        } catch (RuntimeException exception) {
            ActivePromptRun activePromptRun = activePromptRuns.remove(request.id());
            if (activePromptRun != null && !activePromptRun.complete()) {
                return;
            }
            throw exception;
        }
    }

    private void handlePermissionRespond(RequestEnvelope request, PrintWriter writer) {
        PermissionRespondParams params = requireParams(request, PermissionRespondParams.class);
        CompletableFuture<ToolPermissionDecision> future = awaitPendingPermissionDecision(params.requestId());
        ToolPermissionRequest pendingRequest = pendingPermissionRequests.remove(params.requestId());
        if (future == null) {
            write(writer, ResponseEnvelope.failure(
                    request.id(),
                    new ProtocolError("unknown_permission_request", "Permission request is no longer pending.")
            ));
            return;
        }

        String decisionReason = params.decisionReason() == null || params.decisionReason().isBlank()
                ? null
                : params.decisionReason().trim();
        ToolPermissionDecision decision = switch (params.decision() == null ? "" : params.decision().trim().toLowerCase()) {
            case "allow" -> ToolPermissionDecision.allow(
                    decisionReason == null ? "Approved from OpenClaude UI." : decisionReason,
                    params.payloadJson(),
                    params.updatedInputJson(),
                    Boolean.TRUE.equals(params.userModified())
            );
            case "deny" -> Boolean.TRUE.equals(params.interrupt())
                    ? ToolPermissionDecision.denyInterrupt(
                            decisionReason == null ? "Denied from OpenClaude UI." : decisionReason,
                            params.payloadJson()
                    )
                    : ToolPermissionDecision.deny(
                            decisionReason == null ? "Denied from OpenClaude UI." : decisionReason,
                            params.payloadJson()
                    );
            default -> throw new IllegalArgumentException("Unsupported permission decision: " + params.decision());
        };
        persistPermissionDecision(pendingRequest, decision);
        future.complete(decision);
        write(writer, ResponseEnvelope.success(
                request.id(),
                objectMapper.valueToTree(new MutationResult("Permission decision recorded.", snapshot()))
        ));
    }

    private void handlePromptCancel(RequestEnvelope request, PrintWriter writer) {
        PromptCancelParams params = requireParams(request, PromptCancelParams.class);
        if (params.requestId() == null || params.requestId().isBlank()) {
            write(writer, ResponseEnvelope.failure(
                    request.id(),
                    new ProtocolError("invalid_prompt_request", "requestId is required.")
            ));
            return;
        }

        ActivePromptRun activePromptRun = activePromptRuns.remove(params.requestId());
        if (activePromptRun == null) {
            write(writer, ResponseEnvelope.failure(
                    request.id(),
                    new ProtocolError("unknown_prompt_request", "Prompt request is no longer active.")
            ));
            return;
        }

        if (!activePromptRun.canInterruptNow()) {
            activePromptRuns.put(params.requestId(), activePromptRun);
            write(writer, ResponseEnvelope.failure(
                    request.id(),
                    new ProtocolError("prompt_blocked", "Prompt cannot be interrupted while blocking tool calls are running.")
            ));
            return;
        }

        activePromptRun.cancel("Prompt interrupted.");
        for (String permissionRequestId : activePromptRun.permissionRequestIds()) {
            CompletableFuture<ToolPermissionDecision> future = pendingPermissionDecisions.remove(permissionRequestId);
            if (future != null) {
                future.complete(ToolPermissionDecision.denyInterrupt(activePromptRun.cancelReason()));
            }
            pendingPermissionRequests.remove(permissionRequestId);
        }
        Future<?> future = activePromptRun.future();
        if (future != null) {
            future.cancel(true);
        }

        write(writer, ResponseEnvelope.failure(
                params.requestId(),
                new ProtocolError("prompt_cancelled", activePromptRun.cancelReason())
        ));
        write(writer, ResponseEnvelope.success(
                request.id(),
                objectMapper.valueToTree(new MutationResult(activePromptRun.cancelReason(), snapshot()))
        ));
    }

    private CompletableFuture<ToolPermissionDecision> awaitPendingPermissionDecision(String requestId) {
        for (int attempt = 0; attempt < 200; attempt += 1) {
            CompletableFuture<ToolPermissionDecision> future = pendingPermissionDecisions.remove(requestId);
            if (future != null) {
                return future;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private BackendSnapshot snapshot() {
        OpenClaudeState state = providerService.visibleState();
        List<SessionMessage> sessionMessages = sessionMessages(state);
        List<SessionMessageView> messages = SessionMessageView.fromSessionMessages(sessionMessages);
        return new BackendSnapshot(
                StateView.from(state),
                SettingsView.from(state.settings(), stateStore.currentEffortLevel(state.settings().effortLevel())),
                SessionSummaryView.from(commandService.sessionSummary()),
                providerService.listProviders().stream()
                        .map(ProviderView::from)
                        .toList(),
                modelService.listConnectedModels().stream()
                        .map(ModelView::from)
                        .toList(),
                commandService.listCommands().stream()
                        .map(CommandView::from)
                        .toList(),
                messages
        );
    }

    private List<SessionMessage> sessionMessages(OpenClaudeState state) {
        if (state.activeSessionId() == null || state.activeSessionId().isBlank()) {
            return List.of();
        }

        ConversationSession session = sessionStore.loadOrCreate(state.activeSessionId());
        return SessionCompaction.messagesAfterCompactBoundary(session.messages());
    }

    private static void requireRequest(RequestEnvelope request) {
        if (request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("Stdio request id is required.");
        }
        if (request.method() == null || request.method().isBlank()) {
            throw new IllegalArgumentException("Stdio request method is required.");
        }
    }

    private <T> T requireParams(RequestEnvelope request, Class<T> type) {
        try {
            JsonNode params = request.params();
            if (params == null || params.isNull()) {
                throw new IllegalArgumentException("Params are required for method " + request.method());
            }
            return objectMapper.treeToValue(params, type);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid params for method " + request.method(), exception);
        }
    }

    private void write(PrintWriter writer, Object payload) {
        try {
            synchronized (writerLock) {
                writer.println(objectMapper.writeValueAsString(payload));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write stdio payload", exception);
        }
    }

    private void emitPromptEvent(String requestId, PrintWriter writer, PromptEvent event) {
        if (event instanceof TextDeltaEvent textDeltaEvent) {
            write(writer, EventEnvelope.of(
                    requestId,
                    "prompt.delta",
                    objectMapper.valueToTree(new PromptDeltaEvent(textDeltaEvent.text()))
            ));
            return;
        }
        if (event instanceof ReasoningDeltaEvent reasoningDeltaEvent) {
            write(writer, EventEnvelope.of(
                    requestId,
                    "prompt.reasoning.delta",
                    objectMapper.valueToTree(new PromptReasoningDeltaEvent(
                            reasoningDeltaEvent.text(),
                            reasoningDeltaEvent.summary()
                    ))
            ));
            return;
        }
        if (event instanceof PromptStatusEvent promptStatusEvent) {
            write(writer, EventEnvelope.of(
                    requestId,
                    "prompt.status",
                    objectMapper.valueToTree(new StatusEvent(promptStatusEvent.message()))
            ));
            return;
        }
        if (event instanceof ToolCallEvent toolCallEvent) {
            ActivePromptRun activePromptRun = activePromptRuns.get(requestId);
            if (activePromptRun != null) {
                activePromptRun.recordToolPhase(
                        toolCallEvent.toolId(),
                        toolCallEvent.toolName(),
                        toolCallEvent.phase()
                );
            }
            String eventName = switch (toolCallEvent.phase()) {
                case "started" -> "prompt.tool.started";
                case "completed" -> "prompt.tool.completed";
                case "failed", "cancelled" -> "prompt.tool.failed";
                default -> "prompt.tool.delta";
            };
            write(writer, EventEnvelope.of(
                    requestId,
                    eventName,
                    objectMapper.valueToTree(new PromptToolEvent(
                            toolCallEvent.toolId(),
                            toolCallEvent.toolName(),
                            toolCallEvent.phase(),
                            toolCallEvent.text(),
                            toolCallEvent.command()
                    ))
            ));
            return;
        }
        if (event instanceof ToolPermissionEvent toolPermissionEvent) {
            write(writer, EventEnvelope.of(
                    requestId,
                    "permission.requested",
                    objectMapper.valueToTree(new PermissionRequestEvent(
                            toolPermissionEvent.requestId(),
                            toolPermissionEvent.toolId(),
                            toolPermissionEvent.toolName(),
                            toolPermissionEvent.inputJson(),
                            toolPermissionEvent.command(),
                            toolPermissionEvent.reason(),
                            toolPermissionEvent.interactionType(),
                            toolPermissionEvent.interactionJson()
                    ))
            ));
        }
    }

    private ToolPermissionGateway createPermissionGateway(String requestId, PrintWriter writer) {
        return new ToolPermissionGateway() {
            @Override
            public ToolPermissionDecision lookupPersistedDecision(ToolPermissionRequest permissionRequest) {
                return findPersistedDecision(permissionRequest);
            }

            @Override
            public ToolPermissionDecision requestPermission(ToolPermissionRequest permissionRequest) {
                CompletableFuture<ToolPermissionDecision> decisionFuture = new CompletableFuture<>();
                pendingPermissionDecisions.put(permissionRequest.requestId(), decisionFuture);
                pendingPermissionRequests.put(permissionRequest.requestId(), permissionRequest);
                ActivePromptRun activePromptRun = activePromptRuns.get(requestId);
                if (activePromptRun != null) {
                    activePromptRun.permissionRequestIds().add(permissionRequest.requestId());
                }
                try {
                    return decisionFuture.get(2, TimeUnit.MINUTES);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    ActivePromptRun currentRun = activePromptRuns.get(requestId);
                    return ToolPermissionDecision.denyInterrupt(
                            currentRun == null ? "Prompt cancelled." : currentRun.cancelReason()
                    );
                } catch (Exception exception) {
                    pendingPermissionDecisions.remove(permissionRequest.requestId());
                    pendingPermissionRequests.remove(permissionRequest.requestId());
                    return ToolPermissionDecision.deny("Permission request timed out.");
                } finally {
                    ActivePromptRun currentRun = activePromptRuns.get(requestId);
                    if (currentRun != null) {
                        currentRun.permissionRequestIds().remove(permissionRequest.requestId());
                    }
                }
            }
        };
    }

    private void submitPromptAsync(java.util.concurrent.ExecutorService executor, RequestEnvelope request, PrintWriter writer) {
        ActivePromptRun activePromptRun = new ActivePromptRun();
        activePromptRuns.put(request.id(), activePromptRun);
        Future<?> future = executor.submit(() -> handleSafely(request, writer));
        activePromptRun.future(future);
    }

    private static boolean isInterruptibleTool(String toolName) {
        return "bash".equalsIgnoreCase(toolName == null ? "" : toolName.trim());
    }

    private ToolPermissionDecision findPersistedDecision(ToolPermissionRequest request) {
        return permissionRulesStore.findDecision(request, sessionService.scopeWorkspaceRoot());
    }

    private void persistPermissionDecision(ToolPermissionRequest request, ToolPermissionDecision decision) {
        if (request == null || !request.interactionType().isBlank()) {
            return;
        }
        permissionRulesStore.persistSessionDecision(request, decision);
    }

    private static String normalizeMessage(RuntimeException exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null || cause.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : cause.getMessage();
    }

    private static ProtocolError protocolErrorFor(RuntimeException exception) {
        ProviderFailureClassifier.ProviderFailure failure = ProviderFailureClassifier.classify(exception);
        String code = switch (failure.category()) {
            case "rate_limit" -> "rate_limit";
            case "policy_limit" -> "policy_limit";
            case "auth_error" -> "auth_error";
            default -> "runtime_error";
        };
        return new ProtocolError(code, formatProviderFailureMessage(failure, exception));
    }

    private static String formatProviderFailureMessage(
            ProviderFailureClassifier.ProviderFailure failure,
            RuntimeException exception
    ) {
        if (failure == null) {
            return normalizeMessage(exception);
        }
        return switch (failure.category()) {
            case "rate_limit" -> {
                StringBuilder builder = new StringBuilder("Provider rate limit reached.");
                if (failure.limitState() != null && failure.limitState().resetAt() != null) {
                    builder.append(" Retry after ").append(failure.limitState().resetAt()).append('.');
                } else if (failure.limitState() != null && failure.limitState().retryAfter() != null) {
                    builder.append(" Retry after ").append(failure.limitState().retryAfter()).append('.');
                }
                if (failure.message() != null && !failure.message().isBlank()) {
                    builder.append(' ').append(failure.message());
                }
                yield builder.toString().trim();
            }
            case "policy_limit" -> "Provider policy or usage limit blocked this request. " + failure.message();
            case "auth_error" -> "Provider authentication failed. Reconnect the active provider and try again.";
            default -> normalizeMessage(exception);
        };
    }

    private static ProviderId parseProviderId(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("providerId is required.");
        }
        return ProviderId.parse(rawValue);
    }

    private static AuthMethod parseAuthMethod(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("authMethod is required.");
        }

        String normalized = rawValue.trim();
        for (AuthMethod candidate : AuthMethod.values()) {
            if (candidate.cliValue().equalsIgnoreCase(normalized) || candidate.name().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unsupported authMethod: " + rawValue);
    }

    public record RequestEnvelope(
            String kind,
            String id,
            String method,
            JsonNode params
    ) {
    }

    public record ResponseEnvelope(
            String kind,
            String id,
            boolean ok,
            JsonNode result,
            ProtocolError error
    ) {
        static ResponseEnvelope success(String id, JsonNode result) {
            return new ResponseEnvelope("response", id, true, result, null);
        }

        static ResponseEnvelope failure(String id, ProtocolError error) {
            return new ResponseEnvelope("response", id, false, null, error);
        }
    }

    public record EventEnvelope(
            String kind,
            String id,
            String event,
            JsonNode data
    ) {
        static EventEnvelope of(String id, String event, JsonNode data) {
            return new EventEnvelope("event", id, event, data);
        }
    }

    public record ProtocolError(
            String code,
            String message
    ) {
    }

    public record ProviderConnectParams(
            String providerId,
            String authMethod,
            String apiKeyEnv,
            String awsProfile
    ) {
    }

    public record ProviderSelection(
            String providerId
    ) {
    }

    public record ModelSelection(
            String providerId,
            String modelId
    ) {
    }

    public record PromptSubmitParams(
            String text
    ) {
    }

    public record PromptCancelParams(
            String requestId
    ) {
    }

    public record CommandRunParams(
            String commandName,
            String args
    ) {
    }

    public record PermissionEditorMutationParams(
            String action,
            String source,
            String behavior,
            String rule
    ) {
    }

    public record SessionSelectionParams(
            String sessionId
    ) {
    }

    public record SessionRenameParams(
            String title
    ) {
    }

    public record SessionRewindParams(
            String messageId
    ) {
    }

    public record SessionPlanModeParams(
            boolean enabled
    ) {
    }

    public record SettingsUpdateParams(
            Boolean fastMode,
            Boolean verboseOutput,
            Boolean reasoningVisible,
            Boolean alwaysCopyFullResponse,
            String effortLevel
    ) {
    }

    public record PermissionRespondParams(
            String requestId,
            String decision,
            String payloadJson,
            String updatedInputJson,
            Boolean userModified,
            String decisionReason,
            Boolean interrupt
    ) {
    }

    public record StatusEvent(
            String message
    ) {
    }

    public record PromptDeltaEvent(
            String text
    ) {
    }

    public record PromptReasoningDeltaEvent(
            String text,
            boolean summary
    ) {
    }

    public record PromptToolEvent(
            String toolId,
            String toolName,
            String phase,
            String text,
            String command
    ) {
    }

    public record PermissionRequestEvent(
            String requestId,
            String toolId,
            String toolName,
            String inputJson,
            String command,
            String reason,
            String interactionType,
            String interactionJson
    ) {
    }

    public record MutationResult(
            String message,
            BackendSnapshot snapshot
    ) {
    }

    public record PromptSubmitResult(
            String sessionId,
            String modelId,
            String text,
            BackendSnapshot snapshot
    ) {
    }

    public record CommandResult(
            String message,
            PanelView panel,
            BackendSnapshot snapshot
    ) {
    }

    public record ProvidersResult(
            List<ProviderView> providers
    ) {
    }

    public record ModelsResult(
            List<ModelView> models
    ) {
    }

    public record CommandsResult(
            List<CommandView> commands
    ) {
    }

    public record SessionsResult(
            List<SessionListItemView> sessions
    ) {
    }

    public record BackendSnapshot(
            StateView state,
            SettingsView settings,
            SessionSummaryView session,
            List<ProviderView> providers,
            List<ModelView> models,
            List<CommandView> commands,
            List<SessionMessageView> messages
    ) {
    }

    public record StateView(
            String activeProvider,
            String activeModelId,
            String activeSessionId,
            List<ConnectionView> connections
    ) {
        static StateView from(OpenClaudeState state) {
            return new StateView(
                    state.activeProvider() == null ? null : state.activeProvider().cliValue(),
                    state.activeModelId(),
                    state.activeSessionId(),
                    state.connections().values().stream()
                            .map(ConnectionView::from)
                            .toList()
            );
        }
    }

    public record SettingsView(
            boolean fastMode,
            boolean verboseOutput,
            boolean reasoningVisible,
            boolean alwaysCopyFullResponse,
            String effortLevel
    ) {
        static SettingsView from(OpenClaudeSettings settings, String effortLevel) {
            return new SettingsView(
                    settings.fastMode(),
                    settings.verboseOutput(),
                    settings.reasoningVisible(),
                    settings.alwaysCopyFullResponse(),
                    effortLevel
            );
        }
    }

    public record SessionSummaryView(
            String sessionId,
            String title,
            Instant startedAt,
            Instant updatedAt,
            long durationSeconds,
            int userMessageCount,
            int assistantMessageCount,
            int totalMessageCount,
            int estimatedContextTokens,
            int contextWindowTokens,
            double totalCostUsd,
            String workingDirectory,
            String workspaceRoot,
            boolean planMode,
            List<TodoView> todos
    ) {
        static SessionSummaryView from(CommandService.SessionSummary summary) {
            return new SessionSummaryView(
                    summary.sessionId(),
                    summary.title(),
                    summary.startedAt(),
                    summary.updatedAt(),
                    summary.durationSeconds(),
                    summary.userMessageCount(),
                    summary.assistantMessageCount(),
                    summary.totalMessageCount(),
                    summary.estimatedContextTokens(),
                    summary.contextWindowTokens(),
                    summary.totalCostUsd(),
                    summary.workingDirectory(),
                    summary.workspaceRoot(),
                    summary.planMode(),
                    summary.todos().stream().map(TodoView::from).toList()
            );
        }
    }

    public record SessionListItemView(
            String sessionId,
            String title,
            String preview,
            Instant updatedAt,
            int messageCount,
            String workingDirectory,
            String workspaceRoot,
            boolean active
    ) {
        static SessionListItemView from(SessionService.SessionListItem item) {
            return new SessionListItemView(
                    item.sessionId(),
                    item.title(),
                    item.preview(),
                    item.updatedAt(),
                    item.messageCount(),
                    item.workingDirectory(),
                    item.workspaceRoot(),
                    item.active()
            );
        }
    }

    public record TodoView(
            String content,
            String status,
            String activeForm
    ) {
        static TodoView from(TodoItem todo) {
            return new TodoView(todo.content(), todo.status(), todo.activeForm());
        }
    }

    public record ConnectionView(
            String providerId,
            String authMethod,
            String credentialReference,
            Instant connectedAt
    ) {
        static ConnectionView from(com.openclaude.core.provider.ProviderConnectionState connectionState) {
            return new ConnectionView(
                    connectionState.providerId().cliValue(),
                    connectionState.authMethod().cliValue(),
                    connectionState.credentialReference(),
                    connectionState.connectedAt()
            );
        }
    }

    public record ProviderView(
            String providerId,
            String displayName,
            List<String> supportedAuthMethods,
            boolean connected,
            boolean active,
            ConnectionView connection
    ) {
        static ProviderView from(ProviderService.ProviderView providerView) {
            return new ProviderView(
                    providerView.providerId().cliValue(),
                    providerView.displayName(),
                    providerView.supportedAuthMethods().stream()
                            .map(AuthMethod::cliValue)
                            .sorted()
                            .toList(),
                    providerView.connected(),
                    providerView.active(),
                    providerView.connectionState() == null ? null : ConnectionView.from(providerView.connectionState())
            );
        }
    }

    public record ModelView(
            String id,
            String displayName,
            String providerId,
            String providerDisplayName,
            boolean providerActive,
            boolean active
    ) {
        static ModelView from(ModelService.ModelView modelView) {
            return new ModelView(
                    modelView.id(),
                    modelView.displayName(),
                    modelView.providerId().cliValue(),
                    modelView.providerDisplayName(),
                    modelView.providerActive(),
                    modelView.active()
            );
        }
    }

    public record CommandView(
            String name,
            String displayName,
            String description,
            List<String> aliases,
            String argumentHint,
            String execution,
            String overlay,
            String frontendAction,
            boolean enabled,
            boolean hidden
    ) {
        static CommandView from(CommandService.CommandView command) {
            return new CommandView(
                    command.name(),
                    command.displayName(),
                    command.description(),
                    command.aliases(),
                    command.argumentHint(),
                    executionFor(command),
                    overlayFor(command),
                    frontendActionFor(command),
                    command.enabled(),
                    command.hidden()
            );
        }

        private static String executionFor(CommandService.CommandView command) {
            return switch (command.name()) {
                case "provider", "models", "config", "keybindings", "help" -> "overlay";
                case "copy", "exit" -> "frontend";
                default -> "backend";
            };
        }

        private static String overlayFor(CommandService.CommandView command) {
            return switch (command.name()) {
                case "provider" -> "providers";
                case "models" -> "models";
                case "config" -> "config";
                case "keybindings" -> "keybindings";
                case "help" -> "help";
                default -> null;
            };
        }

        private static String frontendActionFor(CommandService.CommandView command) {
            return switch (command.name()) {
                case "copy" -> "copy";
                case "exit" -> "exit";
                default -> null;
            };
        }
    }

    public record PanelView(
            String kind,
            String title,
            String subtitle,
            List<PanelSectionView> sections,
            ContextUsageView contextUsage
    ) {
        static PanelView from(CommandService.PanelView panel) {
            return new PanelView(
                    panel.kind(),
                    panel.title(),
                    panel.subtitle(),
                    panel.sections().stream().map(PanelSectionView::from).toList(),
                    panel.contextUsage() == null ? null : ContextUsageView.from(panel.contextUsage())
            );
        }
    }

    public record PanelSectionView(
            String title,
            List<String> lines
    ) {
        static PanelSectionView from(CommandService.PanelSection section) {
            return new PanelSectionView(section.title(), section.lines());
        }
    }

    public record ContextUsageView(
            int estimatedTokens,
            int contextWindowTokens,
            int usedCells,
            int totalCells,
            String status
    ) {
        static ContextUsageView from(CommandService.ContextUsageView usage) {
            return new ContextUsageView(
                    usage.estimatedTokens(),
                    usage.contextWindowTokens(),
                    usage.usedCells(),
                    usage.totalCells(),
                    usage.status()
            );
        }
    }

    public record SessionMessageView(
            String kind,
            String id,
            Instant createdAt,
            String text,
            String displayText,
            String providerId,
            String modelId,
            String assistantMessageId,
            List<String> siblingToolIds,
            String toolGroupKey,
            String toolRenderClass,
            String attachmentKind,
            String hookEvent,
            String toolId,
            String toolName,
            String phase,
            Boolean isError,
            String inputJson,
            String command,
            String permissionRequestId,
            String interactionType,
            String interactionJson,
            String source,
            String reason
    ) {
        public SessionMessageView(
                String kind,
                String id,
                Instant createdAt,
                String text,
                String displayText,
                String providerId,
                String modelId,
                String assistantMessageId,
                List<String> siblingToolIds,
                String toolGroupKey,
                String toolRenderClass,
                String attachmentKind,
                String hookEvent,
                String toolId,
                String toolName,
                String phase,
                Boolean isError,
                String inputJson,
                String command,
                String permissionRequestId,
                String interactionType,
                String interactionJson,
                String source
        ) {
            this(
                    kind,
                    id,
                    createdAt,
                    text,
                    displayText,
                    providerId,
                    modelId,
                    assistantMessageId,
                    siblingToolIds,
                    toolGroupKey,
                    toolRenderClass,
                    attachmentKind,
                    hookEvent,
                    toolId,
                    toolName,
                    phase,
                    isError,
                    inputJson,
                    command,
                    permissionRequestId,
                    interactionType,
                    interactionJson,
                    source,
                    null
            );
        }

        public SessionMessageView(
                String kind,
                String id,
                Instant createdAt,
                String text,
                String providerId,
                String modelId,
                String assistantMessageId,
                List<String> siblingToolIds,
                String toolGroupKey,
                String toolRenderClass,
                String attachmentKind,
                String hookEvent,
                String toolId,
                String toolName,
                String phase,
                Boolean isError,
                String inputJson,
                String command,
                String permissionRequestId,
                String interactionType,
                String interactionJson,
                String source,
                String reason
        ) {
            this(
                    kind,
                    id,
                    createdAt,
                    text,
                    null,
                    providerId,
                    modelId,
                    assistantMessageId,
                    siblingToolIds,
                    toolGroupKey,
                    toolRenderClass,
                    attachmentKind,
                    hookEvent,
                    toolId,
                    toolName,
                    phase,
                    isError,
                    inputJson,
                    command,
                    permissionRequestId,
                    interactionType,
                    interactionJson,
                    source,
                    reason
            );
        }

        public SessionMessageView(
                String kind,
                String id,
                Instant createdAt,
                String text,
                String providerId,
                String modelId,
                String assistantMessageId,
                List<String> siblingToolIds,
                String toolGroupKey,
                String toolRenderClass,
                String attachmentKind,
                String hookEvent,
                String toolId,
                String toolName,
                String phase,
                Boolean isError,
                String inputJson,
                String command,
                String permissionRequestId,
                String interactionType,
                String interactionJson,
                String source
        ) {
            this(
                    kind,
                    id,
                    createdAt,
                    text,
                    null,
                    providerId,
                    modelId,
                    assistantMessageId,
                    siblingToolIds,
                    toolGroupKey,
                    toolRenderClass,
                    attachmentKind,
                    hookEvent,
                    toolId,
                    toolName,
                    phase,
                    isError,
                    inputJson,
                    command,
                    permissionRequestId,
                    interactionType,
                    interactionJson,
                    source,
                    null
            );
        }

        public SessionMessageView(
                String kind,
                String id,
                Instant createdAt,
                String text,
                String providerId,
                String modelId,
                String assistantMessageId,
                List<String> siblingToolIds,
                String toolGroupKey,
                String toolRenderClass,
                String attachmentKind,
                String hookEvent,
                String toolId,
                String toolName,
                String phase,
                Boolean isError,
                String inputJson,
                String command,
                String permissionRequestId,
                String source,
                String reason
        ) {
            this(
                    kind,
                    id,
                    createdAt,
                    text,
                    null,
                    providerId,
                    modelId,
                    assistantMessageId,
                    siblingToolIds,
                    toolGroupKey,
                    toolRenderClass,
                    attachmentKind,
                    hookEvent,
                    toolId,
                    toolName,
                    phase,
                    isError,
                    inputJson,
                    command,
                    permissionRequestId,
                    null,
                    null,
                    source,
                    reason
            );
        }

        static SessionMessageView attachment(SessionMessage.AttachmentMessage attachmentMessage) {
            return new SessionMessageView(
                    "attachment",
                    attachmentMessage.id(),
                    attachmentMessage.createdAt(),
                    attachmentMessage.text(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    attachmentMessage.attachment() == null ? null : attachmentMessage.attachment().attachmentKind(),
                    attachmentMessage.attachment() == null ? null : blankToNull(attachmentMessage.attachment().hookEvent()),
                    attachmentMessage.attachment() == null ? null : blankToNull(attachmentMessage.attachment().toolUseId()),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    attachmentMessage.source(),
                    null
            );
        }

        static SessionMessageView tombstone(SessionMessage.TombstoneMessage tombstoneMessage) {
            return new SessionMessageView(
                    "tombstone",
                    tombstoneMessage.id(),
                    tombstoneMessage.createdAt(),
                    tombstoneMessage.text(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    tombstoneMessage.reason()
            );
        }

        static List<SessionMessageView> fromSessionMessages(List<SessionMessage> messages) {
            java.util.Map<String, ToolUseContext> toolContexts = buildToolUseContexts(messages);
            java.util.ArrayList<SessionMessageView> views = new java.util.ArrayList<>();
            for (SessionMessage message : messages) {
                views.addAll(from(message, toolContexts));
            }
            return java.util.List.copyOf(views);
        }

        private static List<SessionMessageView> from(
                SessionMessage message,
                java.util.Map<String, ToolUseContext> toolContexts
        ) {
            return switch (message) {
                case SessionMessage.UserMessage userMessage -> List.of(new SessionMessageView(
                        userMessage.compactSummary() ? "compact_summary" : "user",
                        userMessage.id(),
                        userMessage.createdAt(),
                        userMessage.text(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ));
                case SessionMessage.AssistantMessage assistantMessage -> {
                    java.util.ArrayList<SessionMessageView> views = new java.util.ArrayList<>();
                    if (!assistantMessage.text().isBlank()) {
                        views.add(new SessionMessageView(
                                "assistant",
                                assistantMessage.id(),
                                assistantMessage.createdAt(),
                                assistantMessage.text(),
                                assistantMessage.providerId() == null ? null : assistantMessage.providerId().cliValue(),
                                assistantMessage.modelId(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        ));
                    }
                    List<String> siblingToolIds = assistantMessage.toolUses().stream()
                            .map(com.openclaude.provider.spi.ToolUseContentBlock::toolUseId)
                            .filter(toolUseId -> toolUseId != null && !toolUseId.isBlank())
                            .toList();
                    for (var toolUse : assistantMessage.toolUses()) {
                        String toolGroupKey = toolGroupKey(assistantMessage.id(), toolUse.toolName());
                        views.add(new SessionMessageView(
                                "tool_use",
                                assistantMessage.id() + ":" + toolUse.toolUseId(),
                                assistantMessage.createdAt(),
                                toolUse.inputJson(),
                                assistantMessage.providerId() == null ? null : assistantMessage.providerId().cliValue(),
                                assistantMessage.modelId(),
                                assistantMessage.id(),
                                siblingToolIds,
                                toolGroupKey,
                                toolRenderClass(toolUse.toolName(), null, toolUse.inputJson()),
                                null,
                                null,
                                toolUse.toolUseId(),
                                toolUse.toolName(),
                                "started",
                                null,
                                toolUse.inputJson(),
                                null,
                                null,
                                null,
                                null
                        ));
                    }
                    yield java.util.List.copyOf(views);
                }
                case SessionMessage.CompactBoundaryMessage compactBoundaryMessage -> List.of(new SessionMessageView(
                        "compact_boundary",
                        compactBoundaryMessage.id(),
                        compactBoundaryMessage.createdAt(),
                        compactBoundaryMessage.text(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        compactBoundaryMessage.trigger(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        compactBoundaryReason(compactBoundaryMessage)
                ));
                case SessionMessage.ThinkingMessage thinkingMessage -> List.of(new SessionMessageView(
                        "thinking",
                        thinkingMessage.id(),
                        thinkingMessage.createdAt(),
                        thinkingMessage.text(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ));
                case SessionMessage.ToolInvocationMessage toolMessage -> {
                    ToolUseContext toolContext = toolContexts.get(toolMessage.toolId());
                    yield List.of(new SessionMessageView(
                            "tool",
                            toolMessage.id(),
                            toolMessage.createdAt(),
                            toolMessage.text(),
                            null,
                            null,
                            toolContext == null ? null : toolContext.assistantMessageId(),
                            toolContext == null ? null : toolContext.siblingToolIds(),
                            toolContext == null ? null : toolContext.toolGroupKey(toolMessage.toolName()),
                            toolRenderClass(toolMessage.toolName(), toolMessage.command(), toolMessage.inputJson()),
                            null,
                            null,
                            toolMessage.toolId(),
                            toolMessage.toolName(),
                            toolMessage.phase(),
                            toolMessage.isError(),
                            toolMessage.inputJson(),
                            toolMessage.command(),
                            toolMessage.permissionRequestId(),
                            blankToNull(toolMessage.interactionType()),
                            blankToNull(toolMessage.interactionJson()),
                            null,
                            null
                    ));
                }
                case SessionMessage.ToolResultMessage toolResultMessage -> {
                    ToolUseContext toolContext = toolContexts.get(toolResultMessage.toolUseId());
                    yield List.of(new SessionMessageView(
                            "tool_result",
                            toolResultMessage.id(),
                            toolResultMessage.createdAt(),
                            toolResultMessage.text(),
                            toolResultMessage.displayText(),
                            null,
                            null,
                            toolContext == null ? null : toolContext.assistantMessageId(),
                            toolContext == null ? null : toolContext.siblingToolIds(),
                            toolContext == null ? null : toolContext.toolGroupKey(toolResultMessage.toolName()),
                            toolRenderClass(toolResultMessage.toolName(), null, null),
                            null,
                            null,
                            toolResultMessage.toolUseId(),
                            toolResultMessage.toolName(),
                            toolResultMessage.isError() ? "failed" : "completed",
                            toolResultMessage.isError(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null
                    ));
                }
                case SessionMessage.SystemMessage systemMessage -> List.of(new SessionMessageView(
                        "system",
                        systemMessage.id(),
                        systemMessage.createdAt(),
                        systemMessage.text(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ));
                case SessionMessage.ProgressMessage progressMessage -> List.of(new SessionMessageView(
                        "progress",
                        progressMessage.id(),
                        progressMessage.createdAt(),
                        progressMessage.text(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        progressMessage.hookEvent(),
                        progressMessage.toolUseId(),
                        progressMessage.hookName(),
                        progressMessage.progressKind(),
                        progressMessage.isError(),
                        null,
                        progressMessage.command(),
                        null,
                        null,
                        null
                ));
                case SessionMessage.AttachmentMessage attachmentMessage -> List.of(SessionMessageView.attachment(attachmentMessage));
                case SessionMessage.TombstoneMessage tombstoneMessage -> List.of(SessionMessageView.tombstone(tombstoneMessage));
            };
        }

        private static java.util.Map<String, ToolUseContext> buildToolUseContexts(List<SessionMessage> messages) {
            java.util.LinkedHashMap<String, ToolUseContext> contexts = new java.util.LinkedHashMap<>();
            for (SessionMessage message : messages) {
                if (!(message instanceof SessionMessage.AssistantMessage assistantMessage) || assistantMessage.toolUses().isEmpty()) {
                    continue;
                }
                List<String> siblingToolIds = assistantMessage.toolUses().stream()
                        .map(com.openclaude.provider.spi.ToolUseContentBlock::toolUseId)
                        .filter(toolUseId -> toolUseId != null && !toolUseId.isBlank())
                        .toList();
                ToolUseContext context = new ToolUseContext(assistantMessage.id(), siblingToolIds);
                for (var toolUse : assistantMessage.toolUses()) {
                    if (toolUse.toolUseId() != null && !toolUse.toolUseId().isBlank()) {
                        contexts.put(toolUse.toolUseId(), context);
                    }
                }
            }
            return java.util.Map.copyOf(contexts);
        }

        private static String toolGroupKey(String assistantMessageId, String toolName) {
            if (assistantMessageId == null || assistantMessageId.isBlank() || toolName == null || toolName.isBlank()) {
                return null;
            }
            return assistantMessageId + ":" + toolName.trim().toLowerCase(java.util.Locale.ROOT);
        }

        private static String toolRenderClass(String toolName, String command, String inputJson) {
            if (toolName == null || toolName.isBlank()) {
                return null;
            }

            String normalizedToolName = toolName.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (normalizedToolName) {
                case "read", "file_read" -> "read";
                case "grep", "glob", "websearch", "web_search" -> "search";
                case "webfetch", "web_fetch" -> "fetch";
                case "write", "edit" -> "write";
                case "todowrite" -> "todo";
                case "enterplanmode", "exitplanmode" -> "plan";
                case "bash" -> classifyBashRenderClass(command, inputJson);
                default -> "other";
            };
        }

        private static String classifyBashRenderClass(String command, String inputJson) {
            String resolvedCommand = blankToNull(command);
            if (resolvedCommand == null) {
                resolvedCommand = extractCommandFromInputJson(inputJson);
            }
            if (resolvedCommand == null || resolvedCommand.isBlank()) {
                return "bash";
            }

            String normalized = resolvedCommand.strip().toLowerCase(java.util.Locale.ROOT);
            if (normalized.startsWith("ls ") || normalized.equals("ls") || normalized.startsWith("pwd")
                    || normalized.startsWith("tree") || normalized.startsWith("du ")
                    || normalized.startsWith("stat ") || normalized.startsWith("find ")) {
                return "list";
            }
            if (normalized.startsWith("cat ") || normalized.startsWith("head ")
                    || normalized.startsWith("tail ") || normalized.startsWith("sed ")
                    || normalized.startsWith("jq ")) {
                return "read";
            }
            if (normalized.startsWith("rg ") || normalized.startsWith("grep ")
                    || normalized.startsWith("fd ") || normalized.startsWith("git ")
                    || normalized.startsWith("gh ") || normalized.startsWith("docker ")
                    || normalized.startsWith("diff ") || normalized.startsWith("sort ")
                    || normalized.startsWith("uniq ") || normalized.startsWith("cut ")
                    || normalized.startsWith("tr ")) {
                return "search";
            }
            return ShellPermissionPolicy.isReadOnlyCommand(resolvedCommand) ? "bash" : "other";
        }

        private static String extractCommandFromInputJson(String inputJson) {
            if (inputJson == null || inputJson.isBlank()) {
                return null;
            }
            try {
                JsonNode root = new ObjectMapper().readTree(inputJson);
                if (root == null || !root.hasNonNull("command")) {
                    return null;
                }
                return blankToNull(root.get("command").asText(""));
            } catch (Exception exception) {
                return null;
            }
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    private record ToolUseContext(
            String assistantMessageId,
            List<String> siblingToolIds
    ) {
        private ToolUseContext {
            siblingToolIds = siblingToolIds == null ? List.of() : List.copyOf(siblingToolIds);
        }

        private String toolGroupKey(String toolName) {
            return SessionMessageView.toolGroupKey(assistantMessageId, toolName);
        }
    }

    private static final class ActivePromptRun {
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final java.util.Set<String> permissionRequestIds = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<String, Boolean> activeToolInterruptibility = new ConcurrentHashMap<>();
        private volatile String cancelReason = "Prompt cancelled.";
        private volatile Future<?> future;

        private boolean complete() {
            return completed.compareAndSet(false, true);
        }

        private void cancel(String reason) {
            completed.set(true);
            cancelReason = reason == null || reason.isBlank() ? "Prompt cancelled." : reason;
        }

        private java.util.Set<String> permissionRequestIds() {
            return permissionRequestIds;
        }

        private void recordToolPhase(String toolId, String toolName, String phase) {
            if (toolId == null || toolId.isBlank()) {
                return;
            }
            String normalizedPhase = phase == null ? "" : phase.trim().toLowerCase(java.util.Locale.ROOT);
            if (
                    "completed".equals(normalizedPhase)
                            || "failed".equals(normalizedPhase)
                            || "cancelled".equals(normalizedPhase)
                            || "yielded".equals(normalizedPhase)
            ) {
                activeToolInterruptibility.remove(toolId);
                return;
            }
            activeToolInterruptibility.put(toolId, isInterruptibleTool(toolName));
        }

        private boolean canInterruptNow() {
            return activeToolInterruptibility.values().stream().allMatch(Boolean::booleanValue);
        }

        private String cancelReason() {
            return cancelReason;
        }

        private Future<?> future() {
            return future;
        }

        private void future(Future<?> future) {
            this.future = future;
        }
    }

    private static String compactBoundaryReason(SessionMessage.CompactBoundaryMessage compactBoundaryMessage) {
        StringBuilder reason = new StringBuilder()
                .append("preTokens=").append(compactBoundaryMessage.preTokens())
                .append(", summarized=").append(compactBoundaryMessage.messagesSummarized());
        if (compactBoundaryMessage.preservedSegment() != null && !compactBoundaryMessage.preservedSegment().isBlank()) {
            reason.append(", preservedSegment=")
                    .append(compactBoundaryMessage.preservedSegment().headId())
                    .append("->")
                    .append(compactBoundaryMessage.preservedSegment().anchorId())
                    .append("->")
                    .append(compactBoundaryMessage.preservedSegment().tailId());
        }
        return reason.toString();
    }
}
