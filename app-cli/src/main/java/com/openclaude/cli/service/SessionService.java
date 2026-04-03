package com.openclaude.cli.service;

import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.SessionCompaction;
import com.openclaude.core.session.ConversationSessionStore;
import com.openclaude.core.session.SessionMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SessionService {
    private final OpenClaudeStateStore stateStore;
    private final ConversationSessionStore sessionStore;
    private final SessionScope scope;

    public SessionService(
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore,
            Path currentDirectory
    ) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.scope = SessionScope.capture(Objects.requireNonNull(currentDirectory, "currentDirectory"));
    }

    public List<SessionListItem> listCurrentScopeSessions() {
        String activeSessionId = stateStore.load().activeSessionId();
        List<SessionListItem> items = new ArrayList<>();
        for (ConversationSession session : sessionStore.listSessions()) {
            if (!scope.matches(session)) {
                continue;
            }
            if (session.sessionId().equals(activeSessionId)) {
                continue;
            }
            items.add(SessionListItem.from(session, false));
        }
        return List.copyOf(items);
    }

    public SessionDetail currentSession() {
        String activeSessionId = requireActiveSessionId();
        ConversationSession session = sessionStore.loadOrCreate(activeSessionId);
        return SessionDetail.from(session, true);
    }

    public String resumeSession(String sessionId) {
        ConversationSession session = sessionStore.load(sessionId);
        stateStore.setActiveSession(session.sessionId());
        return "Resumed session " + displayTitle(session) + ".";
    }

    public String renameActiveSession(String title) {
        String normalized = normalizeTitle(title);
        if (normalized == null) {
            throw new IllegalArgumentException("Session title must not be blank.");
        }
        String activeSessionId = requireActiveSessionId();
        ConversationSession session = sessionStore.loadOrCreate(activeSessionId);
        sessionStore.save(session.withTitle(normalized));
        return "Session renamed to: " + normalized;
    }

    public String setPlanMode(boolean enabled) {
        String activeSessionId = requireActiveSessionId();
        ConversationSession session = sessionStore.loadOrCreate(activeSessionId);
        sessionStore.save(session.withPlanMode(enabled));
        return enabled ? "Enabled plan mode." : "Exited plan mode.";
    }

    public String clearConversation() {
        String sessionId = UUID.randomUUID().toString();
        sessionStore.save(ConversationSession.create(sessionId, scope.workingDirectory(), scope.workspaceRoot()));
        stateStore.setActiveSession(sessionId);
        return "Cleared conversation history and started a new session.";
    }

    public String rewindToMessage(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("Message id is required.");
        }
        String activeSessionId = requireActiveSessionId();
        ConversationSession session = sessionStore.loadOrCreate(activeSessionId);
        int rewindIndex = -1;
        for (int index = 0; index < session.messages().size(); index += 1) {
            SessionMessage message = session.messages().get(index);
            if (message instanceof SessionMessage.UserMessage && message.id().equals(messageId)) {
                rewindIndex = index;
                break;
            }
        }
        if (rewindIndex < 0) {
            throw new IllegalArgumentException("Could not find a rewind checkpoint for message: " + messageId);
        }
        sessionStore.save(session.withMessages(session.messages().subList(0, rewindIndex)));
        return "Rewound conversation to the selected checkpoint.";
    }

    public String scopeDisplayPath() {
        return scope.displayPath();
    }

    public Path scopeWorkspaceRoot() {
        return Path.of(scope.workspaceRoot()).toAbsolutePath().normalize();
    }

    private String requireActiveSessionId() {
        String sessionId = stateStore.load().activeSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("No active session.");
        }
        return sessionId;
    }

    private static String normalizeTitle(String title) {
        return title == null || title.isBlank() ? null : title.trim();
    }

    private static String displayTitle(ConversationSession session) {
        if (session.title() != null && !session.title().isBlank()) {
            return session.title();
        }
        String preview = previewText(session);
        if (!preview.isBlank()) {
            return preview;
        }
        return session.sessionId();
    }

    private static String previewText(ConversationSession session) {
        for (SessionMessage message : SessionCompaction.messagesAfterCompactBoundary(session.messages())) {
            if (message instanceof SessionMessage.UserMessage userMessage && !userMessage.compactSummary() && !userMessage.text().isBlank()) {
                return summarize(userMessage.text(), 80);
            }
        }
        return "";
    }

    private static String lastAssistantPreview(ConversationSession session) {
        List<SessionMessage> activeMessages = SessionCompaction.messagesAfterCompactBoundary(session.messages());
        for (int index = activeMessages.size() - 1; index >= 0; index -= 1) {
            SessionMessage message = activeMessages.get(index);
            if (message instanceof SessionMessage.AssistantMessage assistantMessage && !assistantMessage.text().isBlank()) {
                return summarize(assistantMessage.text(), 100);
            }
        }
        return "";
    }

    private static String summarize(String text, int maxLength) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength - 1) + "…";
    }

    public record SessionListItem(
            String sessionId,
            String title,
            String preview,
            Instant updatedAt,
            int messageCount,
            String workingDirectory,
            String workspaceRoot,
            boolean active
    ) {
        static SessionListItem from(ConversationSession session, boolean active) {
            List<SessionMessage> activeMessages = SessionCompaction.messagesAfterCompactBoundary(session.messages());
            return new SessionListItem(
                    session.sessionId(),
                    displayTitle(session),
                    lastAssistantPreview(session).isBlank() ? previewText(session) : lastAssistantPreview(session),
                    session.updatedAt(),
                    activeMessages.size(),
                    session.workingDirectory(),
                    session.workspaceRoot(),
                    active
            );
        }
    }

    public record SessionDetail(
            String sessionId,
            String title,
            String preview,
            Instant createdAt,
            Instant updatedAt,
            int messageCount,
            String workingDirectory,
            String workspaceRoot,
            boolean active
    ) {
        static SessionDetail from(ConversationSession session, boolean active) {
            List<SessionMessage> activeMessages = SessionCompaction.messagesAfterCompactBoundary(session.messages());
            return new SessionDetail(
                    session.sessionId(),
                    displayTitle(session),
                    lastAssistantPreview(session).isBlank() ? previewText(session) : lastAssistantPreview(session),
                    session.createdAt(),
                    session.updatedAt(),
                    activeMessages.size(),
                    session.workingDirectory(),
                    session.workspaceRoot(),
                    active
            );
        }
    }

    private record SessionScope(String workingDirectory, String workspaceRoot) {
        static SessionScope capture(Path currentDirectory) {
            Path normalized = currentDirectory.toAbsolutePath().normalize();
            Path resolvedWorkspaceRoot = resolveWorkspaceRoot(normalized);
            return new SessionScope(normalized.toString(), resolvedWorkspaceRoot.toString());
        }

        boolean matches(ConversationSession session) {
            if (session.workspaceRoot() != null && workspaceRoot != null) {
                return workspaceRoot.equals(Path.of(session.workspaceRoot()).toAbsolutePath().normalize().toString());
            }
            if (session.workingDirectory() == null) {
                return false;
            }
            return workingDirectory.equals(Path.of(session.workingDirectory()).toAbsolutePath().normalize().toString());
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
    }
}
