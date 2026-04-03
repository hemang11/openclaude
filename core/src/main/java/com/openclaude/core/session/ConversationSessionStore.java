package com.openclaude.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openclaude.core.config.OpenClaudePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class ConversationSessionStore {
    private final Path sessionsDirectory;
    private final ObjectMapper objectMapper;

    public ConversationSessionStore() {
        this(OpenClaudePaths.sessionsDirectory());
    }

    public ConversationSessionStore(Path sessionsDirectory) {
        this.sessionsDirectory = sessionsDirectory;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public ConversationSession loadOrCreate(String sessionId) {
        Path sessionFile = sessionFile(sessionId);
        if (!Files.exists(sessionFile)) {
            ConversationSession session = ConversationSession.create(sessionId);
            save(session);
            return session;
        }

        try {
            return objectMapper.readValue(sessionFile.toFile(), ConversationSession.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read session file: " + sessionFile, exception);
        }
    }

    public ConversationSession load(String sessionId) {
        Path sessionFile = sessionFile(sessionId);
        if (!Files.exists(sessionFile)) {
            throw new IllegalArgumentException("Unknown session id: " + sessionId);
        }

        try {
            return objectMapper.readValue(sessionFile.toFile(), ConversationSession.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read session file: " + sessionFile, exception);
        }
    }

    public ConversationSession save(ConversationSession session) {
        Path sessionFile = sessionFile(session.sessionId());
        try {
            Files.createDirectories(sessionFile.getParent());
            objectMapper.writeValue(sessionFile.toFile(), session);
            return session;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write session file: " + sessionFile, exception);
        }
    }

    public List<ConversationSession> listSessions() {
        if (!Files.exists(sessionsDirectory)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(sessionsDirectory)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readSessionSafely)
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(ConversationSession::updatedAt).reversed())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list sessions from " + sessionsDirectory, exception);
        }
    }

    public Path sessionFilePath(String sessionId) {
        return sessionFile(sessionId);
    }

    private Path sessionFile(String sessionId) {
        return sessionsDirectory.resolve(sessionId + ".json");
    }

    private ConversationSession readSessionSafely(Path sessionFile) {
        try {
            return objectMapper.readValue(sessionFile.toFile(), ConversationSession.class);
        } catch (IOException exception) {
            return null;
        }
    }
}
