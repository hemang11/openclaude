package com.openclaude.core.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record ConversationSession(
        String sessionId,
        Instant createdAt,
        Instant updatedAt,
        String title,
        String workingDirectory,
        String workspaceRoot,
        List<SessionMessage> messages,
        boolean planMode,
        List<TodoItem> todos,
        Map<String, FileReadState> readFileState,
        SessionMemoryState sessionMemoryState
) {
    public ConversationSession {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        title = normalizeText(title);
        workingDirectory = normalizePath(workingDirectory);
        workspaceRoot = normalizePath(workspaceRoot);
        messages = messages == null ? List.of() : List.copyOf(messages);
        todos = todos == null ? List.of() : List.copyOf(todos);
        readFileState = normalizeReadFileState(readFileState);
        sessionMemoryState = sessionMemoryState == null ? SessionMemoryState.empty() : sessionMemoryState;
    }

    public static ConversationSession create(String sessionId) {
        return create(sessionId, null, null);
    }

    public static ConversationSession create(String sessionId, String workingDirectory, String workspaceRoot) {
        Instant now = Instant.now();
        return new ConversationSession(
                sessionId,
                now,
                now,
                null,
                workingDirectory,
                workspaceRoot,
                List.of(),
                false,
                List.of(),
                Map.of(),
                SessionMemoryState.empty()
        );
    }

    public ConversationSession append(SessionMessage message) {
        ArrayList<SessionMessage> next = new ArrayList<>(messages);
        next.add(message);
        return new ConversationSession(
                sessionId,
                createdAt,
                message.createdAt(),
                title,
                workingDirectory,
                workspaceRoot,
                next,
                planMode,
                todos,
                readFileState,
                sessionMemoryState
        );
    }

    public ConversationSession withPlanMode(boolean enabled) {
        return new ConversationSession(
                sessionId,
                createdAt,
                Instant.now(),
                title,
                workingDirectory,
                workspaceRoot,
                messages,
                enabled,
                todos,
                readFileState,
                sessionMemoryState
        );
    }

    public ConversationSession withTodos(List<TodoItem> nextTodos) {
        return new ConversationSession(
                sessionId,
                createdAt,
                Instant.now(),
                title,
                workingDirectory,
                workspaceRoot,
                messages,
                planMode,
                nextTodos,
                readFileState,
                sessionMemoryState
        );
    }

    public ConversationSession withMessages(List<SessionMessage> nextMessages) {
        return new ConversationSession(
                sessionId,
                createdAt,
                Instant.now(),
                title,
                workingDirectory,
                workspaceRoot,
                nextMessages,
                planMode,
                todos,
                readFileState,
                sessionMemoryState
        );
    }

    public ConversationSession withTitle(String nextTitle) {
        return new ConversationSession(
                sessionId,
                createdAt,
                Instant.now(),
                nextTitle,
                workingDirectory,
                workspaceRoot,
                messages,
                planMode,
                todos,
                readFileState,
                sessionMemoryState
        );
    }

    public ConversationSession withReadFileState(Map<String, FileReadState> nextReadFileState) {
        return new ConversationSession(
                sessionId,
                createdAt,
                Instant.now(),
                title,
                workingDirectory,
                workspaceRoot,
                messages,
                planMode,
                todos,
                nextReadFileState,
                sessionMemoryState
        );
    }

    public ConversationSession withSessionMemoryState(SessionMemoryState nextSessionMemoryState) {
        return new ConversationSession(
                sessionId,
                createdAt,
                Instant.now(),
                title,
                workingDirectory,
                workspaceRoot,
                messages,
                planMode,
                todos,
                readFileState,
                nextSessionMemoryState
        );
    }

    private static String normalizePath(String path) {
        return path == null || path.isBlank() ? null : path;
    }

    private static String normalizeText(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static Map<String, FileReadState> normalizeReadFileState(Map<String, FileReadState> fileState) {
        if (fileState == null || fileState.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, FileReadState> normalized = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, FileReadState> entry : fileState.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            normalized.put(java.nio.file.Path.of(entry.getKey()).normalize().toString(), entry.getValue());
        }
        return Map.copyOf(normalized);
    }
}
