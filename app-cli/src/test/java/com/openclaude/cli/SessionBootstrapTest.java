package com.openclaude.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.ConversationSessionStore;
import com.openclaude.core.session.SessionMemoryState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionBootstrapTest {
    @Test
    void prepareInteractiveSessionCreatesFreshSessionForCurrentDirectoryWhenResumeIsNotRequested() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-session-bootstrap");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        Path currentDirectory = tempDirectory.resolve("repo");
        Files.createDirectories(currentDirectory.resolve(".git"));

        String nextSessionId = SessionBootstrap.prepareInteractiveSession(
                stateStore,
                sessionStore,
                currentDirectory,
                null,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertEquals(nextSessionId, stateStore.load().activeSessionId());
        ConversationSession session = sessionStore.load(nextSessionId);
        assertEquals(currentDirectory.toAbsolutePath().normalize().toString(), session.workingDirectory());
        assertEquals(currentDirectory.toAbsolutePath().normalize().toString(), session.workspaceRoot());
    }

    @Test
    void prepareInteractiveSessionUsesExplicitResumeSessionWhenProvided() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-session-bootstrap-resume");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        ConversationSession existing = ConversationSession.create(
                "resume-me",
                tempDirectory.resolve("repo").toString(),
                tempDirectory.resolve("repo").toString()
        );
        sessionStore.save(existing);

        String resumedSessionId = SessionBootstrap.prepareInteractiveSession(
                stateStore,
                sessionStore,
                tempDirectory.resolve("repo"),
                "resume-me",
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertEquals("resume-me", resumedSessionId);
        assertEquals("resume-me", stateStore.load().activeSessionId());
    }

    @Test
    void prepareInteractiveSessionListsCurrentWorkspaceSessionsFirstWhenResumeHasNoId() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-session-bootstrap-picker");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        Path repoRoot = tempDirectory.resolve("repo");
        Files.createDirectories(repoRoot.resolve(".git"));
        Path subdirectory = repoRoot.resolve("src");
        Files.createDirectories(subdirectory);

        ConversationSession older = new ConversationSession(
                "older-session",
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-04-01T00:00:00Z"),
                null,
                subdirectory.toAbsolutePath().normalize().toString(),
                repoRoot.toAbsolutePath().normalize().toString(),
                List.of(),
                false,
                List.of(),
                java.util.Map.of(),
                SessionMemoryState.empty()
        );
        ConversationSession newer = new ConversationSession(
                "newer-session",
                Instant.parse("2026-04-02T00:00:00Z"),
                Instant.parse("2026-04-02T00:00:00Z"),
                null,
                repoRoot.toAbsolutePath().normalize().toString(),
                repoRoot.toAbsolutePath().normalize().toString(),
                List.of(),
                false,
                List.of(),
                java.util.Map.of(),
                SessionMemoryState.empty()
        );
        sessionStore.save(older);
        sessionStore.save(newer);

        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        String resumedSessionId = SessionBootstrap.prepareInteractiveSession(
                stateStore,
                sessionStore,
                subdirectory,
                OpenClaudeCommand.RESUME_PICKER,
                new ByteArrayInputStream("\n".getBytes()),
                new PrintStream(outputBuffer)
        );

        assertEquals("newer-session", resumedSessionId);
        String output = outputBuffer.toString();
        assertTrue(output.contains("Resumable sessions for " + repoRoot.toAbsolutePath().normalize()));
        assertTrue(output.contains("newer-session"));
        assertTrue(output.contains("older-session"));
    }

    @Test
    void prepareInteractiveSessionDoesNotBleedCrossDirectorySessionsIntoCurrentRepo() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-session-bootstrap-isolation");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        Path currentRepo = tempDirectory.resolve("current-repo");
        Path otherRepo = tempDirectory.resolve("other-repo");
        Files.createDirectories(currentRepo.resolve(".git"));
        Files.createDirectories(otherRepo.resolve(".git"));

        sessionStore.save(ConversationSession.create(
                "other-session",
                otherRepo.toAbsolutePath().normalize().toString(),
                otherRepo.toAbsolutePath().normalize().toString()
        ));

        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        String sessionId = SessionBootstrap.prepareInteractiveSession(
                stateStore,
                sessionStore,
                currentRepo,
                OpenClaudeCommand.RESUME_PICKER,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(outputBuffer)
        );

        assertNotEquals("other-session", sessionId);
        assertEquals(sessionId, stateStore.load().activeSessionId());
        assertTrue(outputBuffer.toString().contains("No resumable sessions found for the current directory"));
    }

    @Test
    void prepareStdioSessionCreatesFreshSessionWithoutReusingGlobalActiveSession() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-session-bootstrap-stdio");
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        Path currentRepo = tempDirectory.resolve("repo");
        Files.createDirectories(currentRepo.resolve(".git"));
        stateStore.setActiveSession("old-session");

        String sessionId = SessionBootstrap.prepareStdioSession(stateStore, sessionStore, currentRepo, null);

        assertNotEquals("old-session", sessionId);
        ConversationSession session = sessionStore.load(sessionId);
        assertEquals(currentRepo.toAbsolutePath().normalize().toString(), session.workspaceRoot());
    }
}
