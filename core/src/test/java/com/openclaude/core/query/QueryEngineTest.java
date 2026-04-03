package com.openclaude.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.config.OpenClaudeSettings;
import com.openclaude.core.instructions.AgentsInstructionsLoader;
import com.openclaude.core.provider.OpenClaudeState;
import com.openclaude.core.provider.ProviderConnectionState;
import com.openclaude.core.provider.ProviderRegistry;
import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.ConversationSessionStore;
import com.openclaude.core.session.SessionAttachment;
import com.openclaude.core.session.SessionMessage;
import com.openclaude.core.session.TodoItem;
import com.openclaude.core.sessionmemory.SessionMemoryService;
import com.openclaude.core.tools.BashToolRuntime;
import com.openclaude.core.tools.ToolHookConfigLoader;
import com.openclaude.core.tools.ToolExecutionRequest;
import com.openclaude.core.tools.ToolExecutionResult;
import com.openclaude.core.tools.ToolSessionEffect;
import com.openclaude.core.tools.ToolExecutionUpdate;
import com.openclaude.core.tools.ToolHooksExecutor;
import com.openclaude.core.tools.ToolPermissionDecision;
import com.openclaude.core.tools.ToolPermissionGateway;
import com.openclaude.core.tools.ToolPermissionRequest;
import com.openclaude.core.tools.ToolRuntime;
import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.PromptEvent;
import com.openclaude.provider.spi.PromptContentBlock;
import com.openclaude.provider.spi.PromptRequest;
import com.openclaude.provider.spi.PromptResult;
import com.openclaude.provider.spi.ProviderToolDefinition;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderPlugin;
import com.openclaude.provider.spi.PromptMessageRole;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class QueryEngineTest {
    @Test
    void submitPersistsConversationAcrossTurns() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new TestProviderPlugin()));
        QueryEngine queryEngine = new QueryEngine(providerRegistry, stateStore, sessionStore);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        List<String> streamedDeltas = new ArrayList<>();
        QueryTurnResult firstTurn = queryEngine.submit("hello", streamedDeltas::add);
        QueryTurnResult secondTurn = queryEngine.submit("again");

        assertEquals(firstTurn.sessionId(), secondTurn.sessionId());
        assertEquals("messages=1 last=hello", firstTurn.text());
        assertEquals("messages=3 last=again", secondTurn.text());
        assertEquals(List.of("messages=1 ", "last=hello"), streamedDeltas);
        assertNotNull(stateStore.load().activeSessionId());

        ConversationSession session = sessionStore.loadOrCreate(firstTurn.sessionId());
        assertEquals(4, session.messages().size());
        assertInstanceOf(SessionMessage.UserMessage.class, session.messages().get(0));
        assertInstanceOf(SessionMessage.AssistantMessage.class, session.messages().get(1));
        assertInstanceOf(SessionMessage.UserMessage.class, session.messages().get(2));
        assertInstanceOf(SessionMessage.AssistantMessage.class, session.messages().get(3));
        assertEquals("hello", session.messages().get(0).text());
        assertEquals("messages=1 last=hello", session.messages().get(1).text());
        assertEquals("again", session.messages().get(2).text());
        assertEquals("messages=3 last=again", session.messages().get(3).text());
    }

    @Test
    void submitStartsSessionMemoryExtractionAfterLongTurn() throws Exception {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-session-memory");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        SessionMemoryService sessionMemoryService = new SessionMemoryService(sessionStore, tempDirectory.resolve("session-memory"));
        SessionMemoryProviderPlugin providerPlugin = new SessionMemoryProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new BashToolRuntime(),
                new ToolHooksExecutor(),
                new AgentsInstructionsLoader(),
                sessionMemoryService
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        String longPrompt = "A".repeat(36_000);
        QueryTurnResult turn = queryEngine.submit(longPrompt);

        sessionMemoryService.waitForPendingExtraction(turn.sessionId());
        ConversationSession session = sessionStore.load(turn.sessionId());
        assertTrue(session.sessionMemoryState().initialized());
        assertNotNull(session.sessionMemoryState().lastSummarizedMessageId());
        assertTrue(providerPlugin.invocationCount() >= 2);

        String notes = Files.readString(sessionMemoryService.memoryFile(turn.sessionId()));
        assertTrue(notes.contains("# Current State"));
        assertTrue(notes.contains("Generated session memory"));
    }

    @Test
    void submitRetriesOnceAfterReactiveCompactWhenProviderSignalsPromptTooLong() throws Exception {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-reactive-compact");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        SessionMemoryService sessionMemoryService = new SessionMemoryService(sessionStore, tempDirectory.resolve("session-memory"));
        ReactiveCompactProviderPlugin providerPlugin = new ReactiveCompactProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new BashToolRuntime(),
                new ToolHooksExecutor(),
                new AgentsInstructionsLoader(),
                sessionMemoryService
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveSession("reactive-session");
        ConversationSession existingSession = ConversationSession.create("reactive-session", tempDirectory.toString(), tempDirectory.toString())
                .append(SessionMessage.user("Older prompt " + "x".repeat(10_000)))
                .append(SessionMessage.assistant("Older answer " + "y".repeat(10_000), ProviderId.OPENAI, "gpt-5.4"));
        sessionStore.save(existingSession);

        QueryTurnResult turn = queryEngine.submit("Need the final answer");

        assertEquals("Recovered answer after reactive compact.", turn.text());
        assertEquals(3, providerPlugin.invocationCount());
        ConversationSession session = sessionStore.load("reactive-session");
        assertTrue(session.messages().stream().anyMatch(SessionMessage.CompactBoundaryMessage.class::isInstance));
        assertTrue(session.messages().stream().anyMatch(message ->
                message instanceof SessionMessage.UserMessage userMessage
                        && userMessage.compactSummary()
                        && userMessage.text().contains("Summary:")
        ));
    }

    @Test
    void submitAppliesTimeBasedMicrocompactBeforeProviderRequest() throws Exception {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-microcompact");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        CapturingToolAwareProviderPlugin providerPlugin = new CapturingToolAwareProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(providerRegistry, stateStore, sessionStore);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        String sessionId = stateStore.load().activeSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = stateStore.setActiveSession("microcompact-session").activeSessionId();
        }

        Instant oldTime = Instant.now().minusSeconds(61 * 60L);
        java.util.ArrayList<SessionMessage> messages = new java.util.ArrayList<>();
        for (int index = 0; index < 7; index += 1) {
            String toolUseId = "tool-" + index;
            messages.add(new SessionMessage.UserMessage("user-" + index, oldTime, "prompt " + index, false));
            messages.add(new SessionMessage.AssistantMessage(
                    "assistant-tool-" + index,
                    oldTime,
                    "",
                    ProviderId.OPENAI,
                    "gpt-5.4",
                    List.of(new ToolUseContentBlock(toolUseId, "Read", "{\"file\":\"File" + index + "\"}"))
            ));
            messages.add(new SessionMessage.ToolResultMessage(
                    "tool-result-" + index,
                    oldTime,
                    toolUseId,
                    "Read",
                    "contents " + "x".repeat(2_000),
                    false
            ));
            messages.add(new SessionMessage.AssistantMessage(
                    "assistant-final-" + index,
                    oldTime,
                    "done " + index,
                    ProviderId.OPENAI,
                    "gpt-5.4",
                    List.of()
            ));
        }
        sessionStore.save(ConversationSession.create(sessionId, tempDirectory.toString(), tempDirectory.toString()).withMessages(messages));

        queryEngine.submit("follow-up question");

        List<ToolResultContentBlock> toolResults = providerPlugin.firstRequest().messages().stream()
                .flatMap(message -> message.content().stream())
                .filter(ToolResultContentBlock.class::isInstance)
                .map(ToolResultContentBlock.class::cast)
                .toList();
        assertEquals(TimeBasedMicrocompact.CLEARED_MESSAGE, toolResults.get(0).text());
        assertEquals(TimeBasedMicrocompact.CLEARED_MESSAGE, toolResults.get(1).text());
        assertTrue(toolResults.subList(2, toolResults.size()).stream()
                .noneMatch(toolResult -> TimeBasedMicrocompact.CLEARED_MESSAGE.equals(toolResult.text())));
    }

    @Test
    void submitPersistsReasoningAndToolMessages() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-tools");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolingProviderPlugin()));
        QueryEngine queryEngine = new QueryEngine(providerRegistry, stateStore, sessionStore);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        QueryTurnResult turn = queryEngine.submit("tool please");

        ConversationSession session = sessionStore.loadOrCreate(turn.sessionId());
        assertEquals(4, session.messages().size());
        assertInstanceOf(SessionMessage.UserMessage.class, session.messages().get(0));
        assertInstanceOf(SessionMessage.ThinkingMessage.class, session.messages().get(1));
        assertInstanceOf(SessionMessage.ToolInvocationMessage.class, session.messages().get(2));
        assertInstanceOf(SessionMessage.AssistantMessage.class, session.messages().get(3));
        assertEquals("planning", session.messages().get(1).text());
        assertEquals("shell", ((SessionMessage.ToolInvocationMessage) session.messages().get(2)).toolName());
        assertEquals("completed", ((SessionMessage.ToolInvocationMessage) session.messages().get(2)).phase());
        assertEquals("done", session.messages().get(3).text());
    }

    @Test
    void submitWithEventsStreamsFinalAnswerDeltasBeforeToolLoopCompletes() throws Exception {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-stream-after-tool");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        CountDownLatch streamedDeltaSeen = new CountDownLatch(1);
        CountDownLatch allowProviderReturn = new CountDownLatch(1);
        StreamingAfterToolProviderPlugin providerPlugin = new StreamingAfterToolProviderPlugin(streamedDeltaSeen, allowProviderReturn);
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new RetryToolRuntime()
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        CompletableFuture<QueryTurnResult> future = CompletableFuture.supplyAsync(() ->
                queryEngine.submitWithEvents("summarize desktop", event -> {
                    if (event instanceof TextDeltaEvent textDeltaEvent && textDeltaEvent.text().contains("streamed after tool")) {
                        streamedDeltaSeen.countDown();
                    }
                })
        );

        assertTrue(streamedDeltaSeen.await(1, TimeUnit.SECONDS), "Expected the streamed delta before the provider returned.");
        assertTrue(!future.isDone(), "The provider should still be blocked after emitting the streamed delta.");

        allowProviderReturn.countDown();
        QueryTurnResult turn = future.get(1, TimeUnit.SECONDS);
        assertEquals("streamed after tool", turn.text());
    }

    @Test
    void submitStartsReadOnlyStreamingToolBeforeProviderReturns() throws Exception {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-early-stream-tool");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch yieldedToolResult = new CountDownLatch(1);
        CountDownLatch allowProviderReturn = new CountDownLatch(1);
        StreamingReadOnlyEarlyToolProviderPlugin providerPlugin =
                new StreamingReadOnlyEarlyToolProviderPlugin(allowProviderReturn);
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new StreamingReadOnlyEarlyToolRuntime(toolStarted)
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        CompletableFuture<QueryTurnResult> future = CompletableFuture.supplyAsync(() ->
                queryEngine.submitWithEvents("inspect the repo", event -> {
                    if (event instanceof ToolCallEvent toolCallEvent
                            && "tool-read".equals(toolCallEvent.toolId())
                            && "yielded".equals(toolCallEvent.phase())) {
                        yieldedToolResult.countDown();
                    }
                })
        );

        assertTrue(toolStarted.await(1, TimeUnit.SECONDS), "Expected the read-only streaming tool to start before the provider returned.");
        assertTrue(yieldedToolResult.await(1, TimeUnit.SECONDS), "Expected the streamed tool result to be yielded before the provider returned.");
        assertTrue(!future.isDone(), "The provider should still be blocked after the tool has already started.");

        allowProviderReturn.countDown();
        QueryTurnResult turn = future.get(1, TimeUnit.SECONDS);
        assertEquals("final answer after streamed tool", turn.text());
    }

    @Test
    void submitStartsSerialStreamingToolBeforeProviderReturns() throws Exception {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-early-stream-serial-tool");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch allowProviderReturn = new CountDownLatch(1);
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(
                new StreamingSerialEarlyToolProviderPlugin(allowProviderReturn)
        ));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new StreamingSerialEarlyToolRuntime(toolStarted)
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        CompletableFuture<QueryTurnResult> future = CompletableFuture.supplyAsync(() ->
                queryEngine.submit("apply the change")
        );

        assertTrue(toolStarted.await(1, TimeUnit.SECONDS), "Expected the serial streaming tool to start before the provider returned.");
        assertTrue(!future.isDone(), "The provider should still be blocked after the serial tool has already started.");

        allowProviderReturn.countDown();
        QueryTurnResult turn = future.get(1, TimeUnit.SECONDS);
        assertEquals("final answer after streamed serial tool", turn.text());
    }

    @Test
    void submitPersistsEarlyStreamedReadOnlyToolTrajectoryWhenProviderFails() throws Exception {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-stream-failure-tool");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        CountDownLatch toolStarted = new CountDownLatch(1);
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new StreamingReadOnlyFailureProviderPlugin()));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new StreamingReadOnlyEarlyToolRuntime(toolStarted)
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                queryEngine.submitWithEvents("inspect the repo", event -> {
                })
        );
        assertTrue(failure.getMessage().contains("Provider stream broke"));
        assertTrue(toolStarted.await(1, TimeUnit.SECONDS), "Expected the early streamed tool to run before the provider failed.");

        ConversationSession session = sessionStore.loadOrCreate(stateStore.load().activeSessionId());
        assertTrue(session.messages().stream().anyMatch(message ->
                message instanceof SessionMessage.AssistantMessage assistantMessage
                        && assistantMessage.toolUses().stream().anyMatch(toolUse -> "tool-read".equals(toolUse.toolUseId()))
        ));
        assertTrue(session.messages().stream().anyMatch(message ->
                message instanceof SessionMessage.ToolResultMessage toolResultMessage
                        && "tool-read".equals(toolResultMessage.toolUseId())
                        && !toolResultMessage.isError()
        ));
        assertTrue(session.messages().stream().anyMatch(message ->
                message instanceof SessionMessage.TombstoneMessage tombstoneMessage
                        && tombstoneMessage.text().contains("Prompt execution failed: Provider stream broke")
        ));
    }

    @Test
    void submitExecutesHooksAroundEarlyStreamedReadOnlyTools() throws Exception {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-streaming-tool-hooks");
        Path workspace = tempDirectory.resolve("workspace");
        Files.createDirectories(workspace.resolve(".openclaude"));
        Files.writeString(
                workspace.resolve(".openclaude").resolve("settings.json"),
                """
                {
                  "hooks": {
                    "PreToolUse": [
                      {
                        "matcher": "Read",
                        "hooks": [
                          { "type": "command", "command": "printf pre-context" }
                        ]
                      }
                    ],
                    "PostToolUse": [
                      {
                        "matcher": "Read",
                        "hooks": [
                          { "type": "command", "command": "printf post-context" }
                        ]
                      }
                    ]
                  }
                }
                """
        );

        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch allowProviderReturn = new CountDownLatch(1);
        CapturingStreamingReadOnlyEarlyToolProviderPlugin providerPlugin =
                new CapturingStreamingReadOnlyEarlyToolProviderPlugin(allowProviderReturn);
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new StreamingReadOnlyEarlyToolRuntime(toolStarted),
                new ToolHooksExecutor(new ToolHookConfigLoader(List.of(workspace.resolve(".openclaude").resolve("settings.json")))),
                new AgentsInstructionsLoader(),
                new SessionMemoryService(sessionStore)
        );

        stateStore.save(OpenClaudeState.empty()
                .withConnection(new ProviderConnectionState(
                        ProviderId.OPENAI,
                        AuthMethod.API_KEY,
                        "env:TEST_OPENAI_KEY",
                        Instant.parse("2026-04-03T00:00:00Z")
                ))
                .withActiveProvider(ProviderId.OPENAI)
                .withActiveModel("gpt-test")
                .withActiveSession("session-stream-hooks"));
        sessionStore.save(ConversationSession.create(
                "session-stream-hooks",
                workspace.toString(),
                workspace.toString()
        ));

        CompletableFuture<QueryTurnResult> future = CompletableFuture.supplyAsync(() ->
                queryEngine.submit("inspect repo")
        );

        assertTrue(toolStarted.await(1, TimeUnit.SECONDS), "Expected the early streamed read-only tool to start.");
        allowProviderReturn.countDown();
        QueryTurnResult turn = future.get(1, TimeUnit.SECONDS);

        assertEquals("final answer after streamed tool", turn.text());
        ConversationSession session = sessionStore.loadOrCreate(turn.sessionId());
        assertTrue(session.messages().stream().anyMatch(message ->
                message instanceof SessionMessage.ProgressMessage progressMessage
                        && "hook_started".equals(progressMessage.progressKind())
                        && "PreToolUse".equals(progressMessage.hookEvent())
                        && "tool-read".equals(progressMessage.toolUseId())
        ));
        assertTrue(session.messages().stream().anyMatch(message ->
                message instanceof SessionMessage.AttachmentMessage attachmentMessage
                        && attachmentMessage.attachment() instanceof SessionAttachment.HookAdditionalContextAttachment hookAttachment
                        && "PostToolUse".equals(hookAttachment.hookEvent())
                        && "tool-read".equals(hookAttachment.toolUseId())
                        && hookAttachment.content().contains("post-context")
        ));

        PromptRequest followUpRequest = providerPlugin.request(1);
        assertNotNull(followUpRequest);
        assertTrue(followUpRequest.messages().stream().anyMatch(message -> message.text().contains("pre-context")));
        assertTrue(followUpRequest.messages().stream().anyMatch(message -> message.text().contains("post-context")));
        List<ToolResultContentBlock> toolResults = followUpRequest.messages().stream()
                .flatMap(message -> message.content().stream())
                .filter(ToolResultContentBlock.class::isInstance)
                .map(ToolResultContentBlock.class::cast)
                .toList();
        assertEquals(1, toolResults.size());
        assertEquals("tool-read", toolResults.getFirst().toolUseId());
        assertEquals("README contents", toolResults.getFirst().text());
    }

    @Test
    void submitPersistsSyntheticCancelledToolResultsForInterruptedSerialToolLoop() throws Exception {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-cancelled-serial-tools");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        AtomicReference<Thread> queryThread = new AtomicReference<>();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new InterruptingToolLoopProviderPlugin(queryThread)));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new InterruptingSerialToolRuntime(queryThread)
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-03T00:00:00Z")
        ));

        RuntimeException failure = assertThrows(RuntimeException.class, () -> queryEngine.submit("cancel me"));
        assertEquals("Prompt cancelled.", failure.getMessage());

        ConversationSession session = sessionStore.loadOrCreate(stateStore.load().activeSessionId());
        List<SessionMessage.ToolResultMessage> toolResults = session.messages().stream()
                .filter(SessionMessage.ToolResultMessage.class::isInstance)
                .map(SessionMessage.ToolResultMessage.class::cast)
                .toList();
        assertEquals(2, toolResults.size());
        assertTrue(toolResults.stream().allMatch(SessionMessage.ToolResultMessage::isError));
        assertTrue(toolResults.stream().allMatch(message -> "Prompt cancelled.".equals(message.text())));
        assertTrue(session.messages().stream().noneMatch(SessionMessage.TombstoneMessage.class::isInstance));
    }

    @Test
    void submitPersistsSyntheticCancelledToolResultsForInterruptedEarlyStreamedTools() throws Exception {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-cancelled-streamed-tools");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        AtomicReference<Thread> queryThread = new AtomicReference<>();
        CountDownLatch toolStarted = new CountDownLatch(1);
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(
                new InterruptingStreamingToolProviderPlugin(queryThread, toolStarted)
        ));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new InterruptingStreamingReadOnlyToolRuntime(queryThread, toolStarted)
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-03T00:00:00Z")
        ));

        RuntimeException failure = assertThrows(RuntimeException.class, () -> queryEngine.submit("cancel streamed tool"));
        assertEquals("Prompt cancelled.", failure.getMessage());

        ConversationSession session = sessionStore.loadOrCreate(stateStore.load().activeSessionId());
        assertTrue(session.messages().stream().anyMatch(message ->
                message instanceof SessionMessage.AssistantMessage assistantMessage
                        && assistantMessage.toolUses().stream().anyMatch(toolUse -> "tool-read".equals(toolUse.toolUseId()))
        ));
        assertTrue(session.messages().stream().anyMatch(message ->
                message instanceof SessionMessage.ToolResultMessage toolResultMessage
                        && "tool-read".equals(toolResultMessage.toolUseId())
                        && toolResultMessage.isError()
                        && "Prompt cancelled.".equals(toolResultMessage.text())
        ));
        assertTrue(session.messages().stream().noneMatch(SessionMessage.TombstoneMessage.class::isInstance));
    }

    @Test
    void submitWithEventsExecutesPermissionAwareToolLoopAndPersistsPairedResults() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-real-tools");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ToolLoopProviderPlugin()));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new PermissionAwareToolRuntime()
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        List<PromptEvent> events = new ArrayList<>();
        QueryTurnResult turn = queryEngine.submitWithEvents(
                "summarize desktop",
                events::add,
                request -> ToolPermissionDecision.allow("approved in test")
        );

        assertEquals("folder summary", turn.text());

        ConversationSession session = sessionStore.loadOrCreate(turn.sessionId());
        assertEquals(8, session.messages().size());
        assertInstanceOf(SessionMessage.UserMessage.class, session.messages().get(0));
        assertInstanceOf(SessionMessage.AssistantMessage.class, session.messages().get(1));
        assertInstanceOf(SessionMessage.ToolInvocationMessage.class, session.messages().get(2));
        assertInstanceOf(SessionMessage.ToolInvocationMessage.class, session.messages().get(3));
        assertInstanceOf(SessionMessage.ToolInvocationMessage.class, session.messages().get(4));
        assertInstanceOf(SessionMessage.ToolInvocationMessage.class, session.messages().get(5));
        assertInstanceOf(SessionMessage.ToolResultMessage.class, session.messages().get(6));
        assertInstanceOf(SessionMessage.AssistantMessage.class, session.messages().get(7));

        SessionMessage.AssistantMessage toolAssistant = (SessionMessage.AssistantMessage) session.messages().get(1);
        assertEquals(1, toolAssistant.toolUses().size());
        assertEquals("tool-1", toolAssistant.toolUses().getFirst().toolUseId());
        assertEquals("bash", toolAssistant.toolUses().getFirst().toolName());
        SessionMessage.ToolInvocationMessage permissionMessage = (SessionMessage.ToolInvocationMessage) session.messages().get(3);
        assertEquals("permission_requested", permissionMessage.phase());
        assertEquals("pwd", permissionMessage.command());
        assertEquals("perm-1", permissionMessage.permissionRequestId());
        assertEquals("folder summary", session.messages().get(7).text());

        assertTrue(events.stream().anyMatch(ToolPermissionEvent.class::isInstance));
        assertTrue(events.stream().anyMatch(event ->
                event instanceof ToolCallEvent toolCallEvent
                        && "progress".equals(toolCallEvent.phase())
                        && "bash".equals(toolCallEvent.toolName())
        ));
        assertTrue(events.stream().anyMatch(event ->
                event instanceof ToolCallEvent toolCallEvent
                        && "yielded".equals(toolCallEvent.phase())
                        && "bash".equals(toolCallEvent.toolName())
        ));
    }

    @Test
    void submitPersistsTypedToolHookMessagesAroundToolTrajectory() throws Exception {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-tool-hooks");
        Path workspace = tempDirectory.resolve("workspace");
        Files.createDirectories(workspace.resolve(".openclaude"));
        Files.writeString(
                workspace.resolve(".openclaude").resolve("settings.json"),
                """
                {
                  "hooks": {
                    "PreToolUse": [
                      {
                        "matcher": "bash",
                        "hooks": [
                          { "type": "command", "command": "printf pre-context" }
                        ]
                      }
                    ],
                    "PostToolUse": [
                      {
                        "matcher": "bash",
                        "hooks": [
                          { "type": "command", "command": "printf post-context" }
                        ]
                      }
                    ]
                  }
                }
                """
        );

        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ToolThenAnswerProviderPlugin providerPlugin = new ToolThenAnswerProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new RetryToolRuntime(),
                new ToolHooksExecutor(new ToolHookConfigLoader(List.of(workspace.resolve(".openclaude").resolve("settings.json")))),
                new AgentsInstructionsLoader(),
                new SessionMemoryService(sessionStore)
        );

        stateStore.save(OpenClaudeState.empty()
                .withConnection(new ProviderConnectionState(
                        ProviderId.OPENAI,
                        AuthMethod.API_KEY,
                        "env:TEST_OPENAI_KEY",
                        Instant.parse("2026-04-03T00:00:00Z")
                ))
                .withActiveProvider(ProviderId.OPENAI)
                .withActiveModel("gpt-test")
                .withActiveSession("session-hooks"));
        sessionStore.save(ConversationSession.create(
                "session-hooks",
                workspace.toString(),
                workspace.toString()
        ));

        QueryTurnResult turn = queryEngine.submit("inspect repo");
        assertEquals("Final answer from tool output.", turn.text());

        ConversationSession session = sessionStore.loadOrCreate(turn.sessionId());
        assertTrue(session.messages().stream().anyMatch(message ->
                message instanceof SessionMessage.ProgressMessage progressMessage
                        && "hook_started".equals(progressMessage.progressKind())
                        && "PreToolUse".equals(progressMessage.hookEvent())
                        && "tool-bash".equals(progressMessage.toolUseId())
        ));
        assertTrue(session.messages().stream().anyMatch(message ->
                message instanceof SessionMessage.AttachmentMessage attachmentMessage
                        && attachmentMessage.attachment() instanceof SessionAttachment.HookAdditionalContextAttachment hookAttachment
                        && "PostToolUse".equals(hookAttachment.hookEvent())
                        && "tool-bash".equals(hookAttachment.toolUseId())
                        && hookAttachment.content().contains("post-context")
        ));

        PromptRequest followUpRequest = providerPlugin.request(1);
        assertNotNull(followUpRequest);
        List<ToolResultContentBlock> toolResults = followUpRequest.messages().stream()
                .flatMap(message -> message.content().stream())
                .filter(ToolResultContentBlock.class::isInstance)
                .map(ToolResultContentBlock.class::cast)
                .toList();
        assertEquals(1, toolResults.size());
        assertEquals("tool-bash", toolResults.getFirst().toolUseId());
        assertTrue(toolResults.getFirst().text().contains("alpha"));
        assertTrue(!"[Tool result missing due to internal error]".equals(toolResults.getFirst().text()));
        assertTrue(followUpRequest.messages().stream().anyMatch(message -> message.text().contains("pre-context")));
        assertTrue(followUpRequest.messages().stream().anyMatch(message -> message.text().contains("post-context")));
    }

    @Test
    void submitRetriesLocalToolQuestionsWhenProviderRefusesWithoutUsingTools() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-tool-retry");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ToolRetryProviderPlugin providerPlugin = new ToolRetryProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new RetryToolRuntime()
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        QueryTurnResult turn = queryEngine.submit("can you look at ~/Desktop and summarize project names");

        assertEquals("Desktop contains 2 project folders: alpha, beta.", turn.text());
        assertTrue(providerPlugin.sawRetryPrompt());

        ConversationSession session = sessionStore.loadOrCreate(turn.sessionId());
        assertEquals(4, session.messages().size());
        assertInstanceOf(SessionMessage.UserMessage.class, session.messages().get(0));
        assertInstanceOf(SessionMessage.AssistantMessage.class, session.messages().get(1));
        assertInstanceOf(SessionMessage.ToolResultMessage.class, session.messages().get(2));
        assertInstanceOf(SessionMessage.AssistantMessage.class, session.messages().get(3));

        SessionMessage.AssistantMessage toolAssistant = (SessionMessage.AssistantMessage) session.messages().get(1);
        assertEquals(1, toolAssistant.toolUses().size());
        assertEquals("bash", toolAssistant.toolUses().getFirst().toolName());
        assertEquals("Desktop contains 2 project folders: alpha, beta.", session.messages().get(3).text());
    }

    @Test
    void submitWithRealReadOnlyBashRuntimeDoesNotRequestPermission() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-real-bash");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new RealBashLoopProviderPlugin()));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new BashToolRuntime()
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        List<PromptEvent> events = new ArrayList<>();
        QueryTurnResult turn = queryEngine.submitWithEvents(
                "inspect the workspace",
                events::add,
                request -> {
                    throw new AssertionError("Read-only bash commands should not request permission.");
                }
        );

        assertEquals("workspace summary", turn.text());
        assertTrue(events.stream().noneMatch(ToolPermissionEvent.class::isInstance));

        ConversationSession session = sessionStore.loadOrCreate(turn.sessionId());
        assertTrue(session.messages().stream()
                .filter(SessionMessage.ToolInvocationMessage.class::isInstance)
                .map(SessionMessage.ToolInvocationMessage.class::cast)
                .noneMatch(message -> "permission_requested".equals(message.phase())));
        assertEquals("workspace summary", session.messages().getLast().text());
    }

    @Test
    void submitAppliesSessionEffectsReturnedByTools() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-session-effects");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new SessionEffectProviderPlugin()));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new SessionEffectToolRuntime()
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        QueryTurnResult turn = queryEngine.submit("plan and track");

        ConversationSession session = sessionStore.loadOrCreate(turn.sessionId());
        assertTrue(session.planMode());
        assertEquals(1, session.todos().size());
        assertEquals("Review tests", session.todos().getFirst().content());
        assertEquals("done", turn.text());
    }

    @Test
    void submitPrependsToolAwareSystemPrompt() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-system");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        HistoryFilteringProviderPlugin providerPlugin = new HistoryFilteringProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(providerRegistry, stateStore, sessionStore);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        queryEngine.submit("list my desktop folders");

        PromptRequest request = providerPlugin.lastRequest();
        assertNotNull(request);
        assertEquals(PromptMessageRole.SYSTEM, request.messages().getFirst().role());
        assertTrue(request.messages().getFirst().text().contains("use the available tools"));
        assertEquals("list my desktop folders", request.messages().getLast().text());
        assertEquals("bash", request.requiredToolName());
    }

    @Test
    void submitProjectsPromptHistoryFromTheLastCompactBoundary() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-compact-boundary");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        CapturingProviderPlugin providerPlugin = new CapturingProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(providerRegistry, stateStore, sessionStore);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        String sessionId = "session-after-compact";
        stateStore.setActiveSession(sessionId);
        sessionStore.save(ConversationSession.create(sessionId)
                .append(SessionMessage.user("old request"))
                .append(SessionMessage.assistant("old reply", ProviderId.OPENAI, "test-model"))
                .append(SessionMessage.compactBoundary("manual", 42, 2))
                .append(SessionMessage.compactSummary("This session is being continued from a previous conversation.\n\nSummary:\n1. Prior work: inspected the repo."))
                .append(SessionMessage.attachment(new com.openclaude.core.session.SessionAttachment.CompactFileReferenceAttachment("/tmp/Tracked.java"))));

        queryEngine.submit("continue from here");

        PromptRequest request = providerPlugin.lastRequest();
        assertNotNull(request);
        assertTrue(request.messages().stream().noneMatch(message -> "old request".equals(message.text())));
        assertTrue(request.messages().stream().noneMatch(message -> "old reply".equals(message.text())));
        assertTrue(request.messages().stream().anyMatch(message -> message.text().contains("This session is being continued from a previous conversation.")));
        assertTrue(request.messages().stream().anyMatch(message -> message.text().contains("/tmp/Tracked.java")));
        assertEquals("continue from here", request.messages().getLast().text());
    }

    @Test
    void submitThreadsConfiguredEffortIntoProviderRequests() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-effort");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        CapturingProviderPlugin providerPlugin = new CapturingProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(providerRegistry, stateStore, sessionStore);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveModel("gpt-5.4");
        stateStore.setSettings(OpenClaudeSettings.defaults().withEffortLevel("high"));

        queryEngine.submit("review this repo");

        PromptRequest request = providerPlugin.lastRequest();
        assertNotNull(request);
        assertEquals("high", request.effortLevel());
    }

    @Test
    void submitInjectsAgentsInstructionsIntoSystemPrompt() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-agents");
        Path managedRoot = tempDirectory.resolve("managed");
        Path userRoot = tempDirectory.resolve("user");
        Path workspace = tempDirectory.resolve("workspace");
        Path nested = workspace.resolve("service");
        Files.createDirectories(nested);
        Files.createDirectories(userRoot);
        Files.writeString(userRoot.resolve("AGENTS.md"), "global repo policy");
        Files.writeString(workspace.resolve("AGENTS.md"), "workspace coding rules");

        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        CapturingProviderPlugin providerPlugin = new CapturingProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new com.openclaude.core.tools.DefaultToolRuntime(),
                new AgentsInstructionsLoader(managedRoot, userRoot)
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        String sessionId = "session-with-agents";
        sessionStore.save(ConversationSession.create(sessionId, nested.toString(), workspace.toString()));
        stateStore.setActiveSession(sessionId);

        queryEngine.submit("follow the repo");

        PromptRequest request = providerPlugin.lastRequest();
        assertNotNull(request);
        assertEquals(PromptMessageRole.SYSTEM, request.messages().get(1).role());
        assertTrue(request.messages().get(1).text().contains("global repo policy"));
        assertTrue(request.messages().get(1).text().contains("workspace coding rules"));
        assertTrue(request.messages().get(1).text().contains("AGENTS.md"));
    }

    @Test
    void submitRepairsIncompleteLegacyToolTurnsWhenBuildingPromptHistory() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-history-filter");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        HistoryFilteringProviderPlugin providerPlugin = new HistoryFilteringProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(providerRegistry, stateStore, sessionStore);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        String sessionId = "legacy-session";
        stateStore.setActiveSession(sessionId);
        ConversationSession poisonedSession = ConversationSession.create(sessionId)
                .append(SessionMessage.user("summarize my desktop"))
                .append(SessionMessage.assistant(
                        "",
                        ProviderId.OPENAI,
                        "gpt-5.3-codex",
                        List.of(
                                new ToolUseContentBlock("call_incomplete", "bash", "{\"command\":\"ls -1 ~/Desktop\"}"),
                                new ToolUseContentBlock("fc_legacy", "function", "{\"command\":\"ls -1 ~/Desktop\"}")
                        )
                ))
                .append(SessionMessage.toolResult("fc_legacy", "function", "Unsupported tool: function", true));
        sessionStore.save(poisonedSession);

        QueryTurnResult turn = queryEngine.submit("hello");

        assertEquals("clean reply", turn.text());
        PromptRequest request = providerPlugin.lastRequest();
        assertNotNull(request);
        List<ToolUseContentBlock> toolUses = request.messages().stream()
                .flatMap(message -> message.content().stream())
                .filter(ToolUseContentBlock.class::isInstance)
                .map(ToolUseContentBlock.class::cast)
                .toList();
        List<ToolResultContentBlock> toolResults = request.messages().stream()
                .flatMap(message -> message.content().stream())
                .filter(ToolResultContentBlock.class::isInstance)
                .map(ToolResultContentBlock.class::cast)
                .toList();
        assertEquals(1, toolUses.size());
        assertEquals("call_incomplete", toolUses.getFirst().toolUseId());
        assertEquals("bash", toolUses.getFirst().toolName());
        assertEquals(1, toolResults.size());
        assertEquals("call_incomplete", toolResults.getFirst().toolUseId());
        assertTrue(toolResults.getFirst().isError());
        assertEquals("[Tool result missing due to internal error]", toolResults.getFirst().text());
        assertTrue(toolUses.stream().noneMatch(toolUse -> "function".equals(toolUse.toolName())));
        assertEquals("hello", request.messages().getLast().text());
    }

    @Test
    void submitRetainsCompletedToolTrajectoryWithoutFollowUpAssistant() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-orphaned-tool-result");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        HistoryFilteringProviderPlugin providerPlugin = new HistoryFilteringProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(providerRegistry, stateStore, sessionStore);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        String sessionId = "orphaned-tool-session";
        stateStore.setActiveSession(sessionId);
        ConversationSession poisonedSession = ConversationSession.create(sessionId)
                .append(SessionMessage.user("summarize ~/Desktop/py"))
                .append(SessionMessage.assistant(
                        "",
                        ProviderId.OPENAI,
                        "gpt-5.3-codex",
                        List.of(new ToolUseContentBlock("call_completed", "bash", "{\"command\":\"ls -la ~/Desktop/py\"}"))
                ))
                .append(SessionMessage.toolResult(
                        "call_completed",
                        "bash",
                        "Command: ls -la ~/Desktop/py\nExit code: 0\n\nalpha\nbeta",
                        false
                ));
        sessionStore.save(poisonedSession);

        QueryTurnResult turn = queryEngine.submit("hi");

        assertEquals("clean reply", turn.text());
        PromptRequest request = providerPlugin.lastRequest();
        assertNotNull(request);
        List<ToolUseContentBlock> toolUses = request.messages().stream()
                .flatMap(message -> message.content().stream())
                .filter(ToolUseContentBlock.class::isInstance)
                .map(ToolUseContentBlock.class::cast)
                .toList();
        List<ToolResultContentBlock> toolResults = request.messages().stream()
                .flatMap(message -> message.content().stream())
                .filter(ToolResultContentBlock.class::isInstance)
                .map(ToolResultContentBlock.class::cast)
                .toList();
        assertEquals(1, toolUses.size());
        assertEquals("call_completed", toolUses.getFirst().toolUseId());
        assertEquals(1, toolResults.size());
        assertEquals("call_completed", toolResults.getFirst().toolUseId());
        assertTrue(!toolResults.getFirst().isError());
        assertEquals("hi", request.messages().getLast().text());
    }

    @Test
    void submitRepairsRepeatedToolLoopTurnWithoutDroppingIt() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-repeated-tool-loop");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        HistoryFilteringProviderPlugin providerPlugin = new HistoryFilteringProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(providerRegistry, stateStore, sessionStore);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        String sessionId = "repeated-tool-session";
        stateStore.setActiveSession(sessionId);
        ConversationSession poisonedSession = ConversationSession.create(sessionId)
                .append(SessionMessage.user("summarize ~/Desktop/py"))
                .append(SessionMessage.assistant(
                        "",
                        ProviderId.OPENAI,
                        "gpt-5.3-codex",
                        List.of(new ToolUseContentBlock("call_first", "bash", "{\"command\":\"ls -la ~/Desktop/py\"}"))
                ))
                .append(SessionMessage.toolResult(
                        "call_first",
                        "bash",
                        "Command: ls -la ~/Desktop/py\nExit code: 0\n\nalpha\nbeta",
                        false
                ))
                .append(SessionMessage.assistant(
                        "",
                        ProviderId.OPENAI,
                        "gpt-5.3-codex",
                        List.of(new ToolUseContentBlock("call_second", "bash", "{\"command\":\"ls -la ~/Desktop/py\"}"))
                ))
                .append(SessionMessage.tool(
                        "call_second",
                        "bash",
                        "started",
                        "Queued bash command.",
                        "{\"command\":\"ls -la ~/Desktop/py\"}",
                        "ls -la ~/Desktop/py",
                        "",
                        false
                ));
        sessionStore.save(poisonedSession);

        QueryTurnResult turn = queryEngine.submit("hi");

        assertEquals("clean reply", turn.text());
        PromptRequest request = providerPlugin.lastRequest();
        assertNotNull(request);
        List<ToolUseContentBlock> toolUses = request.messages().stream()
                .flatMap(message -> message.content().stream())
                .filter(ToolUseContentBlock.class::isInstance)
                .map(ToolUseContentBlock.class::cast)
                .toList();
        List<ToolResultContentBlock> toolResults = request.messages().stream()
                .flatMap(message -> message.content().stream())
                .filter(ToolResultContentBlock.class::isInstance)
                .map(ToolResultContentBlock.class::cast)
                .toList();
        assertTrue(request.messages().stream().anyMatch(message ->
                "summarize ~/Desktop/py".equals(message.text())
        ));
        assertEquals(List.of("call_first", "call_second"), toolUses.stream().map(ToolUseContentBlock::toolUseId).toList());
        assertEquals(List.of("call_first", "call_second"), toolResults.stream().map(ToolResultContentBlock::toolUseId).toList());
        assertTrue(!toolResults.getFirst().isError());
        assertTrue(toolResults.get(1).isError());
        assertEquals("[Tool result missing due to internal error]", toolResults.get(1).text());
        assertEquals("hi", request.messages().getLast().text());
    }

    @Test
    void submitNudgesProviderToAnswerAfterRepeatingTheSameCompletedDesktopToolCall() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-duplicate-tool-retry");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        DuplicateDesktopToolLoopProviderPlugin providerPlugin = new DuplicateDesktopToolLoopProviderPlugin();
        CountingToolRuntime toolRuntime = new CountingToolRuntime(
                "bash",
                "{\"command\":\"cd ~/Desktop && find . -maxdepth 1 -mindepth 1 -type d | wc -l && find . -maxdepth 1 -mindepth 1 -type d | sed 's|^./||'\"}",
                "Command: cd ~/Desktop && find . -maxdepth 1 -mindepth 1 -type d | wc -l && find . -maxdepth 1 -mindepth 1 -type d | sed 's|^./||'\nExit code: 0\n\n25\nAgent-Kit\nbytebytego-ai-cohort"
        );
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(providerRegistry, stateStore, sessionStore, toolRuntime);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        QueryTurnResult turn = queryEngine.submit("go to ~/Desktop and summarize count number of folders present there");

        assertEquals("Desktop contains 25 folders.", turn.text());
        assertEquals(1, toolRuntime.executionCount());
        assertTrue(providerPlugin.sawFinalAnswerRetryPrompt());
    }

    @Test
    void submitForcesFinalAnswerAfterThreeToolOnlyDesktopIterations() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-tool-only-retry");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        MultiStepDesktopToolLoopProviderPlugin providerPlugin = new MultiStepDesktopToolLoopProviderPlugin();
        CountingSequenceToolRuntime toolRuntime = new CountingSequenceToolRuntime(List.of(
                "{\"command\":\"cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l\"}",
                "{\"command\":\"cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | sed 's|^./||'\"}",
                "{\"command\":\"cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | head -n 5\"}"
        ));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(providerRegistry, stateStore, sessionStore, toolRuntime);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        QueryTurnResult turn = queryEngine.submit("summarize the folders on ~/Desktop");

        assertEquals("Desktop contains 25 folders.", turn.text());
        assertEquals(3, toolRuntime.executionCount());
        assertTrue(providerPlugin.sawFinalAnswerRetryPrompt());
        assertTrue(providerPlugin.sawNoToolsRetry());
    }

    @Test
    void submitAllowsFinalNoToolsRetryAfterEightRealToolUseTurns() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-eight-tool-turns");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        EightStepDesktopToolLoopProviderPlugin providerPlugin = new EightStepDesktopToolLoopProviderPlugin();
        CountingSequenceToolRuntime toolRuntime = new CountingSequenceToolRuntime(List.of(
                "{\"command\":\"printf step-1\"}",
                "{\"command\":\"printf step-2\"}",
                "{\"command\":\"printf step-3\"}",
                "{\"command\":\"printf step-4\"}",
                "{\"command\":\"printf step-5\"}",
                "{\"command\":\"printf step-6\"}",
                "{\"command\":\"printf step-7\"}",
                "{\"command\":\"printf step-8\"}"
        ));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(providerRegistry, stateStore, sessionStore, toolRuntime);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        QueryTurnResult turn = queryEngine.submit("finish after eight tool turns");

        assertEquals("Final answer after eight tool turns.", turn.text());
        assertEquals(8, toolRuntime.executionCount());
        assertTrue(providerPlugin.sawFinalAnswerRetryPrompt());
        assertTrue(providerPlugin.sawNoToolsRetry());
    }

    @Test
    void submitPreservesToolHistoryWhenToolLoopFails() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-failed-loop-history");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        EndlessDesktopToolLoopProviderPlugin providerPlugin = new EndlessDesktopToolLoopProviderPlugin();
        CountingSequenceToolRuntime toolRuntime = new CountingSequenceToolRuntime(List.of(
                "{\"command\":\"cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l\"}",
                "{\"command\":\"cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l\"}",
                "{\"command\":\"cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l\"}",
                "{\"command\":\"cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l\"}"
        ));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(providerRegistry, stateStore, sessionStore, toolRuntime);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                queryEngine.submit("count desktop folders")
        );
        assertTrue(exception.getMessage().contains("repeated the same completed tool call")
                || exception.getMessage().contains("Tool loop exceeded"));

        String sessionId = stateStore.load().activeSessionId();
        ConversationSession session = sessionStore.loadOrCreate(sessionId);
        assertTrue(session.messages().stream().anyMatch(SessionMessage.AssistantMessage.class::isInstance));
        assertTrue(session.messages().stream().anyMatch(SessionMessage.ToolResultMessage.class::isInstance));
        assertTrue(session.messages().stream().anyMatch(message ->
                message instanceof SessionMessage.TombstoneMessage tombstone
                        && tombstone.text().contains("Prompt execution failed:")
        ));
    }

    @Test
    void submitRunsConcurrencySafeReadOnlyToolsAsOneBatch() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-concurrent-tools");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ConcurrentReadOnlyToolProviderPlugin providerPlugin = new ConcurrentReadOnlyToolProviderPlugin();
        ConcurrentReadOnlyToolRuntime toolRuntime = new ConcurrentReadOnlyToolRuntime();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(providerRegistry, stateStore, sessionStore, toolRuntime);

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        QueryTurnResult turn = queryEngine.submit("summarize the repo");

        assertEquals("read-only tool batch completed", turn.text());
        assertTrue(toolRuntime.peakConcurrency() >= 2, "Expected read-only tools to overlap in execution.");
    }

    @Test
    void submitConvertsConcurrentToolRuntimeExceptionsIntoPairedFailedToolResults() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-concurrent-tool-failure");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ConcurrentToolFailureProviderPlugin providerPlugin = new ConcurrentToolFailureProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new ConcurrentToolFailureRuntime()
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        List<PromptEvent> events = new ArrayList<>();
        QueryTurnResult turn = queryEngine.submitWithEvents("inspect concurrently", events::add);

        assertEquals("handled concurrent tool failure", turn.text());
        assertTrue(events.stream().anyMatch(event ->
                event instanceof ToolCallEvent toolCallEvent
                        && "failed".equals(toolCallEvent.phase())
                        && "Read".equals(toolCallEvent.toolName())
        ));

        ConversationSession session = sessionStore.loadOrCreate(turn.sessionId());
        List<SessionMessage.ToolResultMessage> toolResults = session.messages().stream()
                .filter(SessionMessage.ToolResultMessage.class::isInstance)
                .map(SessionMessage.ToolResultMessage.class::cast)
                .toList();
        assertEquals(2, toolResults.size());
        assertTrue(toolResults.stream().anyMatch(SessionMessage.ToolResultMessage::isError));
        assertTrue(toolResults.stream().anyMatch(message ->
                message.isError() && message.text().contains("Unexpected Read tool failure: read exploded")
        ));
        assertEquals("handled concurrent tool failure", session.messages().getLast().text());
    }

    @Test
    void submitStopsWaitingForConcurrentSiblingsWhenBashErrors() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-query-engine-concurrent-bash-sibling-abort");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ConcurrentBashSiblingAbortProviderPlugin providerPlugin = new ConcurrentBashSiblingAbortProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        QueryEngine queryEngine = new QueryEngine(
                providerRegistry,
                stateStore,
                sessionStore,
                new ConcurrentBashSiblingAbortRuntime()
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:TEST_OPENAI_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        long startedAt = System.nanoTime();
        QueryTurnResult turn = queryEngine.submit("run concurrent repo inspection");
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertEquals("handled sibling bash failure", turn.text());
        assertTrue(elapsedMillis < 400, "Expected the query to stop waiting on sibling tools after bash failure.");

        ConversationSession session = sessionStore.loadOrCreate(turn.sessionId());
        List<SessionMessage.ToolResultMessage> toolResults = session.messages().stream()
                .filter(SessionMessage.ToolResultMessage.class::isInstance)
                .map(SessionMessage.ToolResultMessage.class::cast)
                .toList();
        assertEquals(2, toolResults.size());
        assertTrue(toolResults.stream().anyMatch(message ->
                "bash".equals(message.toolName())
                        && message.isError()
                        && message.text().contains("bash failed fast")
        ));
        assertTrue(toolResults.stream().anyMatch(message ->
                "ReadAlpha".equals(message.toolName())
                        && message.isError()
                        && message.text().contains("parallel tool call bash(")
        ));
    }

    private static class TestProviderPlugin implements ProviderPlugin {
        @Override
        public ProviderId id() {
            return ProviderId.OPENAI;
        }

        @Override
        public String displayName() {
            return "Test OpenAI";
        }

        @Override
        public Set<AuthMethod> supportedAuthMethods() {
            return Set.of(AuthMethod.API_KEY);
        }

        @Override
        public List<ModelDescriptor> supportedModels() {
            return List.of(new ModelDescriptor("test-model", "Test Model", id()));
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
            String lastUserMessage = request.messages().getLast().text();
            return new PromptResult("messages=" + nonSystemMessageCount(request) + " last=" + lastUserMessage);
        }

        @Override
        public PromptResult executePromptStream(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
            int messageCount = nonSystemMessageCount(request);
            if (eventConsumer != null) {
                eventConsumer.accept(new TextDeltaEvent("messages=" + messageCount + " "));
                eventConsumer.accept(new TextDeltaEvent("last=" + request.messages().getLast().text()));
            }
            return new PromptResult("messages=" + messageCount + " last=" + request.messages().getLast().text());
        }

        private static int nonSystemMessageCount(PromptRequest request) {
            return (int) request.messages().stream()
                    .filter(message -> message.role() != PromptMessageRole.SYSTEM)
                    .count();
        }
    }

    private static final class ToolingProviderPlugin extends TestProviderPlugin {
        @Override
        public PromptResult executePromptStream(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
            if (eventConsumer != null) {
                eventConsumer.accept(new ReasoningDeltaEvent("planning", true));
                eventConsumer.accept(new ToolCallEvent("tool-1", "shell", "started", "Running ls", "ls"));
                eventConsumer.accept(new ToolCallEvent("tool-1", "shell", "completed", "exit 0", "ls"));
                eventConsumer.accept(new TextDeltaEvent("done"));
            }
            return new PromptResult("done");
        }
    }

    private static final class ConcurrentReadOnlyToolProviderPlugin extends TestProviderPlugin {
        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            long toolResultCount = request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .filter(ToolResultContentBlock.class::isInstance)
                    .count();
            if (toolResultCount >= 2) {
                return new PromptResult("read-only tool batch completed");
            }
            return new PromptResult(
                    "",
                    List.of(
                            new ToolUseContentBlock("tool-read", "ReadAlpha", "{\"filePath\":\"README.md\"}"),
                            new ToolUseContentBlock("tool-grep", "SearchBeta", "{\"pattern\":\"TODO\",\"path\":\".\"}")
                    )
            );
        }
    }

    private static final class ConcurrentReadOnlyToolRuntime implements ToolRuntime {
        private final CountDownLatch startedLatch = new CountDownLatch(2);
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger peakConcurrency = new AtomicInteger();

        @Override
        public List<ProviderToolDefinition> toolDefinitions() {
            return List.of(
                    new ProviderToolDefinition("ReadAlpha", "Read a file", "{\"type\":\"object\"}"),
                    new ProviderToolDefinition("SearchBeta", "Search workspace text", "{\"type\":\"object\"}")
            );
        }

        @Override
        public boolean isConcurrencySafe(String toolName, String inputJson) {
            return "ReadAlpha".equals(toolName) || "SearchBeta".equals(toolName);
        }

        @Override
        public ToolExecutionResult execute(
                ToolExecutionRequest request,
                ToolPermissionGateway permissionGateway,
                Consumer<ToolExecutionUpdate> updateConsumer
        ) {
            int active = inFlight.incrementAndGet();
            peakConcurrency.updateAndGet(current -> Math.max(current, active));
            startedLatch.countDown();
            try {
                assertTrue(startedLatch.await(1, TimeUnit.SECONDS), "Expected the read-only tool batch to execute concurrently.");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Concurrent tool test interrupted", exception);
            } finally {
                inFlight.decrementAndGet();
            }
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), request.toolName() + " result", false);
        }

        int peakConcurrency() {
            return peakConcurrency.get();
        }
    }

    private static final class ConcurrentBashSiblingAbortProviderPlugin extends TestProviderPlugin {
        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            long toolResultCount = request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .filter(ToolResultContentBlock.class::isInstance)
                    .count();
            if (toolResultCount >= 2) {
                return new PromptResult("handled sibling bash failure");
            }
            return new PromptResult(
                    "",
                    List.of(
                            new ToolUseContentBlock("tool-bash", "bash", "{\"command\":\"pwd\"}"),
                            new ToolUseContentBlock("tool-read", "ReadAlpha", "{\"filePath\":\"README.md\"}")
                    )
            );
        }
    }

    private static final class ConcurrentBashSiblingAbortRuntime implements ToolRuntime {
        @Override
        public List<ProviderToolDefinition> toolDefinitions() {
            return List.of(
                    new ProviderToolDefinition("bash", "Run bash", "{\"type\":\"object\"}"),
                    new ProviderToolDefinition("ReadAlpha", "Read a file", "{\"type\":\"object\"}")
            );
        }

        @Override
        public boolean isConcurrencySafe(String toolName, String inputJson) {
            return "bash".equals(toolName) || "ReadAlpha".equals(toolName);
        }

        @Override
        public ToolExecutionResult execute(
                ToolExecutionRequest request,
                ToolPermissionGateway permissionGateway,
                Consumer<ToolExecutionUpdate> updateConsumer
        ) {
            if ("bash".equals(request.toolName())) {
                return new ToolExecutionResult(request.toolUseId(), request.toolName(), "bash failed fast", true);
            }
            try {
                Thread.sleep(800);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "slow read finished", false);
        }
    }

    private static final class ConcurrentToolFailureProviderPlugin extends TestProviderPlugin {
        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            long toolResultCount = request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .filter(ToolResultContentBlock.class::isInstance)
                    .count();
            if (toolResultCount >= 2) {
                return new PromptResult("handled concurrent tool failure");
            }
            return new PromptResult(
                    "",
                    List.of(
                            new ToolUseContentBlock("tool-read", "Read", "{\"filePath\":\"README.md\"}"),
                            new ToolUseContentBlock("tool-grep", "Grep", "{\"pattern\":\"TODO\",\"path\":\".\"}")
                    )
            );
        }
    }

    private static final class ConcurrentToolFailureRuntime implements ToolRuntime {
        @Override
        public List<ProviderToolDefinition> toolDefinitions() {
            return List.of(
                    new ProviderToolDefinition("Read", "Read a file", "{\"type\":\"object\"}"),
                    new ProviderToolDefinition("Grep", "Search workspace text", "{\"type\":\"object\"}")
            );
        }

        @Override
        public ToolExecutionResult execute(
                ToolExecutionRequest request,
                ToolPermissionGateway permissionGateway,
                Consumer<ToolExecutionUpdate> updateConsumer
        ) {
            if ("Read".equals(request.toolName())) {
                throw new IllegalStateException("read exploded");
            }
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "grep ok", false);
        }
    }

    private static final class ToolLoopProviderPlugin extends TestProviderPlugin {
        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            boolean hasToolResult = request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .anyMatch(ToolResultContentBlock.class::isInstance);
            if (hasToolResult) {
                return new PromptResult("folder summary");
            }
            return new PromptResult(
                    "",
                    List.of(new ToolUseContentBlock("tool-1", "bash", "{\"command\":\"pwd\"}"))
            );
        }
    }

    private static final class StreamingAfterToolProviderPlugin extends TestProviderPlugin {
        private final CountDownLatch streamedDeltaSeen;
        private final CountDownLatch allowProviderReturn;
        private final AtomicInteger invocationCount = new AtomicInteger();

        private StreamingAfterToolProviderPlugin(CountDownLatch streamedDeltaSeen, CountDownLatch allowProviderReturn) {
            this.streamedDeltaSeen = streamedDeltaSeen;
            this.allowProviderReturn = allowProviderReturn;
        }

        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public PromptResult executePromptStream(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
            boolean hasToolResult = request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .anyMatch(ToolResultContentBlock.class::isInstance);
            if (!hasToolResult) {
                return new PromptResult(
                        "",
                        List.of(new ToolUseContentBlock("tool-1", "bash", "{\"command\":\"ls -1 ~/Desktop\"}"))
                );
            }

            invocationCount.incrementAndGet();
            if (eventConsumer != null) {
                eventConsumer.accept(new TextDeltaEvent("streamed after tool"));
            }
            streamedDeltaSeen.countDown();
            try {
                assertTrue(allowProviderReturn.await(1, TimeUnit.SECONDS), "Test did not release the provider return latch.");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Provider stream interrupted", exception);
            }
            return new PromptResult("streamed after tool");
        }
    }

    private static final class StreamingReadOnlyEarlyToolProviderPlugin extends TestProviderPlugin {
        private final CountDownLatch allowProviderReturn;

        private StreamingReadOnlyEarlyToolProviderPlugin(CountDownLatch allowProviderReturn) {
            this.allowProviderReturn = allowProviderReturn;
        }

        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public PromptResult executePromptStream(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
            boolean hasToolResult = request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .anyMatch(ToolResultContentBlock.class::isInstance);
            if (!hasToolResult) {
                if (eventConsumer != null) {
                    eventConsumer.accept(new ToolUseDiscoveredEvent(
                            "tool-read",
                            "Read",
                            "{\"filePath\":\"README.md\"}"
                    ));
                }
                try {
                    assertTrue(allowProviderReturn.await(1, TimeUnit.SECONDS), "Test did not release the provider return latch.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Provider stream interrupted", exception);
                }
                return new PromptResult(
                        "",
                        List.of(new ToolUseContentBlock("tool-read", "Read", "{\"filePath\":\"README.md\"}"))
                );
            }
            return new PromptResult("final answer after streamed tool");
        }
    }

    private static final class StreamingSerialEarlyToolProviderPlugin extends TestProviderPlugin {
        private final CountDownLatch allowProviderReturn;

        private StreamingSerialEarlyToolProviderPlugin(CountDownLatch allowProviderReturn) {
            this.allowProviderReturn = allowProviderReturn;
        }

        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public PromptResult executePromptStream(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
            boolean hasToolResult = request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .anyMatch(ToolResultContentBlock.class::isInstance);
            if (!hasToolResult) {
                if (eventConsumer != null) {
                    eventConsumer.accept(new ToolUseDiscoveredEvent(
                            "tool-write",
                            "Write",
                            "{\"file_path\":\"/tmp/demo.txt\",\"content\":\"hello\"}"
                    ));
                }
                try {
                    assertTrue(allowProviderReturn.await(1, TimeUnit.SECONDS), "Test did not release the provider return latch.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Provider stream interrupted", exception);
                }
                return new PromptResult(
                        "",
                        List.of(new ToolUseContentBlock(
                                "tool-write",
                                "Write",
                                "{\"file_path\":\"/tmp/demo.txt\",\"content\":\"hello\"}"
                        ))
                );
            }
            return new PromptResult("final answer after streamed serial tool");
        }
    }

    private static final class CapturingStreamingReadOnlyEarlyToolProviderPlugin extends TestProviderPlugin {
        private final CountDownLatch allowProviderReturn;
        private final java.util.ArrayList<PromptRequest> requests = new java.util.ArrayList<>();

        private CapturingStreamingReadOnlyEarlyToolProviderPlugin(CountDownLatch allowProviderReturn) {
            this.allowProviderReturn = allowProviderReturn;
        }

        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public PromptResult executePromptStream(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
            requests.add(request);
            boolean hasToolResult = request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .anyMatch(ToolResultContentBlock.class::isInstance);
            if (!hasToolResult) {
                if (eventConsumer != null) {
                    eventConsumer.accept(new ToolUseDiscoveredEvent(
                            "tool-read",
                            "Read",
                            "{\"filePath\":\"README.md\"}"
                    ));
                }
                try {
                    assertTrue(allowProviderReturn.await(1, TimeUnit.SECONDS), "Test did not release the provider return latch.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Provider stream interrupted", exception);
                }
                return new PromptResult(
                        "",
                        List.of(new ToolUseContentBlock("tool-read", "Read", "{\"filePath\":\"README.md\"}"))
                );
            }
            return new PromptResult("final answer after streamed tool");
        }

        private PromptRequest request(int index) {
            return requests.get(index);
        }
    }

    private static final class StreamingReadOnlyFailureProviderPlugin extends TestProviderPlugin {
        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public PromptResult executePromptStream(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
            boolean hasToolResult = request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .anyMatch(ToolResultContentBlock.class::isInstance);
            if (hasToolResult) {
                return new PromptResult("unexpected retry");
            }
            if (eventConsumer != null) {
                eventConsumer.accept(new ToolUseDiscoveredEvent(
                        "tool-read",
                        "Read",
                        "{\"filePath\":\"README.md\"}"
                ));
            }
            throw new IllegalStateException("Provider stream broke");
        }
    }

    private static final class InterruptingToolLoopProviderPlugin extends TestProviderPlugin {
        private final AtomicReference<Thread> queryThread;

        private InterruptingToolLoopProviderPlugin(AtomicReference<Thread> queryThread) {
            this.queryThread = queryThread;
        }

        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            queryThread.set(Thread.currentThread());
            return new PromptResult(
                    "",
                    List.of(
                            new ToolUseContentBlock("tool-block-1", "SerialBlockTool", "{\"command\":\"first\"}"),
                            new ToolUseContentBlock("tool-block-2", "SerialBlockTool", "{\"command\":\"second\"}")
                    )
            );
        }
    }

    private static final class InterruptingStreamingToolProviderPlugin extends TestProviderPlugin {
        private final AtomicReference<Thread> queryThread;

        private InterruptingStreamingToolProviderPlugin(AtomicReference<Thread> queryThread, CountDownLatch toolStarted) {
            this.queryThread = queryThread;
        }

        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public PromptResult executePromptStream(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
            queryThread.set(Thread.currentThread());
            if (eventConsumer != null) {
                eventConsumer.accept(new ToolUseDiscoveredEvent(
                        "tool-read",
                        "Read",
                        "{\"filePath\":\"README.md\"}"
                ));
            }
            return new PromptResult(
                    "",
                    List.of(new ToolUseContentBlock("tool-read", "Read", "{\"filePath\":\"README.md\"}"))
            );
        }
    }

    private static final class PermissionAwareToolRuntime implements ToolRuntime {
        @Override
        public List<ProviderToolDefinition> toolDefinitions() {
            return List.of(new ProviderToolDefinition(
                    "bash",
                    "Run a bash command",
                    "{\"type\":\"object\"}"
            ));
        }

        @Override
        public ToolExecutionResult execute(
                ToolExecutionRequest request,
                ToolPermissionGateway permissionGateway,
                Consumer<ToolExecutionUpdate> updateConsumer
        ) {
            updateConsumer.accept(new ToolExecutionUpdate(
                    request.toolUseId(),
                    request.toolName(),
                    "started",
                    "Queued bash command.",
                    request.inputJson(),
                    "",
                    "pwd",
                    "",
                    "",
                    false
            ));
            updateConsumer.accept(new ToolExecutionUpdate(
                    request.toolUseId(),
                    request.toolName(),
                    "permission_requested",
                    "Allow this local bash command?",
                    request.inputJson(),
                    "perm-1",
                    "pwd",
                    "",
                    "",
                    false
            ));

            ToolPermissionDecision decision = permissionGateway.requestPermission(new ToolPermissionRequest(
                    "perm-1",
                    request.toolUseId(),
                    request.toolName(),
                    request.inputJson(),
                    "pwd",
                    "Allow this local bash command?"
            ));
            if (!decision.allowed()) {
                updateConsumer.accept(new ToolExecutionUpdate(
                        request.toolUseId(),
                        request.toolName(),
                        "failed",
                        "Permission denied",
                        request.inputJson(),
                        "",
                        "pwd",
                        "",
                        "",
                        true
                ));
                return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Permission denied", true);
            }

            updateConsumer.accept(new ToolExecutionUpdate(
                    request.toolUseId(),
                    request.toolName(),
                    "progress",
                    "Running pwd",
                    request.inputJson(),
                    "",
                    "pwd",
                    "",
                    "",
                    false
            ));
            updateConsumer.accept(new ToolExecutionUpdate(
                    request.toolUseId(),
                    request.toolName(),
                    "completed",
                    "Command: pwd\nExit code: 0\n\n/Users/test",
                    request.inputJson(),
                    "",
                    "pwd",
                    "",
                    "",
                    false
            ));
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Command: pwd\nExit code: 0\n\n/Users/test", false);
        }
    }

    private static final class RetryToolRuntime implements ToolRuntime {
        @Override
        public List<ProviderToolDefinition> toolDefinitions() {
            return List.of(new ProviderToolDefinition(
                    "bash",
                    "Run a bash command",
                    "{\"type\":\"object\"}"
            ));
        }

        @Override
        public ToolExecutionResult execute(
                ToolExecutionRequest request,
                ToolPermissionGateway permissionGateway,
                Consumer<ToolExecutionUpdate> updateConsumer
        ) {
            return new ToolExecutionResult(
                    request.toolUseId(),
                    request.toolName(),
                    "Command: ls -1 ~/Desktop\nExit code: 0\n\nalpha\nbeta",
                    false
            );
        }
    }

    private static final class ToolThenAnswerProviderPlugin extends TestProviderPlugin {
        private int invocationCount;
        private final java.util.ArrayList<PromptRequest> requests = new java.util.ArrayList<>();

        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            requests.add(request);
            invocationCount += 1;
            if (invocationCount == 1) {
                return new PromptResult(
                        "",
                        List.of(new ToolUseContentBlock(
                                "tool-bash",
                                "bash",
                                "{\"command\":\"echo done\"}"
                        ))
                );
            }
            boolean hasToolResult = request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .anyMatch(ToolResultContentBlock.class::isInstance);
            assertTrue(hasToolResult);
            return new PromptResult("Final answer from tool output.");
        }

        private PromptRequest request(int index) {
            return requests.get(index);
        }
    }

    private static final class StreamingReadOnlyEarlyToolRuntime implements ToolRuntime {
        private final CountDownLatch startedLatch;

        private StreamingReadOnlyEarlyToolRuntime(CountDownLatch startedLatch) {
            this.startedLatch = startedLatch;
        }

        @Override
        public List<ProviderToolDefinition> toolDefinitions() {
            return List.of(new ProviderToolDefinition(
                    "Read",
                    "Read a file",
                    "{\"type\":\"object\"}"
            ));
        }

        @Override
        public boolean isConcurrencySafe(String toolName, String inputJson) {
            return "Read".equals(toolName);
        }

        @Override
        public ToolExecutionResult execute(
                ToolExecutionRequest request,
                ToolPermissionGateway permissionGateway,
                Consumer<ToolExecutionUpdate> updateConsumer
        ) {
            startedLatch.countDown();
            return new ToolExecutionResult(
                    request.toolUseId(),
                    request.toolName(),
                    "README contents",
                    false
            );
        }
    }

    private static final class StreamingSerialEarlyToolRuntime implements ToolRuntime {
        private final CountDownLatch startedLatch;

        private StreamingSerialEarlyToolRuntime(CountDownLatch startedLatch) {
            this.startedLatch = startedLatch;
        }

        @Override
        public List<ProviderToolDefinition> toolDefinitions() {
            return List.of(new ProviderToolDefinition(
                    "Write",
                    "Write a file",
                    "{\"type\":\"object\"}"
            ));
        }

        @Override
        public boolean isConcurrencySafe(String toolName, String inputJson) {
            return false;
        }

        @Override
        public ToolExecutionResult execute(
                ToolExecutionRequest request,
                ToolPermissionGateway permissionGateway,
                Consumer<ToolExecutionUpdate> updateConsumer
        ) {
            startedLatch.countDown();
            return new ToolExecutionResult(
                    request.toolUseId(),
                    request.toolName(),
                    "wrote file",
                    false
            );
        }
    }

    private static final class InterruptingSerialToolRuntime implements ToolRuntime {
        private final AtomicReference<Thread> queryThread;
        private final AtomicInteger executionCount = new AtomicInteger();

        private InterruptingSerialToolRuntime(AtomicReference<Thread> queryThread) {
            this.queryThread = queryThread;
        }

        @Override
        public List<ProviderToolDefinition> toolDefinitions() {
            return List.of(new ProviderToolDefinition(
                    "SerialBlockTool",
                    "Blocks until the query thread is interrupted",
                    "{\"type\":\"object\"}"
            ));
        }

        @Override
        public ToolExecutionResult execute(
                ToolExecutionRequest request,
                ToolPermissionGateway permissionGateway,
                Consumer<ToolExecutionUpdate> updateConsumer
        ) {
            updateConsumer.accept(new ToolExecutionUpdate(
                    request.toolUseId(),
                    request.toolName(),
                    "started",
                    "Starting serial tool.",
                    request.inputJson(),
                    "",
                    "serial-block",
                    "",
                    "",
                    false
            ));
            if (executionCount.incrementAndGet() == 1) {
                Thread thread = queryThread.get();
                assertNotNull(thread);
                thread.interrupt();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "serial tool finished", false);
        }
    }

    private static final class InterruptingStreamingReadOnlyToolRuntime implements ToolRuntime {
        private final AtomicReference<Thread> queryThread;
        private final CountDownLatch toolStarted;

        private InterruptingStreamingReadOnlyToolRuntime(AtomicReference<Thread> queryThread, CountDownLatch toolStarted) {
            this.queryThread = queryThread;
            this.toolStarted = toolStarted;
        }

        @Override
        public List<ProviderToolDefinition> toolDefinitions() {
            return List.of(new ProviderToolDefinition(
                    "Read",
                    "Read a file",
                    "{\"type\":\"object\"}"
            ));
        }

        @Override
        public boolean isConcurrencySafe(String toolName, String inputJson) {
            return "Read".equals(toolName);
        }

        @Override
        public ToolExecutionResult execute(
                ToolExecutionRequest request,
                ToolPermissionGateway permissionGateway,
                Consumer<ToolExecutionUpdate> updateConsumer
        ) {
            toolStarted.countDown();
            Thread thread = queryThread.get();
            assertNotNull(thread);
            thread.interrupt();
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "README contents", false);
        }
    }

    private static final class CapturingProviderPlugin extends TestProviderPlugin {
        private PromptRequest lastRequest;

        @Override
        public PromptResult executePromptStream(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
            this.lastRequest = request;
            return super.executePromptStream(request, eventConsumer);
        }

        PromptRequest lastRequest() {
            return lastRequest;
        }
    }

    private static final class CapturingToolAwareProviderPlugin extends TestProviderPlugin {
        private PromptRequest lastRequest;
        private final java.util.ArrayList<PromptRequest> requests = new java.util.ArrayList<>();

        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            this.lastRequest = request;
            this.requests.add(request);
            return new PromptResult("microcompact reply");
        }

        PromptRequest lastRequest() {
            return lastRequest;
        }

        PromptRequest firstRequest() {
            return requests.getFirst();
        }
    }

    private static final class SessionMemoryProviderPlugin extends TestProviderPlugin {
        private final AtomicInteger invocationCount = new AtomicInteger();

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            invocationCount.incrementAndGet();
            String lastUserMessage = request.messages().isEmpty() ? "" : request.messages().getLast().text();
            if (lastUserMessage.contains("update the session notes file")) {
                return new PromptResult("""
                        # Session Title
                        _A short and distinctive 5-10 word descriptive title for the session. Super info dense, no filler_
                        
                        Generated session memory
                        
                        # Current State
                        _What is actively being worked on right now? Pending tasks not yet completed. Immediate next steps._
                        
                        Generated session memory
                        
                        # Task specification
                        _What did the user ask to build? Any design decisions or other explanatory context_
                        
                        Long prompt extraction test
                        
                        # Files and Functions
                        _What are the important files? In short, what do they contain and why are they relevant?_
                        
                        None
                        
                        # Workflow
                        _What bash commands are usually run and in what order? How to interpret their output if not obvious?_
                        
                        None
                        
                        # Errors & Corrections
                        _Errors encountered and how they were fixed. What did the user correct? What approaches failed and should not be tried again?_
                        
                        None
                        
                        # Codebase and System Documentation
                        _What are the important system components? How do they work/fit together?_
                        
                        None
                        
                        # Learnings
                        _What has worked well? What has not? What to avoid? Do not duplicate items from other sections_
                        
                        None
                        
                        # Key results
                        _If the user asked a specific output such as an answer to a question, a table, or other document, repeat the exact result here_
                        
                        None
                        
                        # Worklog
                        _Step by step, what was attempted, done? Very terse summary for each step_
                        
                        1. Captured a long user turn.
                        """);
            }
            return new PromptResult("assistant reply");
        }

        int invocationCount() {
            return invocationCount.get();
        }
    }

    private static final class ReactiveCompactProviderPlugin extends TestProviderPlugin {
        private final AtomicInteger invocationCount = new AtomicInteger();

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            int invocation = invocationCount.incrementAndGet();
            String lastUserMessage = request.messages().isEmpty() ? "" : request.messages().getLast().text();
            if (lastUserMessage.contains("Your task is to create a detailed summary of the conversation so far")) {
                return new PromptResult("""
                        <analysis>
                        Compact
                        </analysis>
                        <summary>
                        1. Primary Request and Intent:
                        - Summarize previous work.
                        </summary>
                        """);
            }
            if (invocation == 1) {
                throw new IllegalStateException("Prompt is too long: 200000 tokens > 180000 maximum");
            }
            return new PromptResult("Recovered answer after reactive compact.");
        }

        int invocationCount() {
            return invocationCount.get();
        }
    }

    private static final class HistoryFilteringProviderPlugin extends TestProviderPlugin {
        private PromptRequest lastRequest;

        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            this.lastRequest = request;
            return new PromptResult("clean reply");
        }

        PromptRequest lastRequest() {
            return lastRequest;
        }
    }

    private static final class ToolRetryProviderPlugin extends TestProviderPlugin {
        private boolean sawRetryPrompt;

        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            boolean hasToolResult = request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .anyMatch(ToolResultContentBlock.class::isInstance);
            if (hasToolResult) {
                return new PromptResult("Desktop contains 2 project folders: alpha, beta.");
            }

            boolean hasRetryPrompt = request.messages().stream()
                    .filter(message -> message.role() == PromptMessageRole.SYSTEM)
                    .anyMatch(message -> message.text().contains("Do not ask the user to run local shell commands"));
            if (hasRetryPrompt) {
                sawRetryPrompt = true;
                return new PromptResult(
                        "",
                        List.of(new ToolUseContentBlock("tool-1", "bash", "{\"command\":\"ls -1 ~/Desktop\"}"))
                );
            }

            return new PromptResult(
                    "I can’t access your Desktop directly in this session. Please run ls -1 ~/Desktop and paste the output."
            );
        }

        boolean sawRetryPrompt() {
            return sawRetryPrompt;
        }
    }

    private static final class SessionEffectProviderPlugin extends TestProviderPlugin {
        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            boolean hasToolResult = request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .anyMatch(ToolResultContentBlock.class::isInstance);
            if (hasToolResult) {
                return new PromptResult("done");
            }
            return new PromptResult(
                    "",
                    List.of(
                            new ToolUseContentBlock("tool-enter", "EnterPlanMode", "{}"),
                            new ToolUseContentBlock(
                                    "tool-todo",
                                    "TodoWrite",
                                    "{\"todos\":[{\"content\":\"Review tests\",\"status\":\"in_progress\",\"activeForm\":\"Reviewing tests\"}]}"
                            )
                    )
            );
        }
    }

    private static final class DuplicateDesktopToolLoopProviderPlugin extends TestProviderPlugin {
        private boolean sawFinalAnswerRetryPrompt;

        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            boolean hasToolResult = request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .anyMatch(ToolResultContentBlock.class::isInstance);

            boolean hasFinalAnswerRetryPrompt = request.messages().stream()
                    .filter(message -> message.role() == PromptMessageRole.SYSTEM)
                    .anyMatch(message -> message.text().contains("Do not repeat the same tool call again."));
            if (hasFinalAnswerRetryPrompt) {
                sawFinalAnswerRetryPrompt = true;
                return new PromptResult("Desktop contains 25 folders.");
            }

            if (hasToolResult) {
                return new PromptResult(
                        "",
                        List.of(new ToolUseContentBlock(
                                "tool-desktop-repeat",
                                "bash",
                                "{\"command\":\"cd ~/Desktop && find . -maxdepth 1 -mindepth 1 -type d | wc -l && find . -maxdepth 1 -mindepth 1 -type d | sed 's|^./||'\"}"
                        ))
                );
            }

            return new PromptResult(
                    "",
                    List.of(new ToolUseContentBlock(
                            "tool-desktop-first",
                            "bash",
                            "{\"command\":\"cd ~/Desktop && find . -maxdepth 1 -mindepth 1 -type d | wc -l && find . -maxdepth 1 -mindepth 1 -type d | sed 's|^./||'\"}"
                    ))
            );
        }

        boolean sawFinalAnswerRetryPrompt() {
            return sawFinalAnswerRetryPrompt;
        }
    }

    private static final class CountingToolRuntime implements ToolRuntime {
        private final String expectedToolName;
        private final String expectedInputJson;
        private final String toolText;
        private int executionCount;

        private CountingToolRuntime(String expectedToolName, String expectedInputJson, String toolText) {
            this.expectedToolName = expectedToolName;
            this.expectedInputJson = expectedInputJson;
            this.toolText = toolText;
        }

        @Override
        public List<ProviderToolDefinition> toolDefinitions() {
            return List.of(new ProviderToolDefinition(
                    "bash",
                    "Run a bash command",
                    "{\"type\":\"object\"}"
            ));
        }

        @Override
        public ToolExecutionResult execute(
                ToolExecutionRequest request,
                ToolPermissionGateway permissionGateway,
                Consumer<ToolExecutionUpdate> updateConsumer
        ) {
            executionCount += 1;
            assertEquals(expectedToolName, request.toolName());
            assertEquals(expectedInputJson, request.inputJson());
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), toolText, false);
        }

        int executionCount() {
            return executionCount;
        }
    }

    private static final class MultiStepDesktopToolLoopProviderPlugin extends TestProviderPlugin {
        private int invocationCount;
        private boolean sawFinalAnswerRetryPrompt;
        private boolean sawNoToolsRetry;

        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            invocationCount += 1;
            boolean hasFinalAnswerRetryPrompt = request.messages().stream()
                    .filter(message -> message.role() == PromptMessageRole.SYSTEM)
                    .anyMatch(message -> message.text().contains("Do not repeat the same tool call again.")
                            || message.text().contains("Answer the user directly from the tool results"));
            if (hasFinalAnswerRetryPrompt) {
                sawFinalAnswerRetryPrompt = true;
                if (request.tools().isEmpty()) {
                    sawNoToolsRetry = true;
                }
                return new PromptResult("Desktop contains 25 folders.");
            }

            return switch (invocationCount) {
                case 1 -> new PromptResult(
                        "",
                        List.of(new ToolUseContentBlock(
                                "tool-desktop-step-1",
                                "bash",
                                "{\"command\":\"cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l\"}"
                        ))
                );
                case 2 -> new PromptResult(
                        "",
                        List.of(new ToolUseContentBlock(
                                "tool-desktop-step-2",
                                "bash",
                                "{\"command\":\"cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | sed 's|^./||'\"}"
                        ))
                );
                default -> new PromptResult(
                        "",
                        List.of(new ToolUseContentBlock(
                                "tool-desktop-step-3",
                                "bash",
                                "{\"command\":\"cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | head -n 5\"}"
                        ))
                );
            };
        }

        boolean sawFinalAnswerRetryPrompt() {
            return sawFinalAnswerRetryPrompt;
        }

        boolean sawNoToolsRetry() {
            return sawNoToolsRetry;
        }
    }

    private static final class EightStepDesktopToolLoopProviderPlugin extends TestProviderPlugin {
        private int invocationCount;
        private boolean sawFinalAnswerRetryPrompt;
        private boolean sawNoToolsRetry;

        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            invocationCount += 1;
            boolean hasFinalAnswerRetryPrompt = request.messages().stream()
                    .filter(message -> message.role() == PromptMessageRole.SYSTEM)
                    .anyMatch(message -> message.text().contains("Do not repeat the same tool call again.")
                            || message.text().contains("Answer the user directly from the tool results"));
            if (hasFinalAnswerRetryPrompt) {
                sawFinalAnswerRetryPrompt = true;
                if (request.tools().isEmpty()) {
                    sawNoToolsRetry = true;
                }
                return new PromptResult("Final answer after eight tool turns.");
            }

            int step = Math.min(invocationCount, 8);
            return new PromptResult(
                    "",
                    List.of(new ToolUseContentBlock(
                            "tool-desktop-step-" + step,
                            "bash",
                            "{\"command\":\"printf step-" + step + "\"}"
                    ))
            );
        }

        boolean sawFinalAnswerRetryPrompt() {
            return sawFinalAnswerRetryPrompt;
        }

        boolean sawNoToolsRetry() {
            return sawNoToolsRetry;
        }
    }

    private static final class CountingSequenceToolRuntime implements ToolRuntime {
        private final List<String> expectedInputs;
        private int executionCount;

        private CountingSequenceToolRuntime(List<String> expectedInputs) {
            this.expectedInputs = List.copyOf(expectedInputs);
        }

        @Override
        public List<ProviderToolDefinition> toolDefinitions() {
            return List.of(new ProviderToolDefinition(
                    "bash",
                    "Run a bash command",
                    "{\"type\":\"object\"}"
            ));
        }

        @Override
        public ToolExecutionResult execute(
                ToolExecutionRequest request,
                ToolPermissionGateway permissionGateway,
                Consumer<ToolExecutionUpdate> updateConsumer
        ) {
            executionCount += 1;
            assertEquals("bash", request.toolName());
            assertEquals(expectedInputs.get(executionCount - 1), request.inputJson());
            return new ToolExecutionResult(
                    request.toolUseId(),
                    request.toolName(),
                    "Command: " + request.inputJson() + "\nExit code: 0\n\nstub output",
                    false
            );
        }

        int executionCount() {
            return executionCount;
        }
    }

    private static final class EndlessDesktopToolLoopProviderPlugin extends TestProviderPlugin {
        private int invocationCount;

        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            invocationCount += 1;
            if (invocationCount >= 5) {
                return new PromptResult(
                        "",
                        List.of(new ToolUseContentBlock(
                                "tool-desktop-endless-" + invocationCount,
                                "bash",
                                "{\"command\":\"cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l\"}"
                        ))
                );
            }
            return new PromptResult(
                    "",
                    List.of(new ToolUseContentBlock(
                            "tool-desktop-endless-" + invocationCount,
                            "bash",
                            "{\"command\":\"cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l\"}"
                    ))
            );
        }
    }

    private static final class RealBashLoopProviderPlugin extends TestProviderPlugin {
        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public PromptResult executePrompt(PromptRequest request) {
            boolean hasToolResult = request.messages().stream()
                    .flatMap(message -> message.content().stream())
                    .anyMatch(ToolResultContentBlock.class::isInstance);
            if (hasToolResult) {
                return new PromptResult("workspace summary");
            }
            return new PromptResult(
                    "",
                    List.of(new ToolUseContentBlock("tool-real-bash", "bash", "{\"command\":\"pwd\"}"))
            );
        }
    }

    private static final class SessionEffectToolRuntime implements ToolRuntime {
        @Override
        public List<ProviderToolDefinition> toolDefinitions() {
            return List.of(
                    new ProviderToolDefinition("EnterPlanMode", "Enter plan mode", "{\"type\":\"object\"}"),
                    new ProviderToolDefinition("TodoWrite", "Update the todo list", "{\"type\":\"object\"}")
            );
        }

        @Override
        public ToolExecutionResult execute(
                ToolExecutionRequest request,
                ToolPermissionGateway permissionGateway,
                Consumer<ToolExecutionUpdate> updateConsumer
        ) {
            if ("EnterPlanMode".equals(request.toolName())) {
                return new ToolExecutionResult(
                        request.toolUseId(),
                        request.toolName(),
                        "Entered plan mode.",
                        false,
                        new ToolSessionEffect(true, null, null)
                );
            }
            if ("TodoWrite".equals(request.toolName())) {
                return new ToolExecutionResult(
                        request.toolUseId(),
                        request.toolName(),
                        "Todos updated.",
                        false,
                        new ToolSessionEffect(null, List.of(new TodoItem("Review tests", "in_progress", "Reviewing tests")), null)
                );
            }
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Unsupported tool", true);
        }
    }
}
