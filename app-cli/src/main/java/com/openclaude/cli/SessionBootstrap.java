package com.openclaude.cli;

import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.ConversationSessionStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class SessionBootstrap {
    private static final DateTimeFormatter RESUME_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private SessionBootstrap() {
    }

    public static String prepareInteractiveSession(
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore,
            Path currentDirectory,
            String resumeSessionId,
            InputStream inputStream,
            PrintStream output
    ) {
        Objects.requireNonNull(inputStream, "inputStream");
        Objects.requireNonNull(output, "output");
        SessionScope scope = SessionScope.capture(currentDirectory);

        if (resumeSessionId == null || resumeSessionId.isBlank()) {
            return createFreshSession(stateStore, sessionStore, scope);
        }

        if (OpenClaudeCommand.RESUME_PICKER.equals(resumeSessionId)) {
            List<ConversationSession> candidates = matchingSessions(sessionStore, scope);
            if (candidates.isEmpty()) {
                output.println("No resumable sessions found for the current directory. Starting a new session.");
                return createFreshSession(stateStore, sessionStore, scope);
            }
            ConversationSession selected = selectSessionForResume(candidates, scope, inputStream, output);
            stateStore.setActiveSession(selected.sessionId());
            return selected.sessionId();
        }

        ConversationSession session = sessionStore.load(resumeSessionId);
        stateStore.setActiveSession(session.sessionId());
        return session.sessionId();
    }

    public static String prepareStdioSession(
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore,
            Path currentDirectory,
            String resumeSessionId
    ) {
        SessionScope scope = SessionScope.capture(currentDirectory);
        if (resumeSessionId == null || resumeSessionId.isBlank()) {
            return createFreshSession(stateStore, sessionStore, scope);
        }

        ConversationSession session = sessionStore.load(resumeSessionId);
        stateStore.setActiveSession(session.sessionId());
        return session.sessionId();
    }

    private static String createFreshSession(
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore,
            SessionScope scope
    ) {
        String sessionId = UUID.randomUUID().toString();
        sessionStore.save(ConversationSession.create(
                sessionId,
                scope.workingDirectory(),
                scope.workspaceRoot()
        ));
        stateStore.setActiveSession(sessionId);
        return sessionId;
    }

    private static List<ConversationSession> matchingSessions(
            ConversationSessionStore sessionStore,
            SessionScope scope
    ) {
        List<ConversationSession> matches = new ArrayList<>();
        for (ConversationSession session : sessionStore.listSessions()) {
            if (scope.matches(session)) {
                matches.add(session);
            }
        }
        return List.copyOf(matches);
    }

    private static ConversationSession selectSessionForResume(
            List<ConversationSession> candidates,
            SessionScope scope,
            InputStream inputStream,
            PrintStream output
    ) {
        output.println("Resumable sessions for " + scope.displayPath() + ":");
        for (int index = 0; index < candidates.size(); index += 1) {
            ConversationSession session = candidates.get(index);
            output.printf(
                    Locale.ROOT,
                    "  %d. %s  %s  %d messages%n",
                    index + 1,
                    session.sessionId(),
                    RESUME_TIME_FORMATTER.format(session.updatedAt()),
                    session.messages().size()
            );
        }
        output.print("Choose a session number (press Enter for 1): ");
        output.flush();

        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                return candidates.getFirst();
            }
            int selectedIndex = Integer.parseInt(line.trim()) - 1;
            if (selectedIndex < 0 || selectedIndex >= candidates.size()) {
                output.println("Invalid selection. Resuming the most recent session.");
                return candidates.getFirst();
            }
            return candidates.get(selectedIndex);
        } catch (IOException | NumberFormatException exception) {
            output.println("Unable to read selection. Resuming the most recent session.");
            return candidates.getFirst();
        }
    }

    private record SessionScope(
            String workingDirectory,
            String workspaceRoot
    ) {
        static SessionScope capture(Path currentDirectory) {
            Path normalized = normalize(currentDirectory);
            Path resolvedWorkspaceRoot = resolveWorkspaceRoot(normalized);
            return new SessionScope(normalized.toString(), resolvedWorkspaceRoot.toString());
        }

        boolean matches(ConversationSession session) {
            if (session.workspaceRoot() != null && workspaceRoot != null) {
                return workspaceRoot.equals(normalize(Path.of(session.workspaceRoot())).toString());
            }
            if (session.workingDirectory() == null) {
                return false;
            }
            return workingDirectory.equals(normalize(Path.of(session.workingDirectory())).toString());
        }

        String displayPath() {
            return workspaceRoot == null ? workingDirectory : workspaceRoot;
        }

        private static Path resolveWorkspaceRoot(Path currentDirectory) {
            Path probe = currentDirectory;
            while (probe != null) {
                if (Files.exists(probe.resolve(".git"))) {
                    return probe;
                }
                probe = probe.getParent();
            }
            return currentDirectory;
        }

        private static Path normalize(Path path) {
            return path.toAbsolutePath().normalize();
        }
    }
}
