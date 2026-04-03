package com.openclaude.cli.stdio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openclaude.cli.service.CommandService;
import com.openclaude.cli.service.ModelService;
import com.openclaude.cli.service.ProviderService;
import com.openclaude.cli.service.SessionService;
import com.openclaude.core.provider.OpenClaudeState;
import com.openclaude.core.provider.ProviderConnectionState;
import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.provider.PromptRouter;
import com.openclaude.core.provider.ProviderRegistry;
import com.openclaude.core.session.ConversationSessionStore;
import com.openclaude.core.session.SessionMessage;
import com.openclaude.core.tools.DefaultToolRuntime;
import com.openclaude.provider.openai.OpenAiBrowserAuthService;
import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.PromptEvent;
import com.openclaude.provider.spi.PromptRequest;
import com.openclaude.provider.spi.PromptResult;
import com.openclaude.provider.spi.ReasoningDeltaEvent;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderHttpException;
import com.openclaude.provider.spi.ProviderPlugin;
import com.openclaude.provider.spi.TextDeltaEvent;
import com.openclaude.provider.spi.ToolCallEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.stream.StreamSupport;
import java.util.stream.Collectors;

class OpenClaudeStdioServerTest {
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @TempDir
    Path tempDir;

    @Test
    void handlesInitializeConnectAndPromptSubmit() throws Exception {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new FakeOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDir.resolve("sessions"));
        PromptRouter promptRouter = new PromptRouter(providerRegistry, stateStore, sessionStore);
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());
        ModelService modelService = new ModelService(providerRegistry, stateStore);
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDir);
        CommandService commandService = new CommandService(stateStore, sessionStore, providerRegistry, new DefaultToolRuntime(), sessionService);

        OpenClaudeStdioServer server = new OpenClaudeStdioServer(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        );

        String requests = String.join("\n",
                "{\"kind\":\"request\",\"id\":\"1\",\"method\":\"initialize\"}",
                "{\"kind\":\"request\",\"id\":\"2\",\"method\":\"provider.connect\",\"params\":{\"providerId\":\"openai\",\"authMethod\":\"api_key\",\"apiKeyEnv\":\"TEST_OPENAI_KEY\"}}",
                "{\"kind\":\"request\",\"id\":\"3\",\"method\":\"models.list\"}",
                "{\"kind\":\"request\",\"id\":\"4\",\"method\":\"prompt.submit\",\"params\":{\"text\":\"hello\"}}"
        ) + "\n";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        server.run(
                new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr
        );

        String[] lines = stdout.toString(StandardCharsets.UTF_8).trim().split("\\R");
        assertEquals(10, lines.length);
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));

        JsonNode initResponse = objectMapper.readTree(lines[0]);
        assertTrue(initResponse.get("ok").asBoolean());
        assertTrue(initResponse.get("result").get("providers").isArray());

        JsonNode connectResponse = objectMapper.readTree(lines[1]);
        assertTrue(connectResponse.get("ok").asBoolean());
        assertEquals("openai", connectResponse.get("result").get("snapshot").get("state").get("activeProvider").asText());

        JsonNode modelsResponse = objectMapper.readTree(lines[2]);
        assertTrue(modelsResponse.get("ok").asBoolean());
        assertEquals("test-model", modelsResponse.get("result").get("models").get(0).get("id").asText());

        JsonNode promptStarted = objectMapper.readTree(lines[3]);
        assertEquals("event", promptStarted.get("kind").asText());
        assertEquals("prompt.started", promptStarted.get("event").asText());

        JsonNode reasoningDelta = objectMapper.readTree(lines[4]);
        assertEquals("event", reasoningDelta.get("kind").asText());
        assertEquals("prompt.reasoning.delta", reasoningDelta.get("event").asText());
        assertEquals("planning", reasoningDelta.get("data").get("text").asText());

        JsonNode toolStarted = objectMapper.readTree(lines[5]);
        assertEquals("event", toolStarted.get("kind").asText());
        assertEquals("prompt.tool.started", toolStarted.get("event").asText());
        assertEquals("shell", toolStarted.get("data").get("toolName").asText());

        JsonNode toolDelta = objectMapper.readTree(lines[6]);
        assertEquals("event", toolDelta.get("kind").asText());
        assertEquals("prompt.tool.delta", toolDelta.get("event").asText());
        assertEquals("delta", toolDelta.get("data").get("phase").asText());

        JsonNode toolCompleted = objectMapper.readTree(lines[7]);
        assertEquals("event", toolCompleted.get("kind").asText());
        assertEquals("prompt.tool.completed", toolCompleted.get("event").asText());

        JsonNode promptDelta = objectMapper.readTree(lines[8]);
        assertEquals("event", promptDelta.get("kind").asText());
        assertEquals("prompt.delta", promptDelta.get("event").asText());
        assertEquals("pong", promptDelta.get("data").get("text").asText());

        JsonNode promptResponse = objectMapper.readTree(lines[9]);
        assertTrue(promptResponse.get("ok").asBoolean());
        assertEquals("pong", promptResponse.get("result").get("text").asText());
        assertEquals("openai", promptResponse.get("result").get("snapshot").get("state").get("activeProvider").asText());
        JsonNode snapshotMessages = promptResponse.get("result").get("snapshot").get("messages");
        assertTrue(containsKind(snapshotMessages, "thinking"));
        assertTrue(containsKind(snapshotMessages, "tool"));
        assertTrue(containsKind(snapshotMessages, "assistant"));
    }

    private static boolean containsKind(JsonNode messages, String kind) {
        return StreamSupport.stream(messages.spliterator(), false)
                .anyMatch(message -> kind.equals(message.get("kind").asText()));
    }

    @Test
    void handlesCommandsAndSettingsUpdates() throws Exception {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new FakeToolingOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state-commands.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDir.resolve("sessions-commands"));
        PromptRouter promptRouter = new PromptRouter(providerRegistry, stateStore, sessionStore);
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());
        ModelService modelService = new ModelService(providerRegistry, stateStore);
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDir);
        CommandService commandService = new CommandService(stateStore, sessionStore, providerRegistry, new DefaultToolRuntime(), sessionService);

        OpenClaudeStdioServer server = new OpenClaudeStdioServer(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        );

        String requests = String.join("\n",
                "{\"kind\":\"request\",\"id\":\"1\",\"method\":\"initialize\"}",
                "{\"kind\":\"request\",\"id\":\"2\",\"method\":\"commands.list\"}",
                "{\"kind\":\"request\",\"id\":\"3\",\"method\":\"settings.update\",\"params\":{\"fastMode\":true,\"verboseOutput\":true}}",
                "{\"kind\":\"request\",\"id\":\"4\",\"method\":\"command.run\",\"params\":{\"commandName\":\"doctor\"}}",
                "{\"kind\":\"request\",\"id\":\"5\",\"method\":\"provider.connect\",\"params\":{\"providerId\":\"openai\",\"authMethod\":\"api_key\",\"apiKeyEnv\":\"TEST_OPENAI_KEY\"}}",
                "{\"kind\":\"request\",\"id\":\"6\",\"method\":\"command.run\",\"params\":{\"commandName\":\"tools\"}}",
                "{\"kind\":\"request\",\"id\":\"7\",\"method\":\"command.run\",\"params\":{\"commandName\":\"permissions\"}}",
                "{\"kind\":\"request\",\"id\":\"8\",\"method\":\"command.run\",\"params\":{\"commandName\":\"usage\"}}",
                "{\"kind\":\"request\",\"id\":\"9\",\"method\":\"command.run\",\"params\":{\"commandName\":\"stats\"}}"
        ) + "\n";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        server.run(
                new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr
        );

        String[] lines = stdout.toString(StandardCharsets.UTF_8).trim().split("\\R");
        assertEquals(9, lines.length);
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));

        JsonNode commandsResponse = objectMapper.readTree(lines[1]);
        assertTrue(commandsResponse.get("ok").asBoolean());
        assertTrue(commandsResponse.get("result").get("commands").isArray());
        List<String> commandNames = StreamSupport.stream(
                commandsResponse.get("result").get("commands").spliterator(),
                false
        ).map(command -> command.get("name").asText()).collect(Collectors.toList());
        assertTrue(commandNames.contains("provider"));
        assertTrue(commandNames.contains("resume"));
        assertTrue(commandNames.contains("session"));
        assertTrue(commandNames.contains("rename"));
        assertTrue(commandNames.contains("login"));
        assertTrue(commandNames.contains("logout"));
        assertTrue(commandNames.contains("status"));
        assertTrue(commandNames.contains("clear"));
        assertTrue(commandNames.contains("plan"));
        assertTrue(commandNames.contains("rewind"));
        assertTrue(commandNames.contains("tasks"));
        assertTrue(commandNames.contains("permissions"));
        assertTrue(commandNames.contains("usage"));
        assertTrue(commandNames.contains("stats"));
        assertTrue(commandNames.contains("effort"));
        assertTrue(commandNames.contains("compact"));

        JsonNode settingsResponse = objectMapper.readTree(lines[2]);
        assertTrue(settingsResponse.get("ok").asBoolean());
        assertTrue(settingsResponse.get("result").get("snapshot").get("settings").get("fastMode").asBoolean());
        assertTrue(settingsResponse.get("result").get("snapshot").get("settings").get("verboseOutput").asBoolean());

        JsonNode doctorResponse = objectMapper.readTree(lines[3]);
        assertTrue(doctorResponse.get("ok").asBoolean());
        assertEquals("Rendered OpenClaude diagnostics.", doctorResponse.get("result").get("message").asText());
        assertEquals("Doctor", doctorResponse.get("result").get("panel").get("title").asText());
        assertTrue(doctorResponse.get("result").get("panel").get("sections").isArray());

        JsonNode toolsResponse = objectMapper.readTree(lines[5]);
        assertTrue(toolsResponse.get("ok").asBoolean());
        assertEquals("Rendered available tools.", toolsResponse.get("result").get("message").asText());
        assertEquals("Tools", toolsResponse.get("result").get("panel").get("title").asText());
        java.util.List<String> titles = StreamSupport.stream(
                toolsResponse.get("result").get("panel").get("sections").spliterator(),
                false
        ).map(section -> section.get("title").asText()).collect(Collectors.toList());
        assertTrue(titles.contains("bash"));
        assertTrue(titles.contains("Read"));
        assertTrue(titles.contains("TodoWrite"));
        assertTrue(titles.contains("WebSearch"));
        assertTrue(titles.contains("EnterPlanMode"));

        JsonNode permissionsResponse = objectMapper.readTree(lines[6]);
        assertTrue(permissionsResponse.get("ok").asBoolean());
        assertEquals("Rendered permission runtime summary.", permissionsResponse.get("result").get("message").asText());
        assertEquals("Permissions", permissionsResponse.get("result").get("panel").get("title").asText());

        JsonNode usageResponse = objectMapper.readTree(lines[7]);
        assertTrue(usageResponse.get("ok").asBoolean());
        assertEquals("Rendered usage limits panel.", usageResponse.get("result").get("message").asText());
        assertEquals("Usage", usageResponse.get("result").get("panel").get("title").asText());

        JsonNode statsResponse = objectMapper.readTree(lines[8]);
        assertTrue(statsResponse.get("ok").asBoolean());
        assertEquals("Rendered usage statistics panel.", statsResponse.get("result").get("message").asText());
        assertEquals("Stats", statsResponse.get("result").get("panel").get("title").asText());
    }

    @Test
    void commandRunContextReturnsBackendContextPanel() throws Exception {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new FakeToolingOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state-context.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDir.resolve("sessions-context"));
        PromptRouter promptRouter = new PromptRouter(providerRegistry, stateStore, sessionStore);
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());
        ModelService modelService = new ModelService(providerRegistry, stateStore);
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDir);
        CommandService commandService = new CommandService(stateStore, sessionStore, providerRegistry, new DefaultToolRuntime(), sessionService);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("test-model");
        stateStore.setActiveSession("session-1");
        sessionStore.save(ConversationSession.create("session-1", tempDir.toString(), tempDir.toString())
                .append(SessionMessage.user("inspect repo"))
                .append(SessionMessage.assistant("Working on it.", ProviderId.OPENAI, "test-model")));

        OpenClaudeStdioServer server = new OpenClaudeStdioServer(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        );

        String requests = "{\"kind\":\"request\",\"id\":\"1\",\"method\":\"command.run\",\"params\":{\"commandName\":\"context\"}}\n";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        server.run(
                new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr
        );

        JsonNode response = objectMapper.readTree(stdout.toString(StandardCharsets.UTF_8).trim());
        assertTrue(response.get("ok").asBoolean());
        assertEquals("Rendered context usage panel.", response.get("result").get("message").asText());
        assertEquals("Context", response.get("result").get("panel").get("title").asText());
        assertTrue(response.get("result").get("panel").get("contextUsage").isObject());
    }

    @Test
    void promptSubmitClassifiesRateLimitFailures() throws Exception {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new RateLimitedOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state-rate-limit.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDir.resolve("sessions-rate-limit"));
        PromptRouter promptRouter = new PromptRouter(providerRegistry, stateStore, sessionStore);
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());
        ModelService modelService = new ModelService(providerRegistry, stateStore);
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDir);
        CommandService commandService = new CommandService(stateStore, sessionStore, providerRegistry, new DefaultToolRuntime(), sessionService);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("test-model");

        OpenClaudeStdioServer server = new OpenClaudeStdioServer(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        );

        String requests = "{\"kind\":\"request\",\"id\":\"1\",\"method\":\"prompt.submit\",\"params\":{\"text\":\"hello\"}}\n";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        server.run(
                new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr
        );

        String[] lines = stdout.toString(StandardCharsets.UTF_8).trim().split("\\R");
        assertEquals(2, lines.length);
        JsonNode failureResponse = objectMapper.readTree(lines[1]);
        assertTrue(!failureResponse.get("ok").asBoolean());
        assertEquals("rate_limit", failureResponse.get("error").get("code").asText());
        assertTrue(failureResponse.get("error").get("message").asText().contains("Provider rate limit reached."));
    }

    @Test
    void exposesStructuredPermissionEditorSnapshotAndMutations() throws Exception {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new FakeToolingOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state-permissions-editor.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDir.resolve("sessions-permissions-editor"));
        PromptRouter promptRouter = new PromptRouter(providerRegistry, stateStore, sessionStore);
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());
        ModelService modelService = new ModelService(providerRegistry, stateStore);
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDir);
        CommandService commandService = new CommandService(stateStore, sessionStore, providerRegistry, new DefaultToolRuntime(), sessionService);

        stateStore.saveConnection(new com.openclaude.core.provider.ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                java.time.Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("gpt-5.3-codex");
        stateStore.setActiveSession("session-1");
        sessionStore.save(ConversationSession.create("session-1", tempDir.toString(), tempDir.toString())
                .append(com.openclaude.core.session.SessionMessage.tool(
                        "tool-1",
                        "bash",
                        "permission_requested",
                        "Allow local bash command: ls -1 ~/Desktop",
                        "{\"command\":\"ls -1 ~/Desktop\"}",
                        "ls -1 ~/Desktop",
                        "perm-1",
                        false
                ))
                .append(com.openclaude.core.session.SessionMessage.tool(
                        "tool-1",
                        "bash",
                        "failed",
                        "Permission denied: Denied from OpenClaude UI.",
                        "{\"command\":\"ls -1 ~/Desktop\"}",
                        "ls -1 ~/Desktop",
                        "",
                        true
                )));

        OpenClaudeStdioServer server = new OpenClaudeStdioServer(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        );

        String requests = String.join("\n",
                "{\"kind\":\"request\",\"id\":\"1\",\"method\":\"permissions.editor.snapshot\"}",
                "{\"kind\":\"request\",\"id\":\"2\",\"method\":\"permissions.editor.mutate\",\"params\":{\"action\":\"add\",\"source\":\"project\",\"behavior\":\"allow\",\"rule\":\"Bash(ls -1 ~/Desktop)\"}}",
                "{\"kind\":\"request\",\"id\":\"3\",\"method\":\"permissions.editor.snapshot\"}"
        ) + "\n";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        server.run(
                new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr
        );

        String[] lines = stdout.toString(StandardCharsets.UTF_8).trim().split("\\R");
        assertEquals(3, lines.length);
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));

        JsonNode firstSnapshot = objectMapper.readTree(lines[0]);
        assertTrue(firstSnapshot.get("ok").asBoolean());
        assertEquals("session-1", firstSnapshot.get("result").get("sessionId").asText());
        assertTrue(firstSnapshot.get("result").get("tabs").isArray());
        assertEquals("recent", firstSnapshot.get("result").get("tabs").get(0).get("id").asText());
        assertTrue(firstSnapshot.get("result").get("tabs").get(0).get("recentActivities").isArray());

        JsonNode mutateResponse = objectMapper.readTree(lines[1]);
        assertTrue(mutateResponse.get("ok").asBoolean());
        assertTrue(mutateResponse.get("result").get("message").asText().contains("Added allow rule Bash(ls -1 ~/Desktop)"));

        JsonNode secondSnapshot = objectMapper.readTree(lines[2]);
        assertTrue(secondSnapshot.get("ok").asBoolean());
        JsonNode allowTab = secondSnapshot.get("result").get("tabs").get(1);
        assertTrue(StreamSupport.stream(allowTab.get("sourceGroups").spliterator(), false)
                .anyMatch(group -> "projectSettings".equals(group.get("source").asText())
                        && StreamSupport.stream(group.get("rules").spliterator(), false)
                        .anyMatch(rule -> "Bash(ls -1 ~/Desktop)".equals(rule.get("ruleString").asText()))));
    }

    @Test
    void handlesSessionListingResumeRenameAndPlanMode() throws Exception {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new FakeToolingOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state-sessions.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDir.resolve("sessions-sessions"));
        PromptRouter promptRouter = new PromptRouter(providerRegistry, stateStore, sessionStore);
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());
        ModelService modelService = new ModelService(providerRegistry, stateStore);
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDir);
        CommandService commandService = new CommandService(stateStore, sessionStore, providerRegistry, new DefaultToolRuntime(), sessionService);

        sessionStore.save(ConversationSession.create("active-session", tempDir.toString(), tempDir.toString()).withTitle("Current session"));
        sessionStore.save(ConversationSession.create("other-session", tempDir.toString(), tempDir.toString()).append(
                com.openclaude.core.session.SessionMessage.user("summarize desktop")
        ).withTitle("Desktop summary"));
        stateStore.setActiveSession("active-session");

        OpenClaudeStdioServer server = new OpenClaudeStdioServer(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        );

        String requests = String.join("\n",
                "{\"kind\":\"request\",\"id\":\"1\",\"method\":\"sessions.list\"}",
                "{\"kind\":\"request\",\"id\":\"2\",\"method\":\"sessions.resume\",\"params\":{\"sessionId\":\"other-session\"}}",
                "{\"kind\":\"request\",\"id\":\"3\",\"method\":\"sessions.rename\",\"params\":{\"title\":\"Renamed session\"}}",
                "{\"kind\":\"request\",\"id\":\"4\",\"method\":\"sessions.plan_mode\",\"params\":{\"enabled\":true}}"
        ) + "\n";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        server.run(
                new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr
        );

        String[] lines = stdout.toString(StandardCharsets.UTF_8).trim().split("\\R");
        assertEquals(4, lines.length);
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));

        JsonNode sessionsResponse = objectMapper.readTree(lines[0]);
        assertTrue(sessionsResponse.get("ok").asBoolean());
        assertEquals(1, sessionsResponse.get("result").get("sessions").size());
        assertEquals("other-session", sessionsResponse.get("result").get("sessions").get(0).get("sessionId").asText());

        JsonNode resumeResponse = objectMapper.readTree(lines[1]);
        assertTrue(resumeResponse.get("ok").asBoolean());
        assertEquals("other-session", resumeResponse.get("result").get("snapshot").get("state").get("activeSessionId").asText());

        JsonNode renameResponse = objectMapper.readTree(lines[2]);
        assertTrue(renameResponse.get("ok").asBoolean());
        assertEquals("Renamed session", renameResponse.get("result").get("snapshot").get("session").get("title").asText());

        JsonNode planModeResponse = objectMapper.readTree(lines[3]);
        assertTrue(planModeResponse.get("ok").asBoolean());
        assertTrue(planModeResponse.get("result").get("snapshot").get("session").get("planMode").asBoolean());
    }

    @Test
    void handlesSessionRewind() throws Exception {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new FakeToolingOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state-rewind.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDir.resolve("sessions-rewind"));
        PromptRouter promptRouter = new PromptRouter(providerRegistry, stateStore, sessionStore);
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());
        ModelService modelService = new ModelService(providerRegistry, stateStore);
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDir);
        CommandService commandService = new CommandService(stateStore, sessionStore, providerRegistry, new DefaultToolRuntime(), sessionService);

        ConversationSession session = ConversationSession.create("rewind-session", tempDir.toString(), tempDir.toString())
                .append(new com.openclaude.core.session.SessionMessage.UserMessage("user-1", java.time.Instant.parse("2026-04-02T00:00:00Z"), "first"))
                .append(new com.openclaude.core.session.SessionMessage.AssistantMessage("assistant-1", java.time.Instant.parse("2026-04-02T00:00:01Z"), "first answer", ProviderId.OPENAI, "gpt-5.3-codex", List.of()))
                .append(new com.openclaude.core.session.SessionMessage.UserMessage("user-2", java.time.Instant.parse("2026-04-02T00:00:02Z"), "second"))
                .append(new com.openclaude.core.session.SessionMessage.AssistantMessage("assistant-2", java.time.Instant.parse("2026-04-02T00:00:03Z"), "second answer", ProviderId.OPENAI, "gpt-5.3-codex", List.of()));
        sessionStore.save(session);
        stateStore.setActiveSession("rewind-session");

        OpenClaudeStdioServer server = new OpenClaudeStdioServer(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        );

        String requests = """
                {"kind":"request","id":"1","method":"sessions.rewind","params":{"messageId":"user-2"}}
                """;

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        server.run(
                new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr
        );

        String[] lines = stdout.toString(StandardCharsets.UTF_8).trim().split("\\R");
        assertEquals(1, lines.length);
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));

        JsonNode rewindResponse = objectMapper.readTree(lines[0]);
        assertTrue(rewindResponse.get("ok").asBoolean());
        assertEquals("Rewound conversation to the selected checkpoint.", rewindResponse.get("result").get("message").asText());
        assertEquals(2, rewindResponse.get("result").get("snapshot").get("messages").size());
        assertEquals("first answer", rewindResponse.get("result").get("snapshot").get("messages").get(1).get("text").asText());
    }

    @Test
    void handlesProviderDisconnect() throws Exception {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new FakeToolingOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state-disconnect.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDir.resolve("sessions-disconnect"));
        PromptRouter promptRouter = new PromptRouter(providerRegistry, stateStore, sessionStore);
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());
        ModelService modelService = new ModelService(providerRegistry, stateStore);
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDir);
        CommandService commandService = new CommandService(stateStore, sessionStore, providerRegistry, new DefaultToolRuntime(), sessionService);

        OpenClaudeStdioServer server = new OpenClaudeStdioServer(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        );

        String requests = String.join("\n",
                "{\"kind\":\"request\",\"id\":\"1\",\"method\":\"provider.connect\",\"params\":{\"providerId\":\"openai\",\"authMethod\":\"api_key\",\"apiKeyEnv\":\"TEST_OPENAI_KEY\"}}",
                "{\"kind\":\"request\",\"id\":\"2\",\"method\":\"provider.disconnect\",\"params\":{\"providerId\":\"openai\"}}"
        ) + "\n";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        server.run(
                new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr
        );

        String[] lines = stdout.toString(StandardCharsets.UTF_8).trim().split("\\R");
        assertEquals(2, lines.length);
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));

        JsonNode disconnectResponse = objectMapper.readTree(lines[1]);
        assertTrue(disconnectResponse.get("ok").asBoolean());
        assertEquals("Disconnected openai", disconnectResponse.get("result").get("message").asText());
        JsonNode state = disconnectResponse.get("result").get("snapshot").get("state");
        assertTrue(!state.has("activeProvider") || state.get("activeProvider").isNull());
        assertTrue(!state.has("activeModelId") || state.get("activeModelId").isNull());
    }

    @Test
    void initializeIncludesExplicitAssistantAndSiblingToolMetadataInSnapshot() throws Exception {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new FakeToolingOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state-tool-metadata.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDir.resolve("sessions-tool-metadata"));
        PromptRouter promptRouter = new PromptRouter(providerRegistry, stateStore, sessionStore);
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());
        ModelService modelService = new ModelService(providerRegistry, stateStore);
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDir);
        CommandService commandService = new CommandService(stateStore, sessionStore, providerRegistry, new DefaultToolRuntime(), sessionService);

        ConversationSession session = sessionStore.loadOrCreate("tool-metadata-session")
                .append(new com.openclaude.core.session.SessionMessage.UserMessage(
                        "user-1",
                        java.time.Instant.parse("2026-04-02T00:00:00Z"),
                        "Inspect files"
                ))
                .append(new com.openclaude.core.session.SessionMessage.AssistantMessage(
                        "assistant-1",
                        java.time.Instant.parse("2026-04-02T00:00:01Z"),
                        "",
                        ProviderId.OPENAI,
                        "gpt-5.3-codex",
                        List.of(
                                new com.openclaude.provider.spi.ToolUseContentBlock("tool-a", "bash", "{\"command\":\"pwd\"}"),
                                new com.openclaude.provider.spi.ToolUseContentBlock("tool-b", "bash", "{\"command\":\"ls\"}")
                        )
                ))
                .append(new com.openclaude.core.session.SessionMessage.ToolResultMessage(
                        "tool-result-b",
                        java.time.Instant.parse("2026-04-02T00:00:02Z"),
                        "tool-b",
                        "bash",
                        "Updated /tmp/demo.txt (12 chars).",
                        false,
                        "--- /tmp/demo.txt\n+++ /tmp/demo.txt\n@@ -1 +1 @@\n-old\n+new"
                ));
        sessionStore.save(session);
        stateStore.setActiveSession("tool-metadata-session");

        OpenClaudeStdioServer server = new OpenClaudeStdioServer(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        );

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        server.run(
                new ByteArrayInputStream("{\"kind\":\"request\",\"id\":\"1\",\"method\":\"initialize\"}\n".getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr
        );

        JsonNode initResponse = objectMapper.readTree(stdout.toString(StandardCharsets.UTF_8).trim());
        assertTrue(initResponse.get("ok").asBoolean());
        JsonNode messages = initResponse.get("result").get("messages");
        JsonNode toolUseA = StreamSupport.stream(messages.spliterator(), false)
                .filter(message -> "tool_use".equals(message.get("kind").asText()) && "tool-a".equals(message.get("toolId").asText()))
                .findFirst()
                .orElseThrow();
        JsonNode toolResultB = StreamSupport.stream(messages.spliterator(), false)
                .filter(message -> "tool_result".equals(message.get("kind").asText()) && "tool-b".equals(message.get("toolId").asText()))
                .findFirst()
                .orElseThrow();

        assertEquals("assistant-1", toolUseA.get("assistantMessageId").asText());
        assertNotNull(toolUseA.get("siblingToolIds"));
        assertEquals(List.of("tool-a", "tool-b"), StreamSupport.stream(toolUseA.get("siblingToolIds").spliterator(), false)
                .map(JsonNode::asText)
                .toList());
        assertEquals("assistant-1", toolResultB.get("assistantMessageId").asText());
        assertEquals(List.of("tool-a", "tool-b"), StreamSupport.stream(toolResultB.get("siblingToolIds").spliterator(), false)
                .map(JsonNode::asText)
                .toList());
        assertEquals("--- /tmp/demo.txt\n+++ /tmp/demo.txt\n@@ -1 +1 @@\n-old\n+new", toolResultB.get("displayText").asText());
    }

    @Test
    void initializeIncludesAttachmentKindInSnapshotMessages() throws Exception {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new FakeToolingOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state-attachment-kind.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDir.resolve("sessions-attachment-kind"));
        PromptRouter promptRouter = new PromptRouter(providerRegistry, stateStore, sessionStore);
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());
        ModelService modelService = new ModelService(providerRegistry, stateStore);
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDir);
        CommandService commandService = new CommandService(stateStore, sessionStore, providerRegistry, new DefaultToolRuntime(), sessionService);

        ConversationSession session = sessionStore.loadOrCreate("attachment-kind-session")
                .append(com.openclaude.core.session.SessionMessage.attachment(
                        new com.openclaude.core.session.SessionAttachment.HookAdditionalContextAttachment(
                                "SessionStart",
                                "echo hi",
                                "hook context"
                        )
                ));
        sessionStore.save(session);
        stateStore.setActiveSession("attachment-kind-session");

        OpenClaudeStdioServer server = new OpenClaudeStdioServer(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        );

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        server.run(
                new ByteArrayInputStream("{\"kind\":\"request\",\"id\":\"1\",\"method\":\"initialize\"}\n".getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr
        );

        JsonNode initResponse = objectMapper.readTree(stdout.toString(StandardCharsets.UTF_8).trim());
        assertTrue(initResponse.get("ok").asBoolean());
        JsonNode attachment = StreamSupport.stream(initResponse.get("result").get("messages").spliterator(), false)
                .filter(message -> "attachment".equals(message.get("kind").asText()))
                .findFirst()
                .orElseThrow();
        assertEquals("hook_additional_context", attachment.get("attachmentKind").asText());
        assertEquals("hook_additional_context", attachment.get("source").asText());
    }

    @Test
    void initializeIncludesHookProgressMetadataInSnapshotMessages() throws Exception {
        Path tempDirectory = Files.createTempDirectory("openclaude-stdio-hook-progress");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new FakeOpenAiProvider()));
        PromptRouter promptRouter = new PromptRouter(providerRegistry, stateStore, sessionStore);
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());
        ModelService modelService = new ModelService(providerRegistry, stateStore);
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(stateStore, sessionStore, providerRegistry, new DefaultToolRuntime(), sessionService);

        stateStore.save(OpenClaudeState.empty()
                .withConnection(new ProviderConnectionState(
                        ProviderId.OPENAI,
                        AuthMethod.API_KEY,
                        "test-key",
                        Instant.parse("2026-01-01T00:00:00Z")
                ))
                .withActiveProvider(ProviderId.OPENAI)
                .withActiveModel("gpt-test")
                .withActiveSession("session-hook-progress"));

        sessionStore.save(ConversationSession.create("session-hook-progress")
                .append(SessionMessage.progress(
                        "PreToolUse:Bash [echo pre] started",
                        "hook_started",
                        "tool-a",
                        "PreToolUse",
                        "PreToolUse:Bash",
                        "echo pre",
                        false
                )));

        OpenClaudeStdioServer server = new OpenClaudeStdioServer(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        );

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        server.run(
                new ByteArrayInputStream("{\"kind\":\"request\",\"id\":\"req-init\",\"method\":\"initialize\",\"params\":{}}\n".getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr
        );

        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
        JsonNode response = objectMapper.readTree(stdout.toString(StandardCharsets.UTF_8).trim());
        JsonNode messages = response.path("result").path("messages");
        JsonNode progress = java.util.stream.StreamSupport.stream(messages.spliterator(), false)
                .filter(message -> "progress".equals(message.get("kind").asText()))
                .findFirst()
                .orElseThrow();

        assertEquals("hook_started", progress.get("phase").asText());
        assertEquals("tool-a", progress.get("toolId").asText());
        assertEquals("PreToolUse", progress.get("hookEvent").asText());
        assertEquals("PreToolUse:Bash", progress.get("toolName").asText());
        assertEquals("echo pre", progress.get("command").asText());
    }

    @Test
    void initializeIncludesPermissionInteractionMetadataInSnapshotMessages() throws Exception {
        Path tempDirectory = Files.createTempDirectory("openclaude-stdio-permission-interaction");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new FakeOpenAiProvider()));
        PromptRouter promptRouter = new PromptRouter(providerRegistry, stateStore, sessionStore);
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());
        ModelService modelService = new ModelService(providerRegistry, stateStore);
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDirectory);
        CommandService commandService = new CommandService(stateStore, sessionStore, providerRegistry, new DefaultToolRuntime(), sessionService);

        stateStore.save(OpenClaudeState.empty()
                .withConnection(new ProviderConnectionState(
                        ProviderId.OPENAI,
                        AuthMethod.API_KEY,
                        "test-key",
                        Instant.parse("2026-01-01T00:00:00Z")
                ))
                .withActiveProvider(ProviderId.OPENAI)
                .withActiveModel("gpt-test")
                .withActiveSession("session-permission-interaction"));

        sessionStore.save(ConversationSession.create("session-permission-interaction")
                .append(SessionMessage.tool(
                        "tool-a",
                        "AskUserQuestion",
                        "permission_requested",
                        "Question requires user input.",
                        "{\"questions\":[]}",
                        "",
                        "perm-ask-1",
                        "ask_user_question",
                        "{\"questions\":[{\"header\":\"Approach\",\"question\":\"Which approach?\"}]}",
                        false
                )));

        OpenClaudeStdioServer server = new OpenClaudeStdioServer(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        );

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        server.run(
                new ByteArrayInputStream("{\"kind\":\"request\",\"id\":\"req-init\",\"method\":\"initialize\",\"params\":{}}\n".getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr
        );

        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
        JsonNode response = objectMapper.readTree(stdout.toString(StandardCharsets.UTF_8).trim());
        JsonNode messages = response.path("result").path("messages");
        JsonNode permissionTool = java.util.stream.StreamSupport.stream(messages.spliterator(), false)
                .filter(message -> "tool".equals(message.get("kind").asText()))
                .findFirst()
                .orElseThrow();

        assertEquals("permission_requested", permissionTool.get("phase").asText());
        assertEquals("ask_user_question", permissionTool.get("interactionType").asText());
        assertTrue(permissionTool.get("interactionJson").asText().contains("\"Approach\""));
    }

    @Test
    void promptCancelCancelsTheActivePromptRequest() throws Exception {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new BlockingStreamingOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state-prompt-cancel.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDir.resolve("sessions-prompt-cancel"));
        PromptRouter promptRouter = new PromptRouter(providerRegistry, stateStore, sessionStore);
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());
        ModelService modelService = new ModelService(providerRegistry, stateStore);
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDir);
        CommandService commandService = new CommandService(stateStore, sessionStore, providerRegistry, new DefaultToolRuntime(), sessionService);

        stateStore.save(OpenClaudeState.empty()
                .withConnection(new ProviderConnectionState(
                        ProviderId.OPENAI,
                        AuthMethod.API_KEY,
                        "test-key",
                        Instant.parse("2026-04-03T00:00:00Z")
                ))
                .withActiveProvider(ProviderId.OPENAI)
                .withActiveModel("test-model")
                .withActiveSession("session-prompt-cancel"));
        sessionStore.save(ConversationSession.create("session-prompt-cancel"));

        OpenClaudeStdioServer server = new OpenClaudeStdioServer(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        );

        String requests = String.join("\n",
                "{\"kind\":\"request\",\"id\":\"submit-1\",\"method\":\"prompt.submit\",\"params\":{\"text\":\"block\"}}",
                "{\"kind\":\"request\",\"id\":\"cancel-1\",\"method\":\"prompt.cancel\",\"params\":{\"requestId\":\"submit-1\"}}"
        ) + "\n";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        server.run(
                new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr
        );

        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
        List<JsonNode> lines = java.util.Arrays.stream(stdout.toString(StandardCharsets.UTF_8).trim().split("\\R"))
                .map(line -> {
                    try {
                        return objectMapper.readTree(line);
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                })
                .toList();

        assertTrue(lines.stream().anyMatch(line ->
                "event".equals(line.get("kind").asText())
                        && "submit-1".equals(line.get("id").asText())
                        && "prompt.started".equals(line.get("event").asText())
        ));
        assertTrue(lines.stream().anyMatch(line ->
                "response".equals(line.get("kind").asText())
                        && "submit-1".equals(line.get("id").asText())
                        && !line.get("ok").asBoolean()
                        && (
                        "prompt_cancelled".equals(line.get("error").get("code").asText())
                                || ("runtime_error".equals(line.get("error").get("code").asText())
                                && ("Prompt cancelled.".equals(line.get("error").get("message").asText())
                                || "Prompt interrupted.".equals(line.get("error").get("message").asText())))
                )
        ));
        assertTrue(lines.stream().anyMatch(line ->
                "response".equals(line.get("kind").asText())
                        && "cancel-1".equals(line.get("id").asText())
        ));
    }

    @Test
    void initializeHidesNonExecutableProvidersAndSanitizesLegacyActiveProvider() throws Exception {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new FakeOpenAiProvider(), new FakeGeminiRegistryOnlyProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state-provider-gating.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDir.resolve("sessions-provider-gating"));
        PromptRouter promptRouter = new PromptRouter(providerRegistry, stateStore, sessionStore);
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());
        ModelService modelService = new ModelService(providerRegistry, stateStore);
        SessionService sessionService = new SessionService(stateStore, sessionStore, tempDir);
        CommandService commandService = new CommandService(stateStore, sessionStore, providerRegistry, new DefaultToolRuntime(), sessionService);

        stateStore.saveConnection(new com.openclaude.core.provider.ProviderConnectionState(
                ProviderId.GEMINI,
                AuthMethod.API_KEY,
                "env:GEMINI_API_KEY",
                java.time.Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.saveConnection(new com.openclaude.core.provider.ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:OPENAI_API_KEY",
                java.time.Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.save(new com.openclaude.core.provider.OpenClaudeState(
                ProviderId.GEMINI,
                "gemini/gemini-2.5-pro",
                null,
                stateStore.load().connections(),
                stateStore.load().settings()
        ));

        OpenClaudeStdioServer server = new OpenClaudeStdioServer(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        );

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        server.run(
                new ByteArrayInputStream("{\"kind\":\"request\",\"id\":\"1\",\"method\":\"initialize\"}\n".getBytes(StandardCharsets.UTF_8)),
                stdout,
                stderr
        );

        JsonNode initResponse = objectMapper.readTree(stdout.toString(StandardCharsets.UTF_8).trim());
        assertTrue(initResponse.get("ok").asBoolean());
        assertEquals("openai", initResponse.get("result").get("state").get("activeProvider").asText());
        JsonNode activeModelId = initResponse.get("result").get("state").get("activeModelId");
        assertTrue(activeModelId == null || activeModelId.isNull());
        assertEquals(1, initResponse.get("result").get("providers").size());
        assertEquals("openai", initResponse.get("result").get("providers").get(0).get("providerId").asText());
        assertEquals(1, initResponse.get("result").get("state").get("connections").size());
        assertEquals("openai", initResponse.get("result").get("models").get(0).get("providerId").asText());
    }

    private static class FakeOpenAiProvider implements ProviderPlugin {
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
            return List.of(new ModelDescriptor("test-model", "Test Model", ProviderId.OPENAI));
        }

        @Override
        public boolean supportsPromptExecution() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            return new PromptResult("pong");
        }

        @Override
        public PromptResult executePromptStream(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
            eventConsumer.accept(new ReasoningDeltaEvent("planning", true));
            eventConsumer.accept(new ToolCallEvent("tool-1", "shell", "started", "Running ls", "ls"));
            eventConsumer.accept(new ToolCallEvent("tool-1", "shell", "delta", "ls -la", "ls -la"));
            eventConsumer.accept(new ToolCallEvent("tool-1", "shell", "completed", "exit 0", "ls -la"));
            eventConsumer.accept(new TextDeltaEvent("pong"));
            return new PromptResult("pong");
        }
    }

    private static final class FakeToolingOpenAiProvider extends FakeOpenAiProvider {
        @Override
        public boolean supportsTools() {
            return true;
        }
    }

    private static final class RateLimitedOpenAiProvider extends FakeOpenAiProvider {
        @Override
        public PromptResult executePromptStream(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
            throw new ProviderHttpException(
                    ProviderId.OPENAI,
                    429,
                    "{\"error\":\"rate_limit_exceeded\"}",
                    java.util.Map.of("retry-after", java.util.List.of("60")),
                    "Rate limit exceeded"
            );
        }
    }

    private static final class BlockingStreamingOpenAiProvider extends FakeOpenAiProvider {
        @Override
        public PromptResult executePromptStream(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
            try {
                while (true) {
                    Thread.sleep(50);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Prompt stream interrupted", exception);
            }
        }
    }

    private static final class FakeGeminiRegistryOnlyProvider implements ProviderPlugin {
        @Override
        public ProviderId id() {
            return ProviderId.GEMINI;
        }

        @Override
        public String displayName() {
            return "Gemini";
        }

        @Override
        public Set<AuthMethod> supportedAuthMethods() {
            return Set.of(AuthMethod.API_KEY);
        }

        @Override
        public List<ModelDescriptor> supportedModels() {
            return List.of(new ModelDescriptor("gemini/gemini-2.5-pro", "Gemini 2.5 Pro", id()));
        }
    }
}
