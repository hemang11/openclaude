package com.openclaude.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.ConversationSessionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionServiceTest {
    @Test
    void listCurrentScopeSessionsExcludesCrossRepoSessionsAndTheActiveSession() throws IOException {
        Path tempDirectory = Files.createTempDirectory("openclaude-session-service-scope");
        Path currentRepo = tempDirectory.resolve("current-repo");
        Path currentRepoSubdirectory = currentRepo.resolve("src");
        Path otherRepo = tempDirectory.resolve("other-repo");
        Files.createDirectories(currentRepo.resolve(".git"));
        Files.createDirectories(currentRepoSubdirectory);
        Files.createDirectories(otherRepo.resolve(".git"));

        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDirectory.resolve("state.json"));
        ConversationSessionStore sessionStore = new ConversationSessionStore(tempDirectory.resolve("sessions"));
        SessionService sessionService = new SessionService(stateStore, sessionStore, currentRepoSubdirectory);

        sessionStore.save(ConversationSession.create(
                "active-session",
                currentRepoSubdirectory.toAbsolutePath().normalize().toString(),
                currentRepo.toAbsolutePath().normalize().toString()
        ));
        sessionStore.save(ConversationSession.create(
                "same-workspace-session",
                currentRepo.toAbsolutePath().normalize().toString(),
                currentRepo.toAbsolutePath().normalize().toString()
        ));
        sessionStore.save(ConversationSession.create(
                "other-workspace-session",
                otherRepo.toAbsolutePath().normalize().toString(),
                otherRepo.toAbsolutePath().normalize().toString()
        ));
        stateStore.setActiveSession("active-session");

        List<SessionService.SessionListItem> sessions = sessionService.listCurrentScopeSessions();

        assertEquals(currentRepo.toAbsolutePath().normalize().toString(), sessionService.scopeDisplayPath());
        assertEquals(1, sessions.size());
        assertIterableEquals(List.of("same-workspace-session"), sessions.stream().map(SessionService.SessionListItem::sessionId).toList());
    }
}
