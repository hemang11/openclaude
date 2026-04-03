package com.openclaude.core.config;

import com.openclaude.provider.spi.ProviderId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

public final class ApiKeyStore {
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private final Path authDirectory;

    public ApiKeyStore() {
        this(OpenClaudePaths.configHome().resolve("auth"));
    }

    public ApiKeyStore(Path authDirectory) {
        this.authDirectory = authDirectory;
    }

    public String toCredentialReference(ProviderId providerId, String apiKeyOrEnv) {
        String normalized = requireNonBlank(apiKeyOrEnv);
        if (normalized.startsWith("env:")) {
            return normalized;
        }
        if (isEnvironmentVariableName(normalized)) {
            return "env:" + normalized;
        }
        return "file:" + writeApiKey(providerId, normalized).toAbsolutePath();
    }

    public String describeCredentialReference(String credentialReference) {
        if (credentialReference == null || credentialReference.isBlank()) {
            return "API key";
        }
        if (credentialReference.startsWith("env:")) {
            return "API key env var " + credentialReference.substring("env:".length());
        }
        if (credentialReference.startsWith("file:")) {
            return "stored API key";
        }
        return "API key";
    }

    public static boolean isEnvironmentVariableName(String value) {
        return value != null && value.matches("[A-Z_][A-Z0-9_]*");
    }

    public void deleteCredentialReference(String credentialReference) {
        if (credentialReference == null || credentialReference.isBlank() || !credentialReference.startsWith("file:")) {
            return;
        }

        Path file = Path.of(credentialReference.substring("file:".length())).toAbsolutePath().normalize();
        Path allowedRoot = authDirectory.toAbsolutePath().normalize();
        if (!file.startsWith(allowedRoot)) {
            return;
        }

        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to remove stored API key: " + file, exception);
        }
    }

    private Path writeApiKey(ProviderId providerId, String apiKey) {
        Path file = authDirectory
                .resolve(providerId.cliValue())
                .resolve("default-api-key.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, apiKey);
            applyOwnerOnlyPermissions(file);
            return file;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist API key for " + providerId.cliValue(), exception);
        }
    }

    private static void applyOwnerOnlyPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Best effort only. Some filesystems do not expose POSIX attributes.
        }
    }

    private static String requireNonBlank(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("API key or environment variable name is required.");
        }
        return value.trim();
    }
}
