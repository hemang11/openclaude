package com.openclaude.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaude.core.config.OpenClaudeEffort;
import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.provider.ProviderConnectionState;
import com.openclaude.core.provider.ProviderLimitState;
import com.openclaude.core.provider.ProviderRegistry;
import com.openclaude.core.provider.ProviderRuntimeDiagnostics;
import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.SessionMessage;
import com.openclaude.core.session.ConversationSessionStore;
import com.openclaude.core.tools.ToolExecutionRequest;
import com.openclaude.core.tools.ToolExecutionResult;
import com.openclaude.core.tools.ToolExecutionUpdate;
import com.openclaude.core.tools.ToolPermissionGateway;
import com.openclaude.core.tools.ToolPermissionRule;
import com.openclaude.core.tools.ToolPermissionSources;
import com.openclaude.core.tools.ToolRuntime;
import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderPlugin;
import com.openclaude.provider.spi.ProviderToolDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CommandServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void listCommandsIncludesMemoryFrontendCommand() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-memory-list");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        List<CommandService.CommandView> commands = commandService.listCommands();

        assertTrue(commands.stream().anyMatch(command ->
                command.name().equals("memory")
                        && command.handler().equals("frontend")
                        && command.argumentHint() == null));
        assertTrue(commands.stream().anyMatch(command ->
                command.name().equals("compact")
                        && command.handler().equals("backend")
                        && command.argumentHint() == null));
    }

    @Test
    void statusCommandRendersWorkspaceProviderAndToolStatus() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-status");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("gpt-5.3-codex");

        CommandService.CommandRunResult result = commandService.run("status", "");

        assertEquals("Rendered OpenClaude status.", result.message());
        assertEquals("Status", result.panel().title());
        assertEquals("Overview", result.panel().sections().get(0).title());
        assertTrue(result.panel().sections().get(0).lines().stream().anyMatch(line -> line.contains("Active provider: OpenAI")));
        assertEquals("Providers", result.panel().sections().get(1).title());
        assertTrue(result.panel().sections().get(1).lines().stream().anyMatch(line -> line.contains("active via api_key")));
        assertEquals("Tools", result.panel().sections().get(2).title());
        assertTrue(result.panel().sections().get(2).lines().stream().anyMatch(line -> line.contains("Tool count: 3")));
    }

    @Test
    void sessionCommandRendersCurrentWorkspaceSessionDetails() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-session");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        sessionStore.save(ConversationSession.create("session-1", tempDirectory.toString(), tempDirectory.toString())
                .append(SessionMessage.user("summarize the repo"))
                .withTitle("Repo summary"));
        stateStore.setActiveSession("session-1");

        CommandService.CommandRunResult result = commandService.run("session", "");

        assertEquals("Rendered current session details.", result.message());
        assertEquals("Session", result.panel().title());
        assertEquals("Current session", result.panel().sections().getFirst().title());
        assertTrue(result.panel().sections().getFirst().lines().stream().anyMatch(line -> line.contains("Title: Repo summary")));
        assertTrue(result.panel().sections().get(2).lines().stream().anyMatch(line -> line.contains("Use /resume to switch to another session")));
    }

    @Test
    void costCommandRendersSessionTimingAndEstimatedContext() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-cost");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        sessionStore.save(ConversationSession.create("session-1", tempDirectory.toString(), tempDirectory.toString())
                .append(SessionMessage.user("review the project structure"))
                .append(SessionMessage.assistant("Working on it.", ProviderId.OPENAI, "gpt-5.3-codex")));
        stateStore.setActiveSession("session-1");
        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:OPENAI_API_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("gpt-5.3-codex");

        CommandService.CommandRunResult result = commandService.run("cost", "");

        assertEquals("Rendered session cost summary.", result.message());
        assertEquals("Session Cost", result.panel().title());
        assertEquals("Summary", result.panel().sections().getFirst().title());
        assertTrue(result.panel().sections().getFirst().lines().stream().anyMatch(line -> line.contains("Active provider: openai")));
        assertTrue(result.panel().sections().getFirst().lines().stream().anyMatch(line -> line.contains("Estimated context:")));
        assertTrue(result.panel().sections().get(1).lines().stream().anyMatch(line -> line.contains("Monetary provider billing is not wired yet")));
    }

    @Test
    void diffCommandRendersWorkspaceDiffPanel() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-diff");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        CommandService.CommandRunResult result = commandService.run("diff", "");

        assertEquals("Rendered workspace diff.", result.message());
        assertEquals("Workspace Diff", result.panel().title());
        assertTrue(!result.panel().sections().isEmpty());
        assertTrue(!result.panel().sections().getFirst().title().isBlank());
    }

    @Test
    void toolsCommandGroupsSubtoolsUnderTheirParentTool() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-tools");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveModel("gpt-5.3-codex");

        CommandService.CommandRunResult result = commandService.run("tools", "");

        assertEquals("Rendered available tools.", result.message());
        assertEquals("Tools", result.panel().title());
        assertEquals("Summary", result.panel().sections().get(0).title());
        assertEquals("bash", result.panel().sections().get(1).title());
        assertEquals("fs", result.panel().sections().get(2).title());
        assertTrue(result.panel().sections().get(2).lines().contains("read — Read files from the current workspace."));
        assertTrue(result.panel().sections().get(2).lines().contains("search — Search the current workspace for matching content."));
    }

    @Test
    void effortCommandPersistsSupportedEffortLevel() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-effort");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("gpt-5.3-codex");

        CommandService.CommandRunResult result = commandService.run("effort", "high");

        assertEquals("Set effort level to high: Comprehensive implementation with extensive testing and documentation", result.message());
        assertEquals("high", stateStore.load().settings().effortLevel());
    }

    @Test
    void permissionsCommandSummarizesPermissionSensitiveToolsAndRecentActivity() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-permissions");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new PermissionAwareToolRuntime(),
                sessionService
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("gpt-5.3-codex");
        Files.createDirectories(tempDirectory.resolve(".openclaude"));
        Files.writeString(
                tempDirectory.resolve(".openclaude").resolve("settings.json"),
                """
                        {
                          "permissions": {
                            "allow": ["WebFetch(example.com)"]
                          }
                        }
                        """
        );
        stateStore.setSettings(stateStore.load().settings().withPermissionRules(List.of(
                new ToolPermissionRule(
                        "bash||||",
                        "bash",
                        "",
                        "",
                        "",
                        "allow",
                        Instant.parse("2026-04-02T00:10:00Z")
                ),
                new ToolPermissionRule(
                        "webfetch| |https://example.com| |".replace(" ", ""),
                        "WebFetch",
                        "",
                        "https://example.com",
                        "",
                        "deny",
                        Instant.parse("2026-04-02T00:11:00Z")
                )
        )));

        sessionStore.save(ConversationSession.create("session-1", tempDirectory.toString(), tempDirectory.toString())
                .append(SessionMessage.tool(
                        "tool-1",
                        "bash",
                        "permission_requested",
                        "Allow local bash command: ls -1 ~/Desktop",
                        "{\"command\":\"ls -1 ~/Desktop\"}",
                        "ls -1 ~/Desktop",
                        "perm-1",
                        false
                ))
                .append(SessionMessage.tool(
                        "tool-1",
                        "bash",
                        "completed",
                        "Command: ls -1 ~/Desktop\nExit code: 0",
                        "{\"command\":\"ls -1 ~/Desktop\"}",
                        "ls -1 ~/Desktop",
                        "",
                        false
                ))
                .append(SessionMessage.tool(
                        "tool-2",
                        "WebFetch",
                        "permission_requested",
                        "Allow fetching https://example.com",
                        "{\"url\":\"https://example.com\"}",
                        "https://example.com",
                        "perm-2",
                        false
                ))
                .append(SessionMessage.tool(
                        "tool-2",
                        "WebFetch",
                        "failed",
                        "Permission denied: Denied from OpenClaude UI.",
                        "{\"url\":\"https://example.com\"}",
                        "https://example.com",
                        "",
                        true
                )));
        stateStore.setActiveSession("session-1");

        CommandService.CommandRunResult result = commandService.run("permissions", "");

        assertEquals("Rendered permission runtime summary.", result.message());
        assertEquals("Permissions", result.panel().title());
        assertEquals("Overview", result.panel().sections().get(0).title());
        assertTrue(result.panel().sections().get(0).lines().stream().anyMatch(line -> line.contains("Permission-bearing tools: 4")));
        assertTrue(result.panel().sections().get(0).lines().stream().anyMatch(line -> line.contains("Persisted allow rules: 2")));
        assertTrue(result.panel().sections().get(0).lines().stream().anyMatch(line -> line.contains("Persisted deny rules: 1")));
        assertTrue(result.panel().sections().stream().anyMatch(section ->
                section.title().equals("Current session")
                        && section.lines().stream().anyMatch(line -> line.contains("allow bash"))));
        assertTrue(result.panel().sections().stream().anyMatch(section ->
                section.title().equals("Shared project settings")
                        && section.lines().stream().anyMatch(line -> line.contains("allow WebFetch(example.com)"))));
        assertEquals("Recent activity", result.panel().sections().getLast().title());
        assertTrue(result.panel().sections().getLast().lines().stream().anyMatch(line -> line.contains("bash — allowed — ls -1 ~/Desktop")));
        assertTrue(result.panel().sections().getLast().lines().stream().anyMatch(line -> line.contains("WebFetch — denied — https://example.com")));
    }

    @Test
    void permissionsClearRemovesPersistedRules() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-permissions-clear");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        stateStore.setSettings(stateStore.load().settings().withPermissionRules(List.of(
                new ToolPermissionRule("bash||||", "bash", "", "", "", "allow", Instant.parse("2026-04-02T00:10:00Z"))
        )));

        CommandService.CommandRunResult result = commandService.run("permissions", "clear");

        assertEquals("Cleared 1 permission rule from Current session.", result.message());
        assertTrue(stateStore.load().settings().permissionRules().isEmpty());
        assertEquals("Permissions", result.panel().title());
    }

    @Test
    void permissionsAddAndRemoveProjectRuleUseClaudeStyleRuleStrings() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-permissions-add-remove");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        CommandService.CommandRunResult addResult = commandService.run("permissions", "add project allow Bash(ls -1 ~/Desktop)");
        Path settingsFile = tempDirectory.resolve(".openclaude").resolve("settings.json");

        assertTrue(addResult.message().contains("Added allow rule Bash(ls -1 ~/Desktop)"));
        assertTrue(Files.exists(settingsFile));
        assertTrue(Files.readString(settingsFile).contains("\"allow\""));
        assertTrue(Files.readString(settingsFile).contains("Bash(ls -1 ~/Desktop)"));

        CommandService.CommandRunResult removeResult = commandService.run("permissions", "remove project allow Bash(ls -1 ~/Desktop)");

        assertTrue(removeResult.message().contains("Removed allow rule Bash(ls -1 ~/Desktop)"));
        assertTrue(objectMapper.readTree(settingsFile.toFile()).isEmpty());
    }

    @Test
    void permissionsRetryDenialsClearsSessionDenyRulesAndAppendsRetryMarker() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-permissions-retry");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        stateStore.setSettings(stateStore.load().settings().withPermissionRules(List.of(
                ToolPermissionRule.fromPermissionRuleString(ToolPermissionSources.SESSION, "deny", "bash(ls -1 ~/Desktop)")
        )));
        sessionStore.save(ConversationSession.create("session-1", tempDirectory.toString(), tempDirectory.toString())
                .append(SessionMessage.user("summarize the desktop"))
                .append(SessionMessage.tool(
                        "tool-1",
                        "bash",
                        "permission_requested",
                        "Allow local bash command: ls -1 ~/Desktop",
                        "{\"command\":\"ls -1 ~/Desktop\"}",
                        "ls -1 ~/Desktop",
                        "perm-1",
                        false
                ))
                .append(SessionMessage.tool(
                        "tool-1",
                        "bash",
                        "failed",
                        "Permission denied: Denied from OpenClaude UI.",
                        "{\"command\":\"ls -1 ~/Desktop\"}",
                        "ls -1 ~/Desktop",
                        "",
                        true
                )));
        stateStore.setActiveSession("session-1");

        CommandService.CommandRunResult result = commandService.run("permissions", "retry-denials");
        ConversationSession updatedSession = sessionStore.load("session-1");

        assertTrue(result.message().contains("Recorded retry for 1 denied permission request"));
        assertTrue(stateStore.load().settings().permissionRules().isEmpty());
        assertTrue(updatedSession.messages().getLast().text().contains("Allowed ls -1 ~/Desktop"));
    }

    @Test
    void permissionEditorSnapshotGroupsRulesByBehaviorAndSource() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-permissions-editor");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("gpt-5.3-codex");
        stateStore.setActiveSession("session-1");

        sessionStore.save(ConversationSession.create("session-1", tempDirectory.toString(), tempDirectory.toString())
                .append(SessionMessage.tool(
                        "tool-1",
                        "bash",
                        "permission_requested",
                        "Allow local bash command: ls -1 ~/Desktop",
                        "{\"command\":\"ls -1 ~/Desktop\"}",
                        "ls -1 ~/Desktop",
                        "perm-1",
                        false
                ))
                .append(SessionMessage.tool(
                        "tool-1",
                        "bash",
                        "failed",
                        "Permission denied: Denied from OpenClaude UI.",
                        "{\"command\":\"ls -1 ~/Desktop\"}",
                        "ls -1 ~/Desktop",
                        "",
                        true
                )));

        commandService.permissionEditorMutate(new CommandService.PermissionEditorMutationRequest(
                "add",
                "project",
                "allow",
                "Bash(ls -1 ~/Desktop)"
        ));
        commandService.permissionEditorMutate(new CommandService.PermissionEditorMutationRequest(
                "add",
                "local",
                "ask",
                "WebFetch(example.com)"
        ));

        CommandService.PermissionEditorSnapshot snapshot = commandService.permissionEditorSnapshot();

        assertEquals("session-1", snapshot.sessionId());
        assertEquals(tempDirectory.toAbsolutePath().normalize().toString(), snapshot.workspaceRoot());
        assertEquals(4, snapshot.tabs().size());

        CommandService.PermissionEditorTab allowTab = snapshot.tabs().stream()
                .filter(tab -> tab.id().equals("allow"))
                .findFirst()
                .orElseThrow();
        assertTrue(allowTab.sourceGroups().stream().anyMatch(group ->
                group.source().equals(ToolPermissionSources.PROJECT_SETTINGS)
                        && group.rules().stream().anyMatch(rule ->
                        rule.ruleString().equals("Bash(ls -1 ~/Desktop)")
                                && rule.behavior().equals("allow"))));

        CommandService.PermissionEditorTab askTab = snapshot.tabs().stream()
                .filter(tab -> tab.id().equals("ask"))
                .findFirst()
                .orElseThrow();
        assertTrue(askTab.sourceGroups().stream().anyMatch(group ->
                group.source().equals(ToolPermissionSources.LOCAL_SETTINGS)
                        && group.rules().stream().anyMatch(rule ->
                        rule.ruleString().equals("WebFetch(example.com)")
                                && rule.behavior().equals("ask"))));

        CommandService.PermissionEditorTab recentTab = snapshot.tabs().stream()
                .filter(tab -> tab.id().equals("recent"))
                .findFirst()
                .orElseThrow();
        assertTrue(recentTab.recentActivities().stream().anyMatch(activity ->
                activity.toolName().equals("bash")
                        && activity.status().equals("denied")
                        && activity.detail().contains("ls -1 ~/Desktop")));
    }

    @Test
    void permissionEditorMutateSupportsClearAndRetryDenials() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-permissions-editor-mutate");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        stateStore.setActiveSession("session-1");
        sessionStore.save(ConversationSession.create("session-1", tempDirectory.toString(), tempDirectory.toString())
                .append(SessionMessage.user("summarize the desktop"))
                .append(SessionMessage.tool(
                        "tool-1",
                        "bash",
                        "permission_requested",
                        "Allow local bash command: ls -1 ~/Desktop",
                        "{\"command\":\"ls -1 ~/Desktop\"}",
                        "ls -1 ~/Desktop",
                        "perm-1",
                        false
                ))
                .append(SessionMessage.tool(
                        "tool-1",
                        "bash",
                        "failed",
                        "Permission denied: Denied from OpenClaude UI.",
                        "{\"command\":\"ls -1 ~/Desktop\"}",
                        "ls -1 ~/Desktop",
                        "",
                        true
                )));

        CommandService.PermissionEditorMutationResult addResult = commandService.permissionEditorMutate(new CommandService.PermissionEditorMutationRequest(
                "add",
                "project",
                "allow",
                "Bash(ls -1 ~/Desktop)"
        ));
        Path settingsFile = tempDirectory.resolve(".openclaude").resolve("settings.json");
        assertTrue(addResult.message().contains("Added allow rule Bash(ls -1 ~/Desktop)"));
        assertTrue(Files.exists(settingsFile));

        CommandService.PermissionEditorMutationResult clearResult = commandService.permissionEditorMutate(new CommandService.PermissionEditorMutationRequest(
                "clear",
                "project",
                null,
                null
        ));
        assertTrue(clearResult.message().contains("Cleared 1 permission rule from Shared project settings."));
        assertTrue(objectMapper.readTree(settingsFile.toFile()).isEmpty());

        stateStore.setSettings(stateStore.load().settings().withPermissionRules(List.of(
                ToolPermissionRule.fromPermissionRuleString(ToolPermissionSources.SESSION, "deny", "bash(ls -1 ~/Desktop)")
        )));

        CommandService.PermissionEditorMutationResult retryResult = commandService.permissionEditorMutate(new CommandService.PermissionEditorMutationRequest(
                "retry-denials",
                null,
                null,
                null
        ));
        ConversationSession updatedSession = sessionStore.load("session-1");
        assertTrue(retryResult.message().contains("Recorded retry for 1 denied permission request"));
        assertTrue(stateStore.load().settings().permissionRules().isEmpty());
        assertTrue(updatedSession.messages().getLast().text().contains("Allowed ls -1 ~/Desktop"));
    }

    @Test
    void usageCommandRendersProviderAndCurrentSessionContext() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-usage");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.BROWSER_SSO,
                "openai/default",
                Instant.parse("2026-04-02T00:00:00Z"),
                new ProviderRuntimeDiagnostics(
                        Instant.parse("2026-04-02T00:05:00Z"),
                        Instant.parse("2026-04-02T00:06:00Z"),
                        "rate_limit",
                        "Rate limit exceeded",
                        new ProviderLimitState(
                                "rate_limit",
                                429,
                                "Rate limit exceeded",
                                Instant.parse("2026-04-02T00:06:00Z"),
                                Instant.parse("2026-04-02T00:16:00Z"),
                                "600"
                        )
                )
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("gpt-5.3-codex");
        stateStore.setActiveSession("session-1");
        sessionStore.save(ConversationSession.create("session-1", tempDirectory.toString(), tempDirectory.toString())
                .append(SessionMessage.user("summarize the workspace"))
                .append(SessionMessage.assistant("Working on it.", ProviderId.OPENAI, "gpt-5.3-codex")));

        CommandService.CommandRunResult result = commandService.run("usage", "");

        assertEquals("Rendered usage limits panel.", result.message());
        assertEquals("Usage", result.panel().title());
        assertEquals("Overview", result.panel().sections().getFirst().title());
        assertTrue(result.panel().sections().getFirst().lines().stream().anyMatch(line -> line.contains("Provider: OpenAI")));
        assertTrue(result.panel().sections().get(1).lines().stream().anyMatch(line -> line.contains("Current session context:")));
        assertTrue(result.panel().sections().get(1).lines().stream().anyMatch(line -> line.contains("Last observed limit: rate_limit")));
        assertTrue(result.panel().sections().get(2).lines().stream().anyMatch(line -> line.contains("Last successful prompt: 2026-04-02T00:05:00Z")));
    }

    @Test
    void contextCommandRendersProjectedContextBreakdown() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-context");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        Files.writeString(tempDirectory.resolve("AGENTS.md"), "Always keep responses concise.");
        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("gpt-5.3-codex");
        stateStore.setActiveSession("session-1");
        sessionStore.save(ConversationSession.create("session-1", tempDirectory.toString(), tempDirectory.toString())
                .append(SessionMessage.user("inspect the repository"))
                .append(SessionMessage.assistant("Working on it.", ProviderId.OPENAI, "gpt-5.3-codex")));

        CommandService.CommandRunResult result = commandService.run("context", "");

        assertEquals("Rendered context usage panel.", result.message());
        assertEquals("Context", result.panel().title());
        assertEquals("Overview", result.panel().sections().getFirst().title());
        assertTrue(result.panel().sections().getFirst().lines().stream().anyMatch(line -> line.contains("Projected prompt messages:")));
        assertTrue(result.panel().sections().get(1).lines().stream().anyMatch(line -> line.contains("Estimated input context:")));
        assertTrue(result.panel().sections().get(2).lines().stream().anyMatch(line -> line.contains("AGENTS instructions:")));
        assertTrue(result.panel().sections().get(3).lines().stream().anyMatch(line -> line.contains("Instruction files loaded: 1")));
        assertTrue(result.panel().contextUsage() != null);
    }

    @Test
    void doctorCommandIncludesProviderRuntimeAndContextDiagnostics() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-doctor");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        Files.writeString(tempDirectory.resolve("AGENTS.md"), "Use tests before claiming a fix.");
        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z"),
                new ProviderRuntimeDiagnostics(
                        Instant.parse("2026-04-02T00:05:00Z"),
                        Instant.parse("2026-04-02T00:06:00Z"),
                        "policy_limit",
                        "Usage limit reached",
                        new ProviderLimitState(
                                "policy_limit",
                                403,
                                "Usage limit reached",
                                Instant.parse("2026-04-02T00:06:00Z"),
                                null,
                                null
                        )
                )
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("gpt-5.3-codex");
        stateStore.setActiveSession("session-1");
        sessionStore.save(ConversationSession.create("session-1", tempDirectory.toString(), tempDirectory.toString())
                .append(SessionMessage.user("review the repo"))
                .append(SessionMessage.assistant("Done", ProviderId.OPENAI, "gpt-5.3-codex")));

        CommandService.CommandRunResult result = commandService.run("doctor", "");

        assertEquals("Rendered OpenClaude diagnostics.", result.message());
        assertEquals("Doctor", result.panel().title());
        assertTrue(result.panel().sections().stream().anyMatch(section ->
                section.title().equals("Provider runtime")
                        && section.lines().stream().anyMatch(line -> line.contains("Last failure: policy_limit"))));
        assertTrue(result.panel().sections().stream().anyMatch(section ->
                section.title().equals("Context")
                        && section.lines().stream().anyMatch(line -> line.contains("Estimated provider-visible context:"))));
    }

    @Test
    void effortCommandPersistsChosenEffortLevel() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-effort");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        CommandService.CommandRunResult setResult = commandService.run("effort", "high");
        CommandService.CommandRunResult showResult = commandService.run("effort", "");
        CommandService.CommandRunResult autoResult = commandService.run("effort", "auto");

        assertTrue(setResult.message().contains("Set effort level to high"));
        assertTrue(showResult.message().contains("Current effort level:"));
        assertEquals("Effort level set to auto", autoResult.message());
        assertEquals(null, stateStore.load().settings().effortLevel());
    }

    @Test
    void effortCommandTracksSessionOnlyMaxWithoutPersistingIt() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-effort-max");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        CommandService.CommandRunResult result = commandService.run("effort", "max");

        assertEquals("Set effort level to max (this session only): Maximum capability with deepest reasoning (Opus 4.6 only)", result.message());
        assertEquals(null, stateStore.load().settings().effortLevel());
        assertEquals("max", stateStore.currentEffortLevel(stateStore.load().settings().effortLevel()));
    }

    @Test
    void effortCommandReportsEnvironmentOverrideConflict() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-effort-env");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService,
                key -> OpenClaudeEffort.ENV_OVERRIDE_NAME.equals(key) ? "medium" : null
        );

        CommandService.CommandRunResult setResult = commandService.run("effort", "high");
        CommandService.CommandRunResult showResult = commandService.run("effort", "");
        CommandService.CommandRunResult autoResult = commandService.run("effort", "auto");

        assertEquals("CLAUDE_CODE_EFFORT_LEVEL=medium overrides this session — clear it and high takes over", setResult.message());
        assertEquals("Current effort level: medium (Balanced approach with standard implementation and testing)", showResult.message());
        assertEquals("Cleared effort from settings, but CLAUDE_CODE_EFFORT_LEVEL=medium still controls this session", autoResult.message());
        assertEquals(null, stateStore.load().settings().effortLevel());
    }

    @Test
    void statsCommandAggregatesSessionHistory() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-command-stats");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolsProviderPlugin()));
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(
                stateStore,
                sessionStore,
                providerRegistry,
                new GroupedToolRuntime(),
                sessionService
        );

        sessionStore.save(ConversationSession.create("session-1", tempDirectory.toString(), tempDirectory.toString())
                .append(SessionMessage.user("hello"))
                .append(SessionMessage.assistant("hi", ProviderId.OPENAI, "gpt-5.3-codex"))
                .withTitle("First session"));
        sessionStore.save(ConversationSession.create("session-2", tempDirectory.toString(), tempDirectory.toString())
                .append(SessionMessage.user("review repo"))
                .append(SessionMessage.assistant("done", ProviderId.ANTHROPIC, "claude-sonnet-4-5"))
                .append(SessionMessage.assistant("more", ProviderId.ANTHROPIC, "claude-sonnet-4-5")));

        CommandService.CommandRunResult result = commandService.run("stats", "");

        assertEquals("Rendered usage statistics panel.", result.message());
        assertEquals("Stats", result.panel().title());
        assertEquals("Overview", result.panel().sections().getFirst().title());
        assertTrue(result.panel().sections().getFirst().lines().stream().anyMatch(line -> line.contains("Total sessions: 2")));
        assertTrue(result.panel().sections().get(2).lines().stream().anyMatch(line -> line.contains("anthropic — 2 assistant turns")));
        assertTrue(result.panel().sections().get(3).lines().stream().anyMatch(line -> line.contains("claude-sonnet-4-5 — 2 assistant turns")));
    }

    private static final class ToolsProviderPlugin implements ProviderPlugin {
        @Override
        public ProviderId id() {
            return ProviderId.OPENAI;
        }

        @Override
        public String displayName() {
            return "OpenAI";
        }

        @Override
        public Set<AuthMethod> supportedAuthMethods() {
            return Set.of(AuthMethod.API_KEY);
        }

        @Override
        public List<ModelDescriptor> supportedModels() {
            return List.of(new ModelDescriptor("gpt-5.3-codex", "GPT-5.3 Codex", ProviderId.OPENAI, 400_000));
        }

        @Override
        public boolean supportsPromptExecution() {
            return true;
        }

        @Override
        public boolean supportsTools() {
            return true;
        }
    }

    private static final class GroupedToolRuntime implements ToolRuntime {
        @Override
        public List<ProviderToolDefinition> toolDefinitions() {
            return List.of(
                    new ProviderToolDefinition("bash", "Run a local shell command in the current workspace.", "{\"type\":\"object\"}"),
                    new ProviderToolDefinition("fs.read", "Read files from the current workspace.", "{\"type\":\"object\"}"),
                    new ProviderToolDefinition("fs.search", "Search the current workspace for matching content.", "{\"type\":\"object\"}")
            );
        }

        @Override
        public ToolExecutionResult execute(
                ToolExecutionRequest request,
                ToolPermissionGateway permissionGateway,
                Consumer<ToolExecutionUpdate> updateConsumer
        ) {
            throw new UnsupportedOperationException("Not needed for this test");
        }
    }

    private static final class PermissionAwareToolRuntime implements ToolRuntime {
        @Override
        public List<ProviderToolDefinition> toolDefinitions() {
            return List.of(
                    new ProviderToolDefinition("bash", "Run a local shell command in the current workspace.", "{\"type\":\"object\"}"),
                    new ProviderToolDefinition("Read", "Read a file from the local filesystem.", "{\"type\":\"object\"}"),
                    new ProviderToolDefinition("Edit", "Perform exact string replacements in files.", "{\"type\":\"object\"}"),
                    new ProviderToolDefinition("Write", "Write a file to the local filesystem.", "{\"type\":\"object\"}"),
                    new ProviderToolDefinition("WebFetch", "Fetch content from a URL and return readable page content.", "{\"type\":\"object\"}")
            );
        }

        @Override
        public ToolExecutionResult execute(
                ToolExecutionRequest request,
                ToolPermissionGateway permissionGateway,
                Consumer<ToolExecutionUpdate> updateConsumer
        ) {
            throw new UnsupportedOperationException("Not needed for this test");
        }
    }
}
