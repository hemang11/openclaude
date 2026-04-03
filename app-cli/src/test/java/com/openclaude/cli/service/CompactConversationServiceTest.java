package com.openclaude.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.instructions.AgentsInstructionsLoader;
import com.openclaude.core.provider.ProviderConnectionState;
import com.openclaude.core.provider.ProviderRegistry;
import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.SessionCompaction;
import com.openclaude.core.session.ConversationSessionStore;
import com.openclaude.core.session.FileReadState;
import com.openclaude.core.session.SessionAttachment;
import com.openclaude.core.session.SessionMessage;
import com.openclaude.core.session.SessionMemoryState;
import com.openclaude.core.sessionmemory.SessionMemoryService;
import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.PromptRequest;
import com.openclaude.provider.spi.PromptResult;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompactConversationServiceTest {
    @Test
    void compactAppendsBoundaryAndSummaryWhilePreservingTranscriptHistory() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-compact-service");
        Path trackedFile = tempDirectory.resolve("Tracked.java");
        Files.writeString(trackedFile, "class Tracked {}\n");
        String longContext = "repo structure detail ".repeat(250);
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        CapturingCompactionProviderPlugin providerPlugin = new CapturingCompactionProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        CompactConversationService compactConversationService = new CompactConversationService(
                stateStore,
                sessionStore,
                providerRegistry,
                new AgentsInstructionsLoader(),
                new CompactHooksExecutor(),
                new PostCompactCleanup(),
                new SessionMemoryService(sessionStore, tempDirectory.resolve("session-memory"))
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:OPENAI_API_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("gpt-5.4");
        stateStore.setActiveSession("session-1");

        ConversationSession originalSession = ConversationSession.create(
                        "session-1",
                        tempDirectory.toString(),
                        tempDirectory.toString()
                )
                .append(SessionMessage.user("Review the repo structure"))
                .append(SessionMessage.assistant("I inspected the workspace. " + longContext, ProviderId.OPENAI, "gpt-5.4"))
                .append(SessionMessage.user("Now summarize the work so far. " + longContext))
                .withPlanMode(true)
                .withReadFileState(Map.of(
                        trackedFile.toAbsolutePath().normalize().toString(),
                        new FileReadState("class Tracked {}\n", Files.getLastModifiedTime(trackedFile).toMillis(), null, null, false)
                ));
        sessionStore.save(originalSession);

        CompactConversationService.CompactResult result = compactConversationService.compact("");
        ConversationSession compactedSession = sessionStore.load("session-1");

        assertEquals("Compacted conversation history into a summary.", result.message());
        assertEquals(7, compactedSession.messages().size());
        assertInstanceOf(SessionMessage.UserMessage.class, compactedSession.messages().get(0));
        assertInstanceOf(SessionMessage.AssistantMessage.class, compactedSession.messages().get(1));
        assertInstanceOf(SessionMessage.UserMessage.class, compactedSession.messages().get(2));
        SessionMessage.CompactBoundaryMessage boundaryMessage = assertInstanceOf(
                SessionMessage.CompactBoundaryMessage.class,
                compactedSession.messages().get(3)
        );
        SessionMessage.UserMessage summaryMessage = assertInstanceOf(
                SessionMessage.UserMessage.class,
                compactedSession.messages().get(4)
        );
        SessionMessage.AttachmentMessage restoredFileAttachment = assertInstanceOf(
                SessionMessage.AttachmentMessage.class,
                compactedSession.messages().get(5)
        );
        SessionMessage.AttachmentMessage planModeAttachment = assertInstanceOf(
                SessionMessage.AttachmentMessage.class,
                compactedSession.messages().get(6)
        );

        assertEquals("Conversation compacted", boundaryMessage.text());
        assertEquals("manual", boundaryMessage.trigger());
        assertTrue(boundaryMessage.messagesSummarized() >= 3);
        assertTrue(summaryMessage.compactSummary());
        assertTrue(summaryMessage.text().contains("This session is being continued from a previous conversation"));
        assertTrue(summaryMessage.text().contains("Summary:"));
        assertEquals(
                List.of(boundaryMessage, summaryMessage, restoredFileAttachment, planModeAttachment),
                SessionCompaction.messagesAfterCompactBoundary(compactedSession.messages())
        );
        assertTrue(compactedSession.readFileState().isEmpty());
        assertTrue(restoredFileAttachment.attachment() instanceof SessionAttachment.RestoredFileAttachment);
        assertTrue(restoredFileAttachment.text().contains("Tracked.java"));
        assertTrue(planModeAttachment.attachment() instanceof SessionAttachment.PlanModeAttachment);

        assertTrue(providerPlugin.lastRequest.tools().isEmpty());
    }

    @Test
    void compactIncludesAgentsInstructionsAndAttachmentProjectionInPromptRequest() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-compact-service-instructions");
        Path workspace = tempDirectory.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "workspace compact rule");

        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        CapturingCompactionProviderPlugin providerPlugin = new CapturingCompactionProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        CompactConversationService compactConversationService = new CompactConversationService(
                stateStore,
                sessionStore,
                providerRegistry,
                new AgentsInstructionsLoader(),
                new CompactHooksExecutor(),
                new PostCompactCleanup(),
                new SessionMemoryService(sessionStore, tempDirectory.resolve("session-memory"))
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:OPENAI_API_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("gpt-5.4");
        stateStore.setActiveSession("session-2");

        ConversationSession originalSession = ConversationSession.create(
                        "session-2",
                        workspace.toString(),
                        workspace.toString()
                )
                .append(SessionMessage.user("Inspect the repo"))
                .append(SessionMessage.assistant("Done.", ProviderId.OPENAI, "gpt-5.4"))
                .append(SessionMessage.attachment(new SessionAttachment.PlanModeAttachment()))
                .append(SessionMessage.user("Compact this"));
        sessionStore.save(originalSession);

        compactConversationService.compact("");

        PromptRequest request = providerPlugin.lastRequest;
        assertTrue(request.messages().stream().anyMatch(message -> message.text().contains("workspace compact rule")));
        assertTrue(request.messages().stream().anyMatch(message -> message.text().contains("Plan mode is still active after compaction")));
    }

    @Test
    void compactExecutesConfiguredCompactHooksAndPersistsSessionStartContext() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-compact-service-hooks");
        Path workspace = tempDirectory.resolve("workspace");
        Files.createDirectories(workspace.resolve(".openclaude"));
        String longContext = "workspace summary detail ".repeat(250);
        Files.writeString(
                workspace.resolve(".openclaude").resolve("settings.json"),
                """
                {
                  "hooks": {
                    "PreCompact": [
                      {
                        "matcher": "manual",
                        "hooks": [
                          { "type": "command", "command": "printf 'extra compact instruction'" }
                        ]
                      }
                    ],
                    "SessionStart": [
                      {
                        "matcher": "compact",
                        "hooks": [
                          { "type": "command", "command": "printf 'session start context'" }
                        ]
                      }
                    ],
                    "PostCompact": [
                      {
                        "matcher": "manual",
                        "hooks": [
                          { "type": "command", "command": "printf 'post compact notice'" }
                        ]
                      }
                    ]
                  }
                }
                """
        );

        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        CapturingCompactionProviderPlugin providerPlugin = new CapturingCompactionProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        CompactConversationService compactConversationService = new CompactConversationService(
                stateStore,
                sessionStore,
                providerRegistry,
                new AgentsInstructionsLoader(),
                new CompactHooksExecutor(new CompactHookConfigLoader(List.of())),
                new PostCompactCleanup(),
                new SessionMemoryService(sessionStore, tempDirectory.resolve("session-memory"))
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:OPENAI_API_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("gpt-5.4");
        stateStore.setActiveSession("session-hooks");

        ConversationSession originalSession = ConversationSession.create(
                        "session-hooks",
                        workspace.toString(),
                        workspace.toString()
                )
                .append(SessionMessage.user("Summarize the conversation"))
                .append(SessionMessage.assistant("Working. " + longContext, ProviderId.OPENAI, "gpt-5.4"))
                .append(SessionMessage.user("Compact it. " + longContext));
        sessionStore.save(originalSession);

        CompactConversationService.CompactResult result = compactConversationService.compact("");
        ConversationSession compactedSession = sessionStore.load("session-hooks");

        assertTrue(result.message().contains("PreCompact [printf 'extra compact instruction'] completed successfully: extra compact instruction"));
        assertTrue(result.message().contains("PostCompact [printf 'post compact notice'] completed successfully: post compact notice"));
        assertTrue(providerPlugin.lastRequest.messages().stream().anyMatch(message -> message.text().contains("extra compact instruction")));
        assertTrue(compactedSession.messages().stream().anyMatch(message ->
                message instanceof SessionMessage.AttachmentMessage attachmentMessage
                        && attachmentMessage.attachment() instanceof SessionAttachment.HookAdditionalContextAttachment hookAttachment
                        && hookAttachment.content().contains("session start context")
        ));
    }

    @Test
    void compactUsesSessionMemoryWhenAvailableAndPreservesRecentTail() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-compact-service-session-memory");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        SessionMemoryService sessionMemoryService = new SessionMemoryService(sessionStore, tempDirectory.resolve("session-memory"));
        CapturingCompactionProviderPlugin providerPlugin = new CapturingCompactionProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        CompactConversationService compactConversationService = new CompactConversationService(
                stateStore,
                sessionStore,
                providerRegistry,
                new AgentsInstructionsLoader(),
                new CompactHooksExecutor(new CompactHookConfigLoader(List.of())),
                new PostCompactCleanup(),
                sessionMemoryService
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:OPENAI_API_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("gpt-5.4");
        stateStore.setActiveSession("session-memory");

        String heavyTail = "tail ".repeat(2_500);
        String heavyEarlier = "earlier ".repeat(2_500);
        ConversationSession originalSession = ConversationSession.create(
                        "session-memory",
                        tempDirectory.toString(),
                        tempDirectory.toString()
                )
                .append(SessionMessage.user("Earlier request " + heavyEarlier))
                .append(SessionMessage.assistant("Earlier answer " + heavyEarlier, ProviderId.OPENAI, "gpt-5.4"))
                .append(SessionMessage.user("Tail 1 " + heavyTail))
                .append(SessionMessage.assistant("Tail answer 1 " + heavyTail, ProviderId.OPENAI, "gpt-5.4"))
                .append(SessionMessage.user("Tail 2 " + heavyTail))
                .append(SessionMessage.assistant("Tail answer 2 " + heavyTail, ProviderId.OPENAI, "gpt-5.4"))
                .append(SessionMessage.user("Tail 3 " + heavyTail))
                .withSessionMemoryState(new SessionMemoryState(
                        true,
                        12_000,
                        null,
                        null,
                        Instant.parse("2026-04-02T00:00:00Z")
                ));
        SessionMessage.AssistantMessage summarizedAssistant = (SessionMessage.AssistantMessage) originalSession.messages().get(1);
        originalSession = originalSession.withSessionMemoryState(new SessionMemoryState(
                true,
                12_000,
                summarizedAssistant.id(),
                null,
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        sessionStore.save(originalSession);
        Files.createDirectories(sessionMemoryService.memoryFile("session-memory").getParent());
        Files.writeString(
                sessionMemoryService.memoryFile("session-memory"),
                """
                # Session Title
                _A short and distinctive 5-10 word descriptive title for the session. Super info dense, no filler_

                Session memory title

                # Current State
                _What is actively being worked on right now? Pending tasks not yet completed. Immediate next steps._

                Session memory summary

                # Task specification
                _What did the user ask to build? Any design decisions or other explanatory context_

                Session memory summary

                # Files and Functions
                _What are the important files? In short, what do they contain and why are they relevant?_

                Session memory summary

                # Workflow
                _What bash commands are usually run and in what order? How to interpret their output if not obvious?_

                Session memory summary

                # Errors & Corrections
                _Errors encountered and how they were fixed. What did the user correct? What approaches failed and should not be tried again?_

                Session memory summary

                # Codebase and System Documentation
                _What are the important system components? How do they work/fit together?_

                Session memory summary

                # Learnings
                _What has worked well? What has not? What to avoid? Do not duplicate items from other sections_

                Session memory summary

                # Key results
                _If the user asked a specific output such as an answer to a question, a table, or other document, repeat the exact result here_

                Session memory summary

                # Worklog
                _Step by step, what was attempted, done? Very terse summary for each step_

                Session memory summary
                """
        );

        CompactConversationService.CompactResult result = compactConversationService.compact("");
        ConversationSession compactedSession = sessionStore.load("session-memory");
        List<SessionMessage> projected = SessionCompaction.messagesAfterCompactBoundary(compactedSession.messages());

        assertEquals("Compacted conversation history using session memory.", result.message());
        assertEquals(0, providerPlugin.invocationCount());
        assertInstanceOf(SessionMessage.CompactBoundaryMessage.class, projected.get(0));
        SessionMessage.UserMessage summaryMessage = assertInstanceOf(SessionMessage.UserMessage.class, projected.get(1));
        assertTrue(summaryMessage.compactSummary());
        assertTrue(summaryMessage.text().contains("Session memory summary"));
        assertEquals(projected.size(), projected.stream().map(SessionMessage::id).distinct().count());
        assertTrue(projected.stream().anyMatch(message ->
                message instanceof SessionMessage.UserMessage userMessage
                        && userMessage.text().startsWith("Tail 1")
        ));
    }

    @Test
    void compactSkipsPersistenceWhenProjectedContextWouldGrow() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-compact-service-no-benefit");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        CapturingCompactionProviderPlugin providerPlugin = new CapturingCompactionProviderPlugin("""
                <analysis>
                Draft
                </analysis>
                <summary>
                %s
                </summary>
                """.formatted("very long summary ".repeat(200)));
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        CompactConversationService compactConversationService = new CompactConversationService(
                stateStore,
                sessionStore,
                providerRegistry,
                new AgentsInstructionsLoader(),
                new CompactHooksExecutor(),
                new PostCompactCleanup(),
                new SessionMemoryService(sessionStore, tempDirectory.resolve("session-memory"))
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:OPENAI_API_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("gpt-5.4");
        stateStore.setActiveSession("session-no-benefit");

        ConversationSession originalSession = ConversationSession.create(
                        "session-no-benefit",
                        tempDirectory.toString(),
                        tempDirectory.toString()
                )
                .append(SessionMessage.user("Hi"))
                .append(SessionMessage.assistant("Hello.", ProviderId.OPENAI, "gpt-5.4"))
                .append(SessionMessage.user("Compact this"));
        sessionStore.save(originalSession);

        CompactConversationService.CompactResult result = compactConversationService.compact("");
        ConversationSession persistedSession = sessionStore.load("session-no-benefit");

        assertTrue(result.message().startsWith("Skipped compaction because it would not reduce context"));
        assertEquals(originalSession.messages(), persistedSession.messages());
    }

    @Test
    void compactContinuesWhenPreCompactHookFailsAndSurfacesFailureMessage() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-compact-service-hook-failure");
        Path workspace = tempDirectory.resolve("workspace");
        Files.createDirectories(workspace.resolve(".openclaude"));
        String longContext = "hook failure context ".repeat(250);
        Files.writeString(
                workspace.resolve(".openclaude").resolve("settings.json"),
                """
                {
                  "hooks": {
                    "PreCompact": [
                      {
                        "matcher": "manual",
                        "hooks": [
                          { "type": "command", "command": "printf 'boom' >&2; exit 1" }
                        ]
                      }
                    ]
                  }
                }
                """
        );

        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        CapturingCompactionProviderPlugin providerPlugin = new CapturingCompactionProviderPlugin();
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(providerPlugin));
        CompactConversationService compactConversationService = new CompactConversationService(
                stateStore,
                sessionStore,
                providerRegistry,
                new AgentsInstructionsLoader(),
                new CompactHooksExecutor(new CompactHookConfigLoader(List.of())),
                new PostCompactCleanup(),
                new SessionMemoryService(sessionStore, tempDirectory.resolve("session-memory"))
        );

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:OPENAI_API_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.setActiveProvider(ProviderId.OPENAI);
        stateStore.setActiveModel("gpt-5.4");
        stateStore.setActiveSession("session-hook-failure");

        ConversationSession originalSession = ConversationSession.create(
                        "session-hook-failure",
                        workspace.toString(),
                        workspace.toString()
                )
                .append(SessionMessage.user("Summarize"))
                .append(SessionMessage.assistant("Done. " + longContext, ProviderId.OPENAI, "gpt-5.4"))
                .append(SessionMessage.user("Compact " + longContext));
        sessionStore.save(originalSession);

        CompactConversationService.CompactResult result = compactConversationService.compact("");

        assertTrue(result.message().contains("PreCompact [printf 'boom' >&2; exit 1] failed: boom"));
        assertTrue(result.message().contains("Compacted conversation history into a summary."));
    }

    @Test
    void formatCompactSummaryStripsAnalysisAndKeepsSummaryContent() {
        String formatted = CompactConversationService.formatCompactSummary("""
                <analysis>
                scratch
                </analysis>

                <summary>
                1. Primary Request and Intent:
                - Keep this section
                </summary>
                """);

        assertFalse(formatted.contains("<analysis>"));
        assertFalse(formatted.contains("</summary>"));
        assertTrue(formatted.startsWith("Summary:"));
        assertTrue(formatted.contains("Primary Request and Intent"));
    }

    private static final class CapturingCompactionProviderPlugin implements ProviderPlugin {
        private PromptRequest lastRequest;
        private int invocationCount;
        private final String summary;

        private CapturingCompactionProviderPlugin() {
            this("""
                    <analysis>
                    Draft
                    </analysis>
                    <summary>
                    1. Primary Request and Intent:
                    - Repo inspection
                    </summary>
                    """);
        }

        private CapturingCompactionProviderPlugin(String summary) {
            this.summary = summary;
        }

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
            return List.of(new ModelDescriptor("gpt-5.4", "GPT-5.4", ProviderId.OPENAI));
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
            invocationCount += 1;
            lastRequest = request;
            return new PromptResult(summary);
        }

        int invocationCount() {
            return invocationCount;
        }
    }
}
