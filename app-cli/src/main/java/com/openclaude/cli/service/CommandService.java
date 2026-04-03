package com.openclaude.cli.service;

import com.openclaude.cli.OpenClaudeCommand;
import com.openclaude.core.config.OpenClaudeEffort;
import com.openclaude.core.config.OpenClaudePaths;
import com.openclaude.core.config.OpenClaudeSettings;
import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.instructions.AgentsInstructionsLoader;
import com.openclaude.core.provider.OpenClaudeState;
import com.openclaude.core.provider.ProviderConnectionState;
import com.openclaude.core.provider.ProviderLimitState;
import com.openclaude.core.provider.ProviderRegistry;
import com.openclaude.core.provider.ProviderRuntimeDiagnostics;
import com.openclaude.core.query.QueryEngine;
import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.SessionCompaction;
import com.openclaude.core.session.ConversationSessionStore;
import com.openclaude.core.session.SessionMessage;
import com.openclaude.core.session.TodoItem;
import com.openclaude.core.tools.ToolPermissionRule;
import com.openclaude.core.tools.ToolPermissionSources;
import com.openclaude.core.tools.ToolRuntime;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.ProviderPlugin;
import com.openclaude.provider.spi.ProviderToolDefinition;
import com.openclaude.provider.spi.PromptMessage;
import com.openclaude.provider.spi.PromptMessageRole;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;

public final class CommandService {
    private static final int CONTEXT_BAR_CELLS = 24;
    private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 200_000;
    private static final int MAX_DIFF_LINES = 160;
    private static final java.util.Set<String> PERMISSION_TOOL_NAMES = java.util.Set.of(
            "bash",
            "write",
            "edit",
            "webfetch",
            "websearch",
            "askuserquestion",
            "enterplanmode",
            "exitplanmode"
    );
    private static final List<CommandView> COMMANDS = List.of(
            new CommandView("resume", "resume", "Resume another session from the current workspace", List.of(), "[session]", "local-jsx", "frontend", false, true, false),
            new CommandView("session", "session", "Show the current session and remote-session details", List.of(), null, "local", "backend", true, true, false),
            new CommandView("rename", "rename", "Rename the current session", List.of(), "<name>", "local-jsx", "frontend", false, true, false),
            new CommandView("login", "login", "Sign in with a provider account or switch provider authentication", List.of(), null, "local-jsx", "frontend", false, true, false),
            new CommandView("logout", "logout", "Sign out from the active provider", List.of(), null, "local-jsx", "frontend", false, true, false),
            new CommandView("provider", "provider", "Connect or switch an LLM provider", List.of(), null, "local-jsx", "frontend", false, true, false),
            new CommandView("models", "models", "Choose the active model from connected providers", List.of("model"), "[model]", "local-jsx", "frontend", true, true, false),
            new CommandView("config", "config", "Open the OpenClaude config panel", List.of("settings"), null, "local-jsx", "frontend", false, true, false),
            new CommandView("status", "status", "Show OpenClaude status including version, model, auth, and tool readiness", List.of(), null, "local", "backend", true, true, false),
            new CommandView("context", "context", "Visualize current context usage", List.of(), null, "local", "backend", true, true, false),
            new CommandView("tools", "tools", "List the tools available to the active model", List.of(), null, "local", "backend", true, true, false),
            new CommandView("permissions", "permissions", "Manage allow & deny tool permission rules", List.of("allowed-tools"), null, "local", "backend", true, true, false),
            new CommandView("usage", "usage", "Show plan usage limits", List.of(), null, "local", "backend", true, true, false),
            new CommandView("stats", "stats", "Show your OpenClaude usage statistics and activity", List.of(), null, "local", "backend", true, true, false),
            new CommandView("copy", "copy", "Copy the last assistant response to the clipboard", List.of(), "[N]", "local", "frontend", false, true, false),
            new CommandView("cost", "cost", "Show the total session duration and text volume", List.of(), null, "local", "backend", true, true, false),
            new CommandView("clear", "clear", "Clear conversation history and start a fresh session", List.of("reset", "new"), null, "local", "frontend", false, true, false),
            new CommandView("compact", "compact", "Compact conversation history into a summary", List.of(), null, "local", "backend", true, true, false),
            new CommandView("plan", "plan", "Enter plan mode or inspect the current plan", List.of(), "[description|open]", "local-jsx", "frontend", false, true, false),
            new CommandView("rewind", "rewind", "Restore the conversation to a previous user turn", List.of("checkpoint"), null, "local-jsx", "frontend", false, true, false),
            new CommandView("memory", "memory", "Edit OpenClaude memory files", List.of(), null, "local-jsx", "frontend", false, true, false),
            new CommandView("diff", "diff", "View uncommitted git changes for the current workspace", List.of(), null, "local", "backend", true, true, false),
            new CommandView("doctor", "doctor", "Diagnose the OpenClaude backend and workspace setup", List.of(), null, "local", "backend", true, true, false),
            new CommandView("tasks", "tasks", "List and manage background tasks", List.of("bashes"), null, "local-jsx", "frontend", false, true, false),
            new CommandView("fast", "fast", "Toggle fast mode for the current session", List.of(), "[on|off]", "local-jsx", "frontend", false, true, false),
            new CommandView("effort", "effort", "Set effort level for supported models", List.of(), "[low|medium|high|max|auto]", "local", "backend", true, true, false),
            new CommandView("keybindings", "keybindings", "Show the current keyboard shortcuts", List.of(), null, "local-jsx", "frontend", false, true, false),
            new CommandView("help", "help", "Show command and shortcut help", List.of(), null, "local-jsx", "frontend", false, true, false),
            new CommandView("exit", "exit", "Exit OpenClaude", List.of("quit"), null, "local", "frontend", false, true, false)
    );

    private final OpenClaudeStateStore stateStore;
    private final ConversationSessionStore sessionStore;
    private final ProviderRegistry providerRegistry;
    private final ToolRuntime toolRuntime;
    private final SessionService sessionService;
    private final CompactConversationService compactConversationService;
    private final PermissionRulesStore permissionRulesStore;
    private final Function<String, String> environmentLookup;
    private final AgentsInstructionsLoader instructionsLoader;

    public CommandService(
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore,
            ProviderRegistry providerRegistry,
            ToolRuntime toolRuntime,
            SessionService sessionService
    ) {
        this(stateStore, sessionStore, providerRegistry, toolRuntime, sessionService, System::getenv);
    }

