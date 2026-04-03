package com.openclaude.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class OpenAiOAuthStore {
    public static final String CREDENTIAL_REFERENCE_PREFIX = "openai-oauth:";
    public static final String DEFAULT_CREDENTIAL_REFERENCE = CREDENTIAL_REFERENCE_PREFIX + "default";

    private final Path authDirectory;
    private final ObjectMapper objectMapper;

    public OpenAiOAuthStore() {
        this(defaultAuthDirectory());
    }

    public OpenAiOAuthStore(Path authDirectory) {
        this.authDirectory = authDirectory;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Optional<OpenAiOAuthSession> load(String credentialReference) {
        Path file = credentialFile(credentialReference);
        if (!Files.exists(file)) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(file.toFile(), OpenAiOAuthSession.class));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read OpenAI OAuth session: " + file, exception);
        }
    }

    public OpenAiOAuthSession save(String credentialReference, OpenAiOAuthSession session) {
        Path file = credentialFile(credentialReference);
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), session);
            return session;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist OpenAI OAuth session: " + file, exception);
        }
    }

    public void delete(String credentialReference) {
        Path file = credentialFile(credentialReference);
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to remove OpenAI OAuth session: " + file, exception);
        }
    }

    public Path credentialFile(String credentialReference) {
        return authDirectory.resolve(profileName(credentialReference) + ".json");
    }

    private static String profileName(String credentialReference) {
        String reference = credentialReference == null || credentialReference.isBlank()
                ? DEFAULT_CREDENTIAL_REFERENCE
                : credentialReference;
        if (!reference.startsWith(CREDENTIAL_REFERENCE_PREFIX)) {
            throw new IllegalStateException("Unsupported OpenAI OAuth credential reference: " + reference);
        }

        String profile = reference.substring(CREDENTIAL_REFERENCE_PREFIX.length());
        return profile.isBlank() ? "default" : profile;
    }

    private static Path defaultAuthDirectory() {
        String override = System.getenv("OPENCLAUDE_HOME");
        Path configHome = override == null || override.isBlank()
                ? Path.of(System.getProperty("user.home"), ".openclaude")
                : Path.of(override);
        return configHome.resolve("auth").resolve("openai");
    }
}