    CommandService(
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore,
            ProviderRegistry providerRegistry,
            ToolRuntime toolRuntime,
            SessionService sessionService,
            Function<String, String> environmentLookup
    ) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry");
        this.toolRuntime = Objects.requireNonNull(toolRuntime, "toolRuntime");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.environmentLookup = environmentLookup == null ? System::getenv : environmentLookup;
        this.compactConversationService = new CompactConversationService(stateStore, sessionStore, providerRegistry);
        this.permissionRulesStore = new PermissionRulesStore(stateStore);
        this.instructionsLoader = new AgentsInstructionsLoader();
    }

    public List<CommandView> listCommands() {
        return COMMANDS;
    }

    public CommandRunResult run(String commandName, String args) {
        CommandView command = requireCommand(commandName);
        if (!"backend".equals(command.handler())) {
            throw new IllegalArgumentException("Command is not handled by the backend: /" + command.name());
        }

        return switch (command.name()) {
            case "cost" -> new CommandRunResult(
                    command,
                    "Rendered session cost summary.",
                    costPanel()
            );
            case "status" -> new CommandRunResult(
                    command,
                    "Rendered OpenClaude status.",
                    statusPanel()
            );
            case "context" -> new CommandRunResult(
                    command,
                    "Rendered context usage panel.",
                    contextPanel()
            );
            case "tools" -> new CommandRunResult(
                    command,
                    "Rendered available tools.",
                    toolsPanel()
            );
            case "permissions" -> executePermissionsCommand(command, args);
            case "usage" -> new CommandRunResult(
                    command,
                    "Rendered usage limits panel.",
                    usagePanel()
            );
            case "stats" -> new CommandRunResult(
                    command,
                    "Rendered usage statistics panel.",
                    statsPanel()
            );
            case "effort" -> new CommandRunResult(
                    command,
                    executeEffortCommand(args),
                    null
            );
            case "compact" -> new CommandRunResult(
                    command,
                    compactConversationService.compact(args).message(),
                    null
            );
            case "session" -> new CommandRunResult(
                    command,
                    "Rendered current session details.",
                    sessionPanel()
            );
            case "diff" -> new CommandRunResult(
                    command,
                    "Rendered workspace diff.",
                    diffPanel()
            );
            case "doctor" -> new CommandRunResult(
                    command,
                    "Rendered OpenClaude diagnostics.",
                    doctorPanel()
            );
            default -> throw new IllegalArgumentException("Unsupported backend command: /" + command.name());
        };
    }

    public void updateSettings(SettingsPatch patch) {
        OpenClaudeState state = stateStore.load();
        OpenClaudeSettings settings = state.settings();
        if (patch.fastMode() != null) {
            settings = settings.withFastMode(patch.fastMode());
        }
        if (patch.verboseOutput() != null) {
            settings = settings.withVerboseOutput(patch.verboseOutput());
        }
        if (patch.reasoningVisible() != null) {
            settings = settings.withReasoningVisible(patch.reasoningVisible());
        }
        if (patch.alwaysCopyFullResponse() != null) {
            settings = settings.withAlwaysCopyFullResponse(patch.alwaysCopyFullResponse());
        }
        if (patch.effortLevel() != null) {
            settings = settings.withEffortLevel(patch.effortLevel());
        }
        stateStore.setSettings(settings);
    }

    public PermissionEditorSnapshot permissionEditorSnapshot() {
        return buildPermissionEditorSnapshot(stateStore.load());
    }

    public PermissionEditorMutationResult permissionEditorMutate(PermissionEditorMutationRequest request) {
        Objects.requireNonNull(request, "request");
        String action = normalizeMutationAction(request.action());
        String source = request.source() == null || request.source().isBlank()
                ? ToolPermissionSources.SESSION
                : ToolPermissionSources.normalize(request.source());

        String message;
        switch (action) {
            case "add", "remove" -> {
                if (!ToolPermissionSources.isEditable(source)) {
                    throw new IllegalArgumentException("Permission rules in " + ToolPermissionSources.displayName(source) + " are read-only.");
                }
                String behavior = normalizePermissionBehavior(request.behavior());
                String rawRule = request.rule() == null ? "" : request.rule().trim();
                if (rawRule.isBlank()) {
                    throw new IllegalArgumentException("Permission rule is required.");
                }
                ToolPermissionRule rule = ToolPermissionRule.fromPermissionRuleString(source, behavior, rawRule);
                boolean updated = "add".equals(action)
                        ? permissionRulesStore.addRule(source, rule, sessionService.scopeWorkspaceRoot())
                        : permissionRulesStore.removeRule(source, rule, sessionService.scopeWorkspaceRoot());
                message = "add".equals(action)
                        ? (updated
                        ? "Added " + rule.behavior() + " rule " + rule.toRuleString() + " to " + ToolPermissionSources.displayName(source) + "."
                        : "Failed to add permission rule to " + ToolPermissionSources.displayName(source) + ".")
                        : (updated
                        ? "Removed " + rule.behavior() + " rule " + rule.toRuleString() + " from " + ToolPermissionSources.displayName(source) + "."
                        : "No matching permission rule was removed from " + ToolPermissionSources.displayName(source) + ".");
            }
            case "clear" -> {
                int cleared = permissionRulesStore.clearRules(source, sessionService.scopeWorkspaceRoot());
                message = cleared == 0
                        ? "No permission rules were cleared for " + ToolPermissionSources.displayName(source) + "."
                        : "Cleared " + cleared + " permission rule" + (cleared == 1 ? "" : "s") + " from " + ToolPermissionSources.displayName(source) + ".";
            }
            case "retry-denials" -> message = retryDeniedPermissionsMessage();
            default -> throw new IllegalArgumentException("Unsupported permission editor action: " + request.action());
        }

        return new PermissionEditorMutationResult(message, permissionEditorSnapshot());
    }

    private CommandRunResult executePermissionsCommand(CommandView command, String args) {
        String normalizedArgs = args == null ? "" : args.trim();
        if (normalizedArgs.isBlank() || "list".equalsIgnoreCase(normalizedArgs)) {
            return new CommandRunResult(
                    command,
                    "Rendered permission runtime summary.",
                    permissionsPanel()
            );
        }
        if ("retry-denials".equalsIgnoreCase(normalizedArgs)) {
            return retryDeniedPermissions(command);
        }
        String[] tokens = normalizedArgs.split("\\s+", 4);
        if (tokens.length >= 1 && ("clear".equalsIgnoreCase(tokens[0]) || "reset".equalsIgnoreCase(tokens[0]))) {
            String source = tokens.length >= 2 ? tokens[1] : ToolPermissionSources.SESSION;
            int cleared = permissionRulesStore.clearRules(source, sessionService.scopeWorkspaceRoot());
            return new CommandRunResult(
                    command,
                    cleared == 0
                            ? "No permission rules were cleared for " + ToolPermissionSources.displayName(source) + "."
                            : "Cleared " + cleared + " permission rule" + (cleared == 1 ? "" : "s") + " from " + ToolPermissionSources.displayName(source) + ".",
                    permissionsPanel()
            );
        }
        if (tokens.length >= 4 && "add".equalsIgnoreCase(tokens[0])) {
            return mutatePermissionRule(command, "add", tokens[1], tokens[2], tokens[3]);
        }
        if (tokens.length >= 4 && ("remove".equalsIgnoreCase(tokens[0]) || "delete".equalsIgnoreCase(tokens[0]))) {
            return mutatePermissionRule(command, "remove", tokens[1], tokens[2], tokens[3]);
        }
        throw new IllegalArgumentException("""
                Unsupported /permissions arguments.
                Use /permissions, /permissions list, /permissions clear [session|user|project|local],
                /permissions add <session|user|project|local> <allow|deny|ask> <Rule>,
                /permissions remove <session|user|project|local> <allow|deny|ask> <Rule>,
                or /permissions retry-denials.
                """.replaceAll("\\s+", " ").trim());
    }

    private CommandRunResult mutatePermissionRule(CommandView command, String action, String sourceToken, String behaviorToken, String rawRule) {
        PermissionEditorMutationResult result = permissionEditorMutate(new PermissionEditorMutationRequest(
                action,
                sourceToken,
                behaviorToken,
                rawRule
        ));
        return new CommandRunResult(command, result.message(), permissionsPanel());
    }

    private CommandRunResult retryDeniedPermissions(CommandView command) {
        PermissionEditorMutationResult result = permissionEditorMutate(new PermissionEditorMutationRequest(
                "retry-denials",
                null,
                null,
                null
        ));
        return new CommandRunResult(command, result.message(), permissionsPanel());
    }

    private String retryDeniedPermissionsMessage() {
        OpenClaudeState state = stateStore.load();
        if (state.activeSessionId() == null || state.activeSessionId().isBlank()) {
            return "No active session is available for retry-denials.";
        }

        List<PermissionActivity> deniedActivities = recentPermissionActivityEntries(state).stream()
                .filter(activity -> "denied".equals(activity.status()))
                .toList();
        if (deniedActivities.isEmpty()) {
            return "No denied permission requests were found in the active session.";
        }

        OpenClaudeState currentState = stateStore.load();
        List<ToolPermissionRule> currentRules = currentState.settings().permissionRules();
        ArrayList<ToolPermissionRule> nextRules = new ArrayList<>(currentRules);
        nextRules.removeIf(rule -> rule.denies()
                && ToolPermissionSources.SESSION.equals(ToolPermissionSources.normalize(rule.source()))
                && deniedActivities.stream().anyMatch(activity -> rule.toolName().equalsIgnoreCase(activity.toolName())
                && rule.displayTarget().equals(activity.detail())));
        if (nextRules.size() != currentRules.size()) {
            stateStore.setSettings(currentState.settings().withPermissionRules(nextRules));
        }

        ConversationSession session = sessionStore.loadOrCreate(state.activeSessionId());
        List<String> commands = deniedActivities.stream()
                .map(PermissionActivity::detail)
                .distinct()
                .limit(8)
                .toList();
        sessionStore.save(session.append(SessionMessage.system("Allowed " + String.join(", ", commands))));

        return "Recorded retry for " + commands.size() + " denied permission request" + (commands.size() == 1 ? "" : "s") + ". Ask again to continue.";
    }

    private PermissionEditorSnapshot buildPermissionEditorSnapshot(OpenClaudeState state) {
        List<PermissionEditorTab> tabs = List.of(
                buildRecentPermissionTab(state),
                buildBehaviorPermissionTab("allow", "Allow", "Claude Code won't ask before using allowed tools."),
                buildBehaviorPermissionTab("ask", "Ask", "Claude Code will always ask for confirmation before using these tools."),
                buildBehaviorPermissionTab("deny", "Deny", "Claude Code will always reject requests to use denied tools.")
        );

        return new PermissionEditorSnapshot(
                state.activeSessionId(),
                sessionService.scopeDisplayPath(),
                sessionService.scopeWorkspaceRoot() == null ? null : sessionService.scopeWorkspaceRoot().toString(),
                state.activeProvider() == null ? null : state.activeProvider().cliValue(),
                state.activeModelId(),
                tabs
        );
    }

    private PermissionEditorTab buildRecentPermissionTab(OpenClaudeState state) {
        List<PermissionActivityView> recentActivities = recentPermissionActivityEntries(state).stream()
                .map(PermissionActivityView::from)
                .toList();
        return new PermissionEditorTab(
                "recent",
                "Recently denied",
                "Commands recently denied by the auto mode classifier.",
                List.of(),
                recentActivities
        );
    }

    private PermissionEditorTab buildBehaviorPermissionTab(String id, String title, String description) {
        Path workspaceRoot = sessionService.scopeWorkspaceRoot();
        List<PermissionRuleSourceGroup> groups = ToolPermissionSources.precedence().stream()
                .map(source -> buildPermissionRuleSourceGroup(source, id, workspaceRoot))
                .toList();
        return new PermissionEditorTab(id, title, description, groups, List.of());
    }

    private PermissionRuleSourceGroup buildPermissionRuleSourceGroup(String source, String behavior, Path workspaceRoot) {
        List<PermissionRuleView> rules = permissionRulesStore.loadRulesForSource(source, workspaceRoot).stream()
                .filter(rule -> behavior.equals(rule.behavior()))
                .map(PermissionRuleView::from)
                .toList();
        return new PermissionRuleSourceGroup(
                source,
                ToolPermissionSources.displayName(source),
                ToolPermissionSources.isEditable(source),
                rules
        );
    }

    public SessionSummary sessionSummary() {
        OpenClaudeState state = stateStore.load();
        int contextWindowTokens = resolveContextWindowTokens(state);
        if (state.activeSessionId() == null || state.activeSessionId().isBlank()) {
            Instant now = Instant.now();
            return new SessionSummary(
                    null,
                    null,
                    now,
                    now,
                    0L,
                    0,
                    0,
                    0,
                    0,
                    contextWindowTokens,
                    0.0,
                    null,
                    null,
                    false,
                    List.of()
            );
        }

        ConversationSession session = sessionStore.loadOrCreate(state.activeSessionId());
        List<SessionMessage> activeMessages = SessionCompaction.messagesAfterCompactBoundary(session.messages());
        int userMessageCount = (int) activeMessages.stream()
                .filter(message -> message instanceof SessionMessage.UserMessage userMessage && !userMessage.compactSummary())
                .count();
        int assistantMessageCount = (int) activeMessages.stream().filter(SessionMessage.AssistantMessage.class::isInstance).count();
        int totalMessageCount = activeMessages.size();
        ContextDiagnostics contextDiagnostics = analyzeContext(state, session);

        return new SessionSummary(
                session.sessionId(),
                session.title(),
                session.createdAt(),
                session.updatedAt(),
                Math.max(0L, Duration.between(session.createdAt(), session.updatedAt()).toSeconds()),
                userMessageCount,
                assistantMessageCount,
                totalMessageCount,
                contextDiagnostics.estimatedTokens(),
                contextDiagnostics.contextWindowTokens(),
                0.0,
                session.workingDirectory(),
                session.workspaceRoot(),
                session.planMode(),
                session.todos()
        );
    }

    private String executeEffortCommand(String args) {
        OpenClaudeState state = stateStore.load();
        String normalizedArgs = args == null ? "" : args.trim().toLowerCase(Locale.ROOT);
        String currentEffortLevel = stateStore.currentEffortLevel(state.settings().effortLevel());
        if (normalizedArgs.isBlank()) {
            OpenClaudeEffort.EffortEnvironmentOverride environmentOverride = OpenClaudeEffort.environmentOverride(environmentLookup);
            String effective = environmentOverride.present()
                    ? environmentOverride.value()
                    : currentEffortLevel;
            if (effective == null) {
                return "Effort level: auto (currently "
                        + OpenClaudeEffort.displayedLevel(
                                state.activeProvider(),
                                state.activeModelId(),
                                currentEffortLevel,
                                environmentLookup
                        )
                        + ")";
            }
            return "Current effort level: " + effective + " (" + OpenClaudeEffort.description(effective) + ")";
        }

        if (!OpenClaudeEffort.isValidCommandArgument(normalizedArgs)) {
            return "Invalid argument: " + args.trim() + ". Valid options are: low, medium, high, max, auto";
        }

        String requested = OpenClaudeEffort.normalizeConfiguredValue(normalizedArgs);
        OpenClaudeEffort.EffortEnvironmentOverride environmentOverride = OpenClaudeEffort.environmentOverride(environmentLookup);
        if (requested == null) {
            stateStore.setSettings(state.settings().withEffortLevel(null));
            stateStore.clearSessionEffortLevel();
            if (environmentOverride.present() && environmentOverride.value() != null) {
                return "Cleared effort from settings, but " + OpenClaudeEffort.ENV_OVERRIDE_NAME + "="
                        + environmentOverride.rawValue() + " still controls this session";
            }
            return "Effort level set to auto";
        }

        String persistable = OpenClaudeEffort.persistableValue(requested);
        if (persistable != null) {
            stateStore.setSettings(state.settings().withEffortLevel(persistable));
        }
        stateStore.setSessionEffortLevel(requested);

        if (environmentOverride.present() && !Objects.equals(environmentOverride.value(), requested)) {
            if (persistable == null) {
                return "Not applied: " + OpenClaudeEffort.ENV_OVERRIDE_NAME + "=" + environmentOverride.rawValue()
                        + " overrides effort this session, and " + requested + " is session-only (nothing saved)";
            }
            return OpenClaudeEffort.ENV_OVERRIDE_NAME + "=" + environmentOverride.rawValue()
                    + " overrides this session — clear it and " + requested + " takes over";
        }

        String suffix = persistable == null ? " (this session only)" : "";
        return "Set effort level to " + requested + suffix + ": " + OpenClaudeEffort.description(requested);
    }

    private String displayEffort(OpenClaudeState state) {
        String currentEffortLevel = stateStore.currentEffortLevel(state.settings().effortLevel());
        if (currentEffortLevel == null) {
            return "auto";
        }
        String applied = OpenClaudeEffort.resolveForPrompt(
                state.activeProvider(),
                state.activeModelId(),
                currentEffortLevel,
                environmentLookup
        );
        if (applied == null) {
            return currentEffortLevel;
        }
        if (!currentEffortLevel.equals(applied)) {
            return currentEffortLevel + " (applied as " + applied + ")";
        }
        return currentEffortLevel;
    }

    private PanelView costPanel() {
        OpenClaudeState state = stateStore.load();
        SessionSummary summary = sessionSummary();
        return new PanelView(
                "cost",
                "Session Cost",
                "Session timing and text volume for the active conversation.",
                List.of(
                        new PanelSection("Summary", List.of(
                                "Session: " + (summary.sessionId() == null ? "none" : summary.sessionId()),
                                "Active provider: " + (state.activeProvider() == null ? "none" : state.activeProvider().cliValue()),
                                "Active model: " + (state.activeModelId() == null ? "default" : state.activeModelId()),
                                "Started: " + summary.startedAt(),
                                "Updated: " + summary.updatedAt(),
                                "Duration: " + formatDuration(Duration.ofSeconds(summary.durationSeconds())),
                                "User turns: " + summary.userMessageCount(),
                                "Assistant turns: " + summary.assistantMessageCount(),
                                "Messages: " + summary.totalMessageCount(),
                                "Estimated context: " + summary.estimatedContextTokens() + " tokens"
                        )),
                        new PanelSection("Billing", List.of(
                                "Monetary provider billing is not wired yet for OpenClaude's multi-provider engine.",
                                "This panel currently reports session timing and text volume only."
                        ))
                ),
                null
        );
    }

    private PanelView sessionPanel() {
        SessionService.SessionDetail current = sessionService.currentSession();
        return new PanelView(
                "session",
                "Session",
                "Current session information for this workspace.",
                List.of(
                        new PanelSection("Current session", List.of(
                                "Title: " + current.title(),
                                "Session id: " + current.sessionId(),
                                "Created: " + current.createdAt(),
                                "Updated: " + current.updatedAt(),
                                "Messages: " + current.messageCount(),
                                "Workspace root: " + nullSafe(current.workspaceRoot()),
                                "Working directory: " + nullSafe(current.workingDirectory())
                        )),
                        new PanelSection("Preview", List.of(
                                current.preview().isBlank() ? "No session preview yet." : current.preview()
                        )),
                        new PanelSection("Resume scope", List.of(
                                "Current scope path: " + sessionService.scopeDisplayPath(),
                                "Use /resume to switch to another session from this workspace."
                        ))
                ),
                null
        );
    }

    private PanelView statusPanel() {
        OpenClaudeState state = stateStore.load();
        SessionSummary summary = sessionSummary();
        List<ProviderPlugin> providers = providerRegistry.listExecutable();
        ProviderPlugin activeProvider = state.activeProvider() == null
                ? null
                : providerRegistry.findExecutable(state.activeProvider()).orElse(null);
        int toolCount = activeProvider != null && activeProvider.supportsTools()
                ? toolRuntime.toolDefinitions().size()
                : 0;

        List<String> providerLines = providers.isEmpty()
                ? List.of("No v0-ready providers connected.")
                : providers.stream()
                .map(provider -> {
                    ProviderConnectionState connectionState = state.get(provider.id());
                    boolean connected = connectionState != null;
                    boolean active = provider.id() == state.activeProvider();
                    String status = active ? "active" : connected ? "connected" : "available";
                    String auth = connectionState == null ? "not connected" : connectionState.authMethod().cliValue();
                    return "%s (%s) — %s via %s".formatted(
                            provider.displayName(),
                            provider.id().cliValue(),
                            status,
                            auth
                    );
                })
                .toList();

        List<String> toolLines = activeProvider == null
                ? List.of("No active provider selected.")
                : !activeProvider.supportsTools()
                ? List.of("Provider " + activeProvider.displayName() + " does not expose tools in OpenClaude.")
                : List.of(
                        "Tool runtime ready: yes",
                        "Tool count: " + toolCount,
                        "Primary tools: " + toolRuntime.toolDefinitions().stream()
                                .map(ProviderToolDefinition::name)
                                .limit(6)
                                .collect(Collectors.joining(", "))
                );

        return new PanelView(
                "status",
                "Status",
                "OpenClaude runtime status for the current workspace.",
                List.of(
                        new PanelSection("Overview", List.of(
                                "Version: " + OpenClaudeCommand.VERSION,
                                "Active provider: " + (activeProvider == null ? "none" : activeProvider.displayName()),
                                "Active model: " + (state.activeModelId() == null ? "<default>" : state.activeModelId()),
                                "Session: " + (summary.sessionId() == null ? "none" : summary.sessionId()),
                                "Workspace root: " + nullSafe(summary.workspaceRoot()),
                                "Fast mode: " + state.settings().fastMode(),
                                "Effort: " + displayEffort(state)
                        )),
                        new PanelSection("Providers", providerLines),
                        new PanelSection("Tools", toolLines),
                        new PanelSection("Environment", List.of(
                                "Java: " + System.getProperty("java.version"),
                                "Git workspace: " + isGitWorkspace(),
                                "Current directory: " + Path.of("").toAbsolutePath().normalize()
                        ))
                ),
                null
        );
    }

    private PanelView contextPanel() {
        OpenClaudeState state = stateStore.load();
        if (state.activeSessionId() == null || state.activeSessionId().isBlank()) {
            return new PanelView(
                    "context",
                    "Context",
                    "Provider-visible context for the active session.",
                    List.of(new PanelSection("Status", List.of(
                            "No active session is selected.",
                            "Send a prompt before checking context usage."
                    ))),
                    ContextUsageView.fromEstimate(0, resolveContextWindowTokens(state))
            );
        }

        ConversationSession session = sessionStore.loadOrCreate(state.activeSessionId());
        ContextDiagnostics diagnostics = analyzeContext(state, session);
        List<String> overviewLines = List.of(
                "Provider: " + diagnostics.providerDisplayName(),
                "Model: " + diagnostics.modelDisplayName(),
                "Session: " + session.sessionId(),
                "Workspace: " + nullSafe(session.workspaceRoot()),
                "Projected prompt messages: " + diagnostics.promptMessageCount(),
                "Provider-visible session messages: " + diagnostics.providerVisibleSessionMessageCount()
        );
        List<String> budgetLines = List.of(
                "Estimated input context: " + diagnostics.estimatedTokens() + " tokens",
                "Context window: " + diagnostics.contextWindowTokens() + " tokens",
                "Remaining headroom: " + Math.max(0, diagnostics.contextWindowTokens() - diagnostics.estimatedTokens()) + " tokens",
                "Usage: " + formatPercentage(diagnostics.estimatedTokens(), diagnostics.contextWindowTokens()),
                "Status: " + diagnostics.usage().status()
        );
        List<String> breakdownLines = List.of(
                "Base system prompt: " + diagnostics.baseSystemPromptTokens() + " tokens",
                "AGENTS instructions: " + diagnostics.instructionsTokens() + " tokens",
                "Conversation + retained tool context: " + diagnostics.conversationTokens() + " tokens",
                "System messages in provider view: " + diagnostics.systemMessageCount(),
                "Assistant messages in provider view: " + diagnostics.assistantPromptMessageCount(),
                "User/tool-result messages in provider view: " + diagnostics.userPromptMessageCount()
        );
        List<String> lifecycleLines = new ArrayList<>();
        lifecycleLines.add("Compaction boundaries in session: " + diagnostics.compactBoundaryCount());
        lifecycleLines.add("Compact summaries retained after boundary: " + diagnostics.activeCompactSummaryCount());
        lifecycleLines.add("Instruction files loaded: " + diagnostics.instructionsFileCount());
        lifecycleLines.add(diagnostics.sessionMemoryPresent()
                ? "Session memory file: present (" + diagnostics.sessionMemoryPath() + ", " + diagnostics.sessionMemoryBytes() + " bytes)"
                : "Session memory file: not created yet (" + diagnostics.sessionMemoryPath() + ")");

        return new PanelView(
                "context",
                "Context",
                "Provider-visible context for the active session.",
                List.of(
                        new PanelSection("Overview", overviewLines),
                        new PanelSection("Budget", budgetLines),
                        new PanelSection("Breakdown", breakdownLines),
                        new PanelSection("Lifecycle", lifecycleLines)
                ),
                diagnostics.usage()
        );
    }

    private PanelView toolsPanel() {
        OpenClaudeState state = stateStore.load();
        if (state.activeProvider() == null) {
            return new PanelView(
                    "tools",
                    "Tools",
                    "Tools available to the active model.",
                    List.of(new PanelSection("Status", List.of(
                            "No active provider is selected.",
                            "Connect a provider and choose a model before using /tools."
                    ))),
                    null
            );
        }

        ProviderPlugin providerPlugin = providerRegistry.find(state.activeProvider()).orElse(null);
        if (providerPlugin == null) {
            return new PanelView(
                    "tools",
                    "Tools",
                    "Tools available to the active model.",
                    List.of(new PanelSection("Status", List.of(
                            "Active provider is not registered: " + state.activeProvider().cliValue()
                    ))),
                    null
            );
        }

        if (!providerPlugin.supportsTools()) {
            return new PanelView(
                    "tools",
                    "Tools",
                    "Tools available to the active model.",
                    List.of(new PanelSection("Status", List.of(
                            "Provider " + providerPlugin.displayName() + " does not currently expose tool use in OpenClaude."
                    ))),
                    null
            );
        }

        List<ProviderToolDefinition> toolDefinitions = toolRuntime.toolDefinitions();
        if (toolDefinitions.isEmpty()) {
            return new PanelView(
                    "tools",
                    "Tools",
                    "Tools available to the active model.",
                    List.of(new PanelSection("Status", List.of(
                            "No tools are currently available."
                    ))),
                    null
            );
        }

        List<PanelSection> sections = new ArrayList<>();
        sections.add(new PanelSection("Summary", List.of(
                "Provider: " + providerPlugin.displayName(),
                "Model: " + (state.activeModelId() == null ? "default" : state.activeModelId()),
                "Tool count: " + toolDefinitions.size()
        )));
        sections.addAll(groupToolSections(toolDefinitions));

        return new PanelView(
                "tools",
                "Tools",
                "Allowed tools exposed to the active model.",
                sections,
                null
        );
    }

    private PanelView permissionsPanel() {
        OpenClaudeState state = stateStore.load();
        List<ToolPermissionRule> allRules = permissionRulesStore.loadRules(sessionService.scopeWorkspaceRoot());
        long allowRules = allRules.stream().filter(ToolPermissionRule::allows).count();
        long denyRules = allRules.stream().filter(ToolPermissionRule::denies).count();
        if (state.activeProvider() == null) {
            return new PanelView(
                    "permissions",
                    "Permissions",
                    "Current permission model and recent permission activity for the active workspace.",
                    List.of(new PanelSection("Status", List.of(
                            "No active provider is selected.",
                            "Connect a provider before inspecting permission-sensitive tools.",
                            "Persisted allow rules: " + allowRules,
                            "Persisted deny rules: " + denyRules
                    ))),
                    null
            );
        }

        ProviderPlugin providerPlugin = providerRegistry.find(state.activeProvider()).orElse(null);
        if (providerPlugin == null) {
            return new PanelView(
                    "permissions",
                    "Permissions",
                    "Current permission model and recent permission activity for the active workspace.",
                    List.of(new PanelSection("Status", List.of(
                            "Active provider is not registered: " + state.activeProvider().cliValue()
                    ))),
                    null
            );
        }

        List<ProviderToolDefinition> permissionTools = permissionBearingTools(toolRuntime.toolDefinitions());
        List<PanelSection> sections = new java.util.ArrayList<>();
        sections.add(new PanelSection("Overview", List.of(
                "Provider: " + providerPlugin.displayName(),
                "Model: " + (state.activeModelId() == null ? "default" : state.activeModelId()),
                "Workspace: " + sessionService.scopeDisplayPath(),
                "Permission-bearing tools: " + permissionTools.size(),
                "Persisted allow rules: " + allowRules,
                "Persisted deny rules: " + denyRules
        )));
        sections.add(new PanelSection("Runtime mode", List.of(
                "Claude-style permission sources are loaded in precedence order: user, project, local, managed, session.",
                "Session allow/deny decisions are reused before prompting and stored in current-session state.",
                "Use /permissions add <source> <allow|deny|ask> <Rule> to manage rules with Claude-style rule strings.",
                "Use /permissions retry-denials to clear recent session denies and record an Allowed ... retry marker.",
                "Alias: /allowed-tools"
        )));
        sections.add(new PanelSection("Shell policy", List.of(
                "Bash is currently restricted to single-line read-only commands.",
                "Redirection, shell control operators, multiline commands, and mutating git subcommands are blocked."
        )));

        if (permissionTools.isEmpty()) {
            sections.add(new PanelSection("Permission-sensitive tools", List.of(
                    "No permission-sensitive tools are currently exposed to the active model."
            )));
        } else {
            sections.add(new PanelSection("Summary", List.of(
                    "Permission-sensitive tool count: " + permissionTools.size()
            )));
            sections.addAll(groupToolSections(permissionTools));
        }

        for (String source : ToolPermissionSources.precedence().reversed()) {
            List<ToolPermissionRule> sourceRules = permissionRulesStore.loadRulesForSource(source, sessionService.scopeWorkspaceRoot());
            sections.add(new PanelSection(
                    ToolPermissionSources.displayName(source),
                    sourceRules.isEmpty()
                            ? List.of("No permission rules.")
                            : sourceRules.stream()
                                    .sorted(java.util.Comparator.comparing(ToolPermissionRule::createdAt).reversed())
                                    .limit(12)
                                    .map(CommandService::describePermissionRule)
                                    .toList()
            ));
        }

        List<String> recentActivity = recentPermissionActivityEntries(state).stream()
                .limit(8)
                .map(activity -> "%s — %s — %s".formatted(activity.toolName(), activity.status(), summarize(activity.detail(), 96)))
                .toList();
        sections.add(new PanelSection("Recent activity", recentActivity.isEmpty()
                ? List.of("No permission prompts in this session yet.")
                : recentActivity));

        return new PanelView(
                "permissions",
                "Permissions",
                "Current permission model and recent permission activity for the active workspace.",
                sections,
                null
        );
    }

    private PanelView usagePanel() {
        OpenClaudeState state = stateStore.load();
        if (state.activeProvider() == null) {
            return new PanelView(
                    "usage",
                    "Usage",
                    "Plan usage limits for the active provider/account.",
                    List.of(new PanelSection("Status", List.of(
                            "No active provider is selected.",
                            "Connect a provider before checking usage limits."
                    ))),
                    null
            );
        }

        ProviderConnectionState connectionState = state.get(state.activeProvider());
        ProviderPlugin providerPlugin = providerRegistry.find(state.activeProvider()).orElse(null);
        String providerName = providerPlugin == null ? state.activeProvider().cliValue() : providerPlugin.displayName();
        ContextDiagnostics contextDiagnostics = state.activeSessionId() == null || state.activeSessionId().isBlank()
                ? ContextDiagnostics.empty(providerName, state.activeModelId(), resolveContextWindowTokens(state))
                : analyzeContext(state, sessionStore.loadOrCreate(state.activeSessionId()));
        ProviderRuntimeDiagnostics runtimeDiagnostics = connectionState == null
                ? ProviderRuntimeDiagnostics.empty()
                : connectionState.diagnostics();
        ProviderLimitState limitState = runtimeDiagnostics.lastLimitState();

        List<String> limitLines = new ArrayList<>();
        limitLines.add("Current session context: " + contextDiagnostics.estimatedTokens() + "/" + contextDiagnostics.contextWindowTokens()
                + " estimated tokens (" + formatPercentage(contextDiagnostics.estimatedTokens(), contextDiagnostics.contextWindowTokens()) + ").");
        if (limitState == null) {
            limitLines.add("No rate-limit or policy-limit response has been recorded for this provider yet.");
        } else {
            limitLines.add("Last observed limit: " + limitState.kind() + " (HTTP " + limitState.statusCode() + ")");
            limitLines.add("Observed at: " + limitState.observedAt());
            if (limitState.resetAt() != null) {
                limitLines.add("Reset at: " + limitState.resetAt());
            }
            if (limitState.retryAfter() != null) {
                limitLines.add("Retry-After: " + limitState.retryAfter());
            }
            if (!limitState.message().isBlank()) {
                limitLines.add("Provider message: " + summarize(limitState.message(), 160));
            }
        }

        List<String> runtimeLines = new ArrayList<>();
        runtimeLines.add("Last successful prompt: " + formatInstant(runtimeDiagnostics.lastSuccessfulPromptAt()));
        runtimeLines.add("Last failure: " + formatFailureSummary(runtimeDiagnostics));

        return new PanelView(
                "usage",
                "Usage",
                "Plan usage limits for the active provider/account.",
                List.of(
                        new PanelSection("Overview", List.of(
                                "Provider: " + providerName,
                                "Auth: " + (connectionState == null ? "not connected" : connectionState.authMethod().cliValue()),
                                "Model: " + (state.activeModelId() == null ? "default" : state.activeModelId()),
                                "Context window: " + contextDiagnostics.contextWindowTokens() + " tokens"
                        )),
                        new PanelSection("Limits", limitLines),
                        new PanelSection("Runtime", runtimeLines)
                ),
                contextDiagnostics.usage()
        );
    }

    private PanelView statsPanel() {
        StatsSummary summary = statsSummary();
        if (summary.totalSessions() == 0) {
            return new PanelView(
                    "stats",
                    "Stats",
                    "OpenClaude usage statistics and activity.",
                    List.of(new PanelSection("Status", List.of(
                            "No stats available yet. Start using OpenClaude in this workspace or another one."
                    ))),
                    null
            );
        }

        List<String> providerLines = summary.assistantTurnsByProvider().isEmpty()
                ? List.of("No provider activity recorded yet.")
                : summary.assistantTurnsByProvider().entrySet().stream()
                .map(entry -> entry.getKey() + " — " + entry.getValue() + " assistant turns")
                .toList();
        List<String> modelLines = summary.assistantTurnsByModel().isEmpty()
                ? List.of("No model activity recorded yet.")
                : summary.assistantTurnsByModel().entrySet().stream()
                .map(entry -> entry.getKey() + " — " + entry.getValue() + " assistant turns")
                .toList();

        return new PanelView(
                "stats",
                "Stats",
                "OpenClaude usage statistics and activity.",
                List.of(
                        new PanelSection("Overview", List.of(
                                "Total sessions: " + summary.totalSessions(),
                                "Total messages: " + summary.totalMessages(),
                                "Active days: " + summary.activeDays(),
                                "First session: " + nullSafe(summary.firstSessionDate()),
                                "Last session: " + nullSafe(summary.lastSessionDate())
                        )),
                        new PanelSection("Activity", List.of(
                                "Longest session: " + summary.longestSessionLabel(),
                                "Longest session duration: " + formatDuration(Duration.ofMillis(summary.longestSessionDurationMs())),
                                "Peak activity day: " + nullSafe(summary.peakActivityDay()),
                                "Peak activity messages: " + summary.peakActivityMessages()
                        )),
                        new PanelSection("Providers", providerLines),
                        new PanelSection("Models", modelLines)
                ),
                null
        );
    }

    private PanelView diffPanel() {
        if (!isGitWorkspace()) {
            return new PanelView(
                    "diff",
                    "Workspace Diff",
                    "Git changes for the current working directory.",
                    List.of(new PanelSection("Status", List.of(
                            "No git repository detected for the current working directory.",
                            "Change into a git workspace and run /diff again."
                    ))),
                    null
            );
        }

        CommandOutput status = runCommand(List.of("git", "status", "--short"), 8);
        if (!status.succeeded()) {
            return new PanelView(
                    "diff",
                    "Workspace Diff",
                    "Git changes for the current working directory.",
                    List.of(new PanelSection("Error", status.renderLines())),
                    null
            );
        }

        if (status.stdout().isBlank()) {
            return new PanelView(
                    "diff",
                    "Workspace Diff",
                    "Git changes for the current working directory.",
                    List.of(new PanelSection("Status", List.of("Workspace is clean. No uncommitted changes."))),
                    null
            );
        }

        CommandOutput stat = runCommand(List.of("git", "diff", "--stat", "--no-ext-diff"), 8);
        CommandOutput diff = runCommand(List.of("git", "diff", "--no-ext-diff", "--unified=3"), 12);

        return new PanelView(
                "diff",
                "Workspace Diff",
                "Uncommitted workspace changes.",
                List.of(
                        new PanelSection("git status --short", trimLines(status.stdout())),
                        new PanelSection("git diff --stat", stat.succeeded() ? trimLines(stat.stdout()) : stat.renderLines()),
                        new PanelSection("git diff", diff.succeeded() ? trimLines(diff.stdout(), MAX_DIFF_LINES) : diff.renderLines())
                ),
                null
        );
    }

    private PanelView doctorPanel() {
        OpenClaudeState state = stateStore.load();
        Path configHome = OpenClaudePaths.configHome();
        Path stateFile = OpenClaudePaths.stateFile();
        Path sessionsDirectory = OpenClaudePaths.sessionsDirectory();
        Path sessionMemoryDirectory = OpenClaudePaths.sessionMemoryDirectory();
        ProviderPlugin activeProvider = state.activeProvider() == null
                ? null
                : providerRegistry.find(state.activeProvider()).orElse(null);
        ProviderConnectionState connectionState = state.activeProvider() == null ? null : state.get(state.activeProvider());
        ProviderRuntimeDiagnostics runtimeDiagnostics = connectionState == null
                ? ProviderRuntimeDiagnostics.empty()
                : connectionState.diagnostics();
        Integer contextWindowTokens = resolveContextWindowTokens(state);
        boolean activeModelSupported = activeProvider == null || state.activeModelId() == null
                || activeProvider.supportedModels().stream().anyMatch(model -> state.activeModelId().equals(model.id()));
        ConversationSession activeSession = state.activeSessionId() == null || state.activeSessionId().isBlank()
                ? null
                : sessionStore.loadOrCreate(state.activeSessionId());
        ContextDiagnostics contextDiagnostics = activeSession == null
                ? ContextDiagnostics.empty(activeProvider == null ? "none" : activeProvider.displayName(), state.activeModelId(), contextWindowTokens)
                : analyzeContext(state, activeSession);
        int loadedInstructionFiles = activeSession == null ? 0 : instructionsLoader.load(activeSession).size();
        List<String> checks = new ArrayList<>();
        checks.add(formatCheck("PASS", "Java runtime", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"));
        checks.add(formatCheck(ensureDirectory(configHome), "OpenClaude home", configHome.toAbsolutePath().toString()));
        checks.add(formatCheck(ensureParentDirectory(stateFile), "State file parent", stateFile.toAbsolutePath().toString()));
        checks.add(formatCheck(ensureDirectory(sessionsDirectory), "Session store", sessionsDirectory.toAbsolutePath().toString()));
        checks.add(formatCheck(ensureDirectory(sessionMemoryDirectory), "Session memory store", sessionMemoryDirectory.toAbsolutePath().toString()));
        checks.add(formatCheck(isGitInstalled(), "Git executable", gitVersion()));
        checks.add(formatCheck(isGitWorkspace(), "Workspace repository", Path.of("").toAbsolutePath().normalize().toString()));
        checks.add(formatCheck(!state.connections().isEmpty(), "Connected providers", describeConnections(state.connections())));
        checks.add(formatCheck(state.activeProvider() != null, "Active provider", state.activeProvider() == null ? "none" : state.activeProvider().cliValue()));
        checks.add(formatCheck(activeProvider != null || state.activeProvider() == null, "Provider registration", activeProvider == null && state.activeProvider() != null ? "missing plugin" : "ok"));
        checks.add(formatCheck(activeModelSupported, "Active model support", state.activeModelId() == null ? "default" : state.activeModelId()));
        checks.add(formatCheck(contextWindowTokens > 0, "Context window metadata", String.valueOf(contextWindowTokens)));
        checks.add(formatCheck(loadedInstructionFiles > 0 || activeSession == null, "AGENTS instructions", loadedInstructionFiles > 0 ? loadedInstructionFiles + " files loaded" : "no instruction files found"));
        checks.add(formatCheck(runtimeDiagnostics.lastFailureCategory() == null, "Provider runtime health", formatFailureSummary(runtimeDiagnostics)));
        checks.add(formatCheck(runtimeDiagnostics.lastLimitState() == null, "Rate/policy limits", formatLimitStateSummary(runtimeDiagnostics.lastLimitState())));

        return new PanelView(
                "doctor",
                "Doctor",
                "OpenClaude backend diagnostics.",
                List.of(
                        new PanelSection("Checks", checks),
                        new PanelSection("Provider runtime", List.of(
                                "Provider: " + (activeProvider == null ? "none" : activeProvider.displayName()),
                                "Auth: " + (connectionState == null ? "not connected" : connectionState.authMethod().cliValue()),
                                "Last successful prompt: " + formatInstant(runtimeDiagnostics.lastSuccessfulPromptAt()),
                                "Last failure: " + formatFailureSummary(runtimeDiagnostics),
                                "Last limit state: " + formatLimitStateSummary(runtimeDiagnostics.lastLimitState())
                        )),
                        new PanelSection("Context", List.of(
                                "Estimated provider-visible context: " + contextDiagnostics.estimatedTokens() + " tokens",
                                "Context window: " + contextDiagnostics.contextWindowTokens() + " tokens",
                                "Usage: " + formatPercentage(contextDiagnostics.estimatedTokens(), contextDiagnostics.contextWindowTokens()),
                                "Instructions loaded: " + contextDiagnostics.instructionsFileCount(),
                                "Session memory: " + (contextDiagnostics.sessionMemoryPresent()
                                        ? contextDiagnostics.sessionMemoryPath() + " (" + contextDiagnostics.sessionMemoryBytes() + " bytes)"
                                        : "not created yet")
                        )),
                        new PanelSection("Settings", List.of(
                                "Fast mode: " + state.settings().fastMode(),
                                "Verbose output: " + state.settings().verboseOutput(),
                                "Reasoning visible: " + state.settings().reasoningVisible(),
                                "Always copy full response: " + state.settings().alwaysCopyFullResponse(),
                                "Effort level: " + displayEffort(state)
                        ))
                ),
                null
        );
    }

    private static int estimateContextTokens(List<SessionMessage> messages) {
        int visibleChars = messages.stream()
                .filter(CommandService::isProviderVisible)
                .mapToInt(message -> message.text().length())
                .sum();
        return Math.max(0, (int) Math.ceil(visibleChars / 4.0));
    }

    private static boolean isProviderVisible(SessionMessage message) {
        return message instanceof SessionMessage.UserMessage
                || message instanceof SessionMessage.AssistantMessage
                || message instanceof SessionMessage.SystemMessage;
    }

    private boolean isGitWorkspace() {
        CommandOutput result = runCommand(List.of("git", "rev-parse", "--is-inside-work-tree"), 6);
        return result.succeeded() && result.stdout().trim().equals("true");
    }

    private boolean isGitInstalled() {
        return runCommand(List.of("git", "--version"), 6).succeeded();
    }

    private String gitVersion() {
        CommandOutput output = runCommand(List.of("git", "--version"), 6);
        if (!output.succeeded()) {
            return output.render();
        }
        return output.stdout().trim();
    }

    private static boolean ensureDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            return Files.isDirectory(directory) && Files.isWritable(directory);
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean ensureParentDirectory(Path file) {
        Path parent = file.toAbsolutePath().getParent();
        return parent != null && ensureDirectory(parent);
    }

    private static String describeConnections(Map<?, ProviderConnectionState> connections) {
        if (connections.isEmpty()) {
            return "none";
        }
        return connections.values().stream()
                .map(connection -> connection.providerId().cliValue() + " via " + connection.authMethod().cliValue())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static List<PanelSection> groupToolSections(List<ProviderToolDefinition> toolDefinitions) {
        LinkedHashMap<String, List<ProviderToolDefinition>> grouped = new LinkedHashMap<>();
        for (ProviderToolDefinition toolDefinition : toolDefinitions) {
            String groupName = toolGroupName(toolDefinition.name());
            grouped.computeIfAbsent(groupName, ignored -> new java.util.ArrayList<>()).add(toolDefinition);
        }

        return grouped.entrySet().stream()
                .map(entry -> new PanelSection(
                        entry.getKey(),
                        renderToolLines(entry.getKey(), entry.getValue())
                ))
                .toList();
    }

    private List<PermissionActivity> recentPermissionActivityEntries(OpenClaudeState state) {
        if (state.activeSessionId() == null || state.activeSessionId().isBlank()) {
            return List.of();
        }

        ConversationSession session = sessionStore.loadOrCreate(state.activeSessionId());
        LinkedHashMap<String, PermissionActivity> activityByToolUseId = new LinkedHashMap<>();
        for (SessionMessage message : session.messages()) {
            if (!(message instanceof SessionMessage.ToolInvocationMessage toolMessage)) {
                continue;
            }

            if (!toolMessage.permissionRequestId().isBlank()) {
                activityByToolUseId.put(
                        toolMessage.toolId(),
                        new PermissionActivity(
                                toolMessage.toolId(),
                                toolMessage.toolName(),
                                "pending",
                                permissionDetail(toolMessage),
                                toolMessage.createdAt()
                        )
                );
                continue;
            }

            PermissionActivity existing = activityByToolUseId.get(toolMessage.toolId());
            if (existing == null) {
                continue;
            }

            String nextStatus = switch (toolMessage.phase()) {
                case "progress", "completed" -> "allowed";
                case "failed" -> toolMessage.text().startsWith("Permission denied:") ? "denied" : "failed";
                default -> existing.status();
            };
            activityByToolUseId.put(
                    toolMessage.toolId(),
                    new PermissionActivity(
                            existing.toolUseId(),
                            existing.toolName(),
                            nextStatus,
                            existing.detail(),
                            existing.createdAt()
                    )
            );
        }

        List<PermissionActivity> activities = new java.util.ArrayList<>(activityByToolUseId.values());
        java.util.Collections.reverse(activities);
        return List.copyOf(activities);
    }

    private StatsSummary statsSummary() {
        List<ConversationSession> sessions = sessionStore.listSessions();
        if (sessions.isEmpty()) {
            return new StatsSummary(
                    0,
                    0,
                    0,
                    null,
                    null,
                    "none",
                    0L,
                    null,
                    0,
                    Map.of(),
                    Map.of()
            );
        }

        ZoneId zone = ZoneId.systemDefault();
        Map<LocalDate, Integer> messagesByDay = new LinkedHashMap<>();
        Map<String, Integer> assistantTurnsByProvider = new LinkedHashMap<>();
        Map<String, Integer> assistantTurnsByModel = new LinkedHashMap<>();

        int totalMessages = 0;
        ConversationSession longestSession = null;
        long longestSessionDurationMs = 0L;
        LocalDate firstSessionDate = null;
        LocalDate lastSessionDate = null;

        for (ConversationSession session : sessions) {
            totalMessages += session.messages().size();
            LocalDate createdDate = session.createdAt().atZone(zone).toLocalDate();
            LocalDate updatedDate = session.updatedAt().atZone(zone).toLocalDate();
            if (firstSessionDate == null || createdDate.isBefore(firstSessionDate)) {
                firstSessionDate = createdDate;
            }
            if (lastSessionDate == null || updatedDate.isAfter(lastSessionDate)) {
                lastSessionDate = updatedDate;
            }

            long sessionDurationMs = Math.max(0L, Duration.between(session.createdAt(), session.updatedAt()).toMillis());
            if (sessionDurationMs > longestSessionDurationMs) {
                longestSessionDurationMs = sessionDurationMs;
                longestSession = session;
            }

            for (SessionMessage message : session.messages()) {
                LocalDate messageDate = message.createdAt().atZone(zone).toLocalDate();
                messagesByDay.merge(messageDate, 1, Integer::sum);
                if (message instanceof SessionMessage.AssistantMessage assistantMessage) {
                    String providerName = assistantMessage.providerId() == null ? "unknown" : assistantMessage.providerId().cliValue();
                    String modelName = assistantMessage.modelId() == null || assistantMessage.modelId().isBlank()
                            ? "<default>"
                            : assistantMessage.modelId();
                    assistantTurnsByProvider.merge(providerName, 1, Integer::sum);
                    assistantTurnsByModel.merge(modelName, 1, Integer::sum);
                }
            }
        }

        Map.Entry<LocalDate, Integer> peakActivity = messagesByDay.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);

        return new StatsSummary(
                sessions.size(),
                totalMessages,
                messagesByDay.size(),
                firstSessionDate == null ? null : firstSessionDate.toString(),
                lastSessionDate == null ? null : lastSessionDate.toString(),
                longestSessionLabel(longestSession),
                longestSessionDurationMs,
                peakActivity == null ? null : peakActivity.getKey().toString(),
                peakActivity == null ? 0 : peakActivity.getValue(),
                assistantTurnsByProvider.entrySet().stream()
                        .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new)),
                assistantTurnsByModel.entrySet().stream()
                        .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new))
        );
    }

    private static String permissionDetail(SessionMessage.ToolInvocationMessage toolMessage) {
        if (toolMessage.command() != null && !toolMessage.command().isBlank()) {
            return toolMessage.command();
        }
        return toolMessage.text();
    }

    private static String longestSessionLabel(ConversationSession session) {
        if (session == null) {
            return "none";
        }
        if (session.title() != null && !session.title().isBlank()) {
            return session.title();
        }
        return session.sessionId();
    }

    private static List<ProviderToolDefinition> permissionBearingTools(List<ProviderToolDefinition> toolDefinitions) {
        return toolDefinitions.stream()
                .filter(toolDefinition -> PERMISSION_TOOL_NAMES.contains(normalizeToolKey(toolDefinition.name())))
                .toList();
    }

    private static String describePermissionRule(ToolPermissionRule rule) {
        return "%s %s".formatted(rule.behavior(), rule.toRuleString());
    }

    private static String normalizeToolKey(String toolName) {
        return toolName == null ? "" : toolName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static List<String> renderToolLines(String groupName, List<ProviderToolDefinition> toolDefinitions) {
        if (toolDefinitions.size() == 1 && toolDefinitions.getFirst().name().equals(groupName)) {
            ProviderToolDefinition tool = toolDefinitions.getFirst();
            return List.of(
                    tool.name() + " — " + tool.description()
            );
        }

        return toolDefinitions.stream()
                .map(tool -> {
                    String suffix = toolSubtoolName(groupName, tool.name());
                    return suffix == null
                            ? tool.name() + " — " + tool.description()
                            : suffix + " — " + tool.description();
                })
                .toList();
    }

    private static String toolGroupName(String toolName) {
        int separatorIndex = firstToolSeparator(toolName);
        return separatorIndex < 0 ? toolName : toolName.substring(0, separatorIndex);
    }

    private static String toolSubtoolName(String groupName, String toolName) {
        if (toolName.equals(groupName)) {
            return null;
        }
        if (toolName.startsWith(groupName + ".")) {
            return toolName.substring(groupName.length() + 1);
        }
        if (toolName.startsWith(groupName + "/")) {
            return toolName.substring(groupName.length() + 1);
        }
        if (toolName.startsWith(groupName + ":")) {
            return toolName.substring(groupName.length() + 1);
        }
        return toolName;
    }

    private static int firstToolSeparator(String toolName) {
        int dot = toolName.indexOf('.');
        int slash = toolName.indexOf('/');
        int colon = toolName.indexOf(':');
        int index = Integer.MAX_VALUE;
        if (dot >= 0) {
            index = Math.min(index, dot);
        }
        if (slash >= 0) {
            index = Math.min(index, slash);
        }
        if (colon >= 0) {
            index = Math.min(index, colon);
        }
        return index == Integer.MAX_VALUE ? -1 : index;
    }

    private static CommandView requireCommand(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("Command name is required.");
        }

        String normalizedValue = rawName.startsWith("/") ? rawName.substring(1) : rawName;
        final String normalized = normalizedValue.trim().toLowerCase(Locale.ROOT);
        return COMMANDS.stream()
                .filter(command -> command.name().equals(normalized) || command.aliases().contains(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown command: /" + normalized));
    }

    private static String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0L, duration.toSeconds());
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.ROOT, "%02dh %02dm %02ds", hours, minutes, seconds);
    }

    private static String formatCheck(boolean ok, String label, String detail) {
        return formatCheck(ok ? "PASS" : "WARN", label, detail);
    }

    private static String formatCheck(String status, String label, String detail) {
        return "[%s] %s: %s".formatted(status, label, detail);
    }

    private static List<String> trimLines(String text) {
        return trimLines(text, Integer.MAX_VALUE);
    }

    private static List<String> trimLines(String text, int maxLines) {
        if (text == null || text.isBlank()) {
            return List.of("(empty)");
        }

        List<String> lines = text.lines().map(String::stripTrailing).toList();
        if (lines.size() <= maxLines) {
            return lines;
        }

        return java.util.stream.Stream.concat(
                lines.subList(0, maxLines - 1).stream(),
                java.util.stream.Stream.of("… truncated …")
        ).toList();
    }

    private static String summarize(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength - 1) + "…";
    }

    private static String classifyContextStatus(int estimatedTokens, int contextWindowTokens) {
        if (contextWindowTokens <= 0) {
            return "healthy";
        }
        double usage = estimatedTokens / (double) contextWindowTokens;
        if (usage < 0.5) {
            return "healthy";
        }
        if (usage < 0.75) {
            return "warming";
        }
        if (usage < 0.9) {
            return "hot";
        }
        return "critical";
    }

    private ContextDiagnostics analyzeContext(OpenClaudeState state, ConversationSession session) {
        ProviderPlugin providerPlugin = resolveActiveProvider(state);
        String providerDisplayName = providerPlugin == null ? "none" : providerPlugin.displayName();
        String modelDisplayName = resolveModelDisplayName(state, providerPlugin);
        int contextWindowTokens = resolveContextWindowTokens(state);
        String instructionsPrompt = instructionsLoader.renderSystemPrompt(session);
        int instructionsFileCount = instructionsLoader.load(session).size();
        List<ProviderToolDefinition> availableTools = providerPlugin != null && providerPlugin.supportsTools()
                ? toolRuntime.toolDefinitions()
                : List.of();
        List<PromptMessage> promptMessages = QueryEngine.projectPromptMessagesForDiagnostics(
                session,
                availableTools,
                instructionsPrompt
        );
        int estimatedTokens = QueryEngine.estimatePromptTokensForDiagnostics(promptMessages);
        int baseSystemPromptTokens = QueryEngine.estimatePromptTokensForDiagnostics(
                List.of(new PromptMessage(PromptMessageRole.SYSTEM, QueryEngine.defaultSystemPromptForDiagnostics()))
        );
        int instructionsTokens = instructionsPrompt == null || instructionsPrompt.isBlank()
                ? 0
                : QueryEngine.estimatePromptTokensForDiagnostics(List.of(new PromptMessage(PromptMessageRole.SYSTEM, instructionsPrompt)));
        int systemMessageCount = (int) promptMessages.stream().filter(message -> message.role() == PromptMessageRole.SYSTEM).count();
        int assistantPromptMessageCount = (int) promptMessages.stream().filter(message -> message.role() == PromptMessageRole.ASSISTANT).count();
        int userPromptMessageCount = (int) promptMessages.stream().filter(message -> message.role() == PromptMessageRole.USER).count();
        List<SessionMessage> activeMessages = SessionCompaction.messagesAfterCompactBoundary(session.messages());
        int providerVisibleSessionMessageCount = (int) activeMessages.stream().filter(CommandService::isPromptProjectedSessionMessage).count();
        int compactBoundaryCount = (int) session.messages().stream().filter(SessionMessage.CompactBoundaryMessage.class::isInstance).count();
        int activeCompactSummaryCount = (int) activeMessages.stream()
                .filter(message -> message instanceof SessionMessage.UserMessage userMessage && userMessage.compactSummary())
                .count();
        Path sessionMemoryPath = OpenClaudePaths.sessionMemoryPath(session.sessionId());
        boolean sessionMemoryPresent = Files.isRegularFile(sessionMemoryPath);
        long sessionMemoryBytes = sessionMemoryPresent ? safeFileSize(sessionMemoryPath) : 0L;

        return new ContextDiagnostics(
                providerDisplayName,
                modelDisplayName,
                estimatedTokens,
                contextWindowTokens,
                promptMessages.size(),
                systemMessageCount,
                assistantPromptMessageCount,
                userPromptMessageCount,
                providerVisibleSessionMessageCount,
                baseSystemPromptTokens,
                instructionsTokens,
                Math.max(0, estimatedTokens - baseSystemPromptTokens - instructionsTokens),
                compactBoundaryCount,
                activeCompactSummaryCount,
                instructionsFileCount,
                sessionMemoryPresent,
                sessionMemoryPath.toAbsolutePath().normalize().toString(),
                sessionMemoryBytes,
                ContextUsageView.fromEstimate(estimatedTokens, contextWindowTokens)
        );
    }

    private ProviderPlugin resolveActiveProvider(OpenClaudeState state) {
        if (state.activeProvider() == null) {
            return null;
        }
        return providerRegistry.find(state.activeProvider()).orElse(null);
    }

    private int resolveContextWindowTokens(OpenClaudeState state) {
        ProviderPlugin providerPlugin = resolveActiveProvider(state);
        if (providerPlugin == null) {
            return DEFAULT_CONTEXT_WINDOW_TOKENS;
        }
        return providerPlugin.supportedModels().stream()
                .filter(model -> state.activeModelId() == null || state.activeModelId().equals(model.id()))
                .map(ModelDescriptor::contextWindowTokens)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(() -> providerPlugin.supportedModels().stream()
                        .map(ModelDescriptor::contextWindowTokens)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(DEFAULT_CONTEXT_WINDOW_TOKENS));
    }

    private String resolveModelDisplayName(OpenClaudeState state, ProviderPlugin providerPlugin) {
        if (providerPlugin == null) {
            return state.activeModelId() == null ? "default" : state.activeModelId();
        }
        if (state.activeModelId() == null) {
            return "default";
        }
        return providerPlugin.supportedModels().stream()
                .filter(model -> state.activeModelId().equals(model.id()))
                .map(ModelDescriptor::displayName)
                .findFirst()
                .orElse(state.activeModelId());
    }

    private static boolean isPromptProjectedSessionMessage(SessionMessage message) {
        return message instanceof SessionMessage.UserMessage
                || message instanceof SessionMessage.AssistantMessage
                || message instanceof SessionMessage.SystemMessage
                || message instanceof SessionMessage.AttachmentMessage
                || message instanceof SessionMessage.ToolResultMessage;
    }

    private static long safeFileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? "never" : instant.toString();
    }

    private static String formatPercentage(int numerator, int denominator) {
        if (denominator <= 0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.1f%%", (numerator * 100.0) / denominator);
    }

    private static String formatFailureSummary(ProviderRuntimeDiagnostics diagnostics) {
        if (diagnostics == null || diagnostics.lastFailureCategory() == null) {
            return "none recorded";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(diagnostics.lastFailureCategory());
        if (diagnostics.lastFailureAt() != null) {
            builder.append(" at ").append(diagnostics.lastFailureAt());
        }
        if (diagnostics.lastFailureMessage() != null && !diagnostics.lastFailureMessage().isBlank()) {
            builder.append(" — ").append(summarize(diagnostics.lastFailureMessage(), 160));
        }
        return builder.toString();
    }

    private static String formatLimitStateSummary(ProviderLimitState limitState) {
        if (limitState == null) {
            return "none recorded";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(limitState.kind()).append(" (HTTP ").append(limitState.statusCode()).append(")");
        if (limitState.resetAt() != null) {
            builder.append(", resets ").append(limitState.resetAt());
        }
        return builder.toString();
    }

    private static CommandOutput runCommand(List<String> command, long timeoutSeconds) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .directory(Path.of("").toAbsolutePath().normalize().toFile())
                    .redirectErrorStream(false)
                    .start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandOutput(command, -1, "", "Command timed out after %d seconds".formatted(timeoutSeconds));
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandOutput(command, process.exitValue(), stdout, stderr);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new CommandOutput(command, -1, "", exception.getMessage() == null ? exception.toString() : exception.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    public record CommandView(
            String name,
            String displayName,
            String description,
            List<String> aliases,
            String argumentHint,
            String type,
            String handler,
            boolean immediate,
            boolean enabled,
            boolean hidden
    ) {
    }

    public record CommandRunResult(
            CommandView command,
            String message,
            PanelView panel
    ) {
    }

    public record PanelView(
            String kind,
            String title,
            String subtitle,
            List<PanelSection> sections,
            ContextUsageView contextUsage
    ) {
    }

    public record PanelSection(
            String title,
            List<String> lines
    ) {
    }

    public record ContextUsageView(
            int estimatedTokens,
            int contextWindowTokens,
            int usedCells,
            int totalCells,
            String status
    ) {
        public static ContextUsageView fromEstimate(int estimatedTokens, int contextWindowTokens) {
            int usedCells = contextWindowTokens <= 0
                    ? 0
                    : Math.min(CONTEXT_BAR_CELLS, (int) Math.round((estimatedTokens / (double) contextWindowTokens) * CONTEXT_BAR_CELLS));
            return new ContextUsageView(
                    estimatedTokens,
                    contextWindowTokens,
                    usedCells,
                    CONTEXT_BAR_CELLS,
                    classifyContextStatus(estimatedTokens, contextWindowTokens)
            );
        }
    }

    private record ContextDiagnostics(
            String providerDisplayName,
            String modelDisplayName,
            int estimatedTokens,
            int contextWindowTokens,
            int promptMessageCount,
            int systemMessageCount,
            int assistantPromptMessageCount,
            int userPromptMessageCount,
            int providerVisibleSessionMessageCount,
            int baseSystemPromptTokens,
            int instructionsTokens,
            int conversationTokens,
            int compactBoundaryCount,
            int activeCompactSummaryCount,
            int instructionsFileCount,
            boolean sessionMemoryPresent,
            String sessionMemoryPath,
            long sessionMemoryBytes,
            ContextUsageView usage
    ) {
        private static ContextDiagnostics empty(String providerDisplayName, String modelDisplayName, int contextWindowTokens) {
            return new ContextDiagnostics(
                    providerDisplayName == null ? "none" : providerDisplayName,
                    modelDisplayName == null ? "default" : modelDisplayName,
                    0,
                    contextWindowTokens <= 0 ? DEFAULT_CONTEXT_WINDOW_TOKENS : contextWindowTokens,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    OpenClaudePaths.sessionMemoryDirectory().toAbsolutePath().normalize().toString(),
                    0L,
                    ContextUsageView.fromEstimate(0, contextWindowTokens <= 0 ? DEFAULT_CONTEXT_WINDOW_TOKENS : contextWindowTokens)
            );
        }
    }

    public record SettingsPatch(
            Boolean fastMode,
            Boolean verboseOutput,
            Boolean reasoningVisible,
            Boolean alwaysCopyFullResponse,
            String effortLevel
    ) {
    }

    public record SessionSummary(
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
            List<TodoItem> todos
    ) {
    }

    public record PermissionEditorSnapshot(
            String sessionId,
            String workspaceDisplayPath,
            String workspaceRoot,
            String activeProvider,
            String activeModel,
            List<PermissionEditorTab> tabs
    ) {
    }

    public record PermissionEditorTab(
            String id,
            String title,
            String description,
            List<PermissionRuleSourceGroup> sourceGroups,
            List<PermissionActivityView> recentActivities
    ) {
    }

    public record PermissionRuleSourceGroup(
            String source,
            String displayName,
            boolean editable,
            List<PermissionRuleView> rules
    ) {
    }

    public record PermissionRuleView(
            String source,
            String displayName,
            String behavior,
            String toolName,
            String ruleString,
            String summary,
            String createdAt,
            boolean editable
    ) {
        static PermissionRuleView from(ToolPermissionRule rule) {
            return new PermissionRuleView(
                    rule.source(),
                    ToolPermissionSources.displayName(rule.source()),
                    rule.behavior(),
                    rule.toolName(),
                    rule.toRuleString(),
                    describePermissionRule(rule),
                    rule.createdAt().toString(),
                    ToolPermissionSources.isEditable(rule.source())
            );
        }
    }

    public record PermissionActivityView(
            String toolUseId,
            String toolName,
            String status,
            String detail,
            String createdAt
    ) {
        static PermissionActivityView from(PermissionActivity activity) {
            return new PermissionActivityView(
                    activity.toolUseId(),
                    activity.toolName(),
                    activity.status(),
                    activity.detail(),
                    activity.createdAt().toString()
            );
        }
    }

    public record PermissionEditorMutationRequest(
            String action,
            String source,
            String behavior,
            String rule
    ) {
    }

    public record PermissionEditorMutationResult(
            String message,
            PermissionEditorSnapshot snapshot
    ) {
    }

    private record PermissionActivity(
            String toolUseId,
            String toolName,
            String status,
            String detail,
            Instant createdAt
    ) {
    }

    private record StatsSummary(
            int totalSessions,
            int totalMessages,
            int activeDays,
            String firstSessionDate,
            String lastSessionDate,
            String longestSessionLabel,
            long longestSessionDurationMs,
            String peakActivityDay,
            int peakActivityMessages,
            Map<String, Integer> assistantTurnsByProvider,
            Map<String, Integer> assistantTurnsByModel
    ) {
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    private static String normalizeMutationAction(String action) {
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "add", "remove", "clear", "retry-denials" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported permission editor action: " + action);
        };
    }

    private static String normalizePermissionBehavior(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "allow", "deny", "ask" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported permission behavior: " + value);
        };
    }

    private record CommandOutput(
            List<String> command,
            int exitCode,
            String stdout,
            String stderr
    ) {
        boolean succeeded() {
            return exitCode == 0;
        }

        String render() {
            return String.join(System.lineSeparator(), renderLines());
        }

        List<String> renderLines() {
            String commandLine = String.join(" ", command);
            String stdoutText = stdout == null || stdout.isBlank() ? "(empty)" : stdout.strip();
            String stderrText = stderr == null || stderr.isBlank() ? "(empty)" : stderr.strip();
            return List.of(
                    "Command: " + commandLine,
                    "Exit code: " + exitCode,
                    "Stdout:",
                    stdoutText,
                    "",
                    "Stderr:",
                    stderrText
            );
        }
    }
}
