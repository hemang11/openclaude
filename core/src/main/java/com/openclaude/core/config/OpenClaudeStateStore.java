package com.openclaude.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openclaude.core.provider.OpenClaudeState;
import com.openclaude.core.provider.ProviderConnectionState;
import com.openclaude.core.provider.ProviderRuntimeDiagnostics;
import com.openclaude.provider.spi.ProviderId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.function.UnaryOperator;

public final class OpenClaudeStateStore {
    private final Path stateFile;
    private final ObjectMapper objectMapper;
    private volatile String sessionEffortLevel;

    public OpenClaudeStateStore() {
        this(OpenClaudePaths.stateFile());
    }

    public OpenClaudeStateStore(Path stateFile) {
        this.stateFile = stateFile;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public OpenClaudeState load() {
        if (!Files.exists(stateFile)) {
            return OpenClaudeState.empty();
        }

        try {
            return objectMapper.readValue(stateFile.toFile(), OpenClaudeState.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read state file: " + stateFile, exception);
        }
    }

    public OpenClaudeState save(OpenClaudeState state) {
        try {
            Files.createDirectories(stateFile.getParent());
            objectMapper.writeValue(stateFile.toFile(), state);
            return state;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write state file: " + stateFile, exception);
        }
    }

    public OpenClaudeState saveConnection(ProviderConnectionState connectionState) {
        OpenClaudeState current = load();
        return save(current.withConnection(connectionState));
    }

    public OpenClaudeState updateConnectionDiagnostics(
            ProviderId providerId,
            UnaryOperator<ProviderRuntimeDiagnostics> updater
    ) {
        OpenClaudeState current = load();
        ProviderConnectionState connection = current.get(providerId);
        if (connection == null) {
            return current;
        }
        ProviderRuntimeDiagnostics currentDiagnostics = connection.diagnostics();
        ProviderRuntimeDiagnostics nextDiagnostics = updater == null
                ? currentDiagnostics
                : updater.apply(currentDiagnostics == null ? ProviderRuntimeDiagnostics.empty() : currentDiagnostics);
        if (nextDiagnostics == null) {
            nextDiagnostics = ProviderRuntimeDiagnostics.empty();
        }
        EnumMap<ProviderId, ProviderConnectionState> nextConnections = new EnumMap<>(current.connections());
        nextConnections.put(providerId, new ProviderConnectionState(
                connection.providerId(),
                connection.authMethod(),
                connection.credentialReference(),
                connection.connectedAt(),
                nextDiagnostics
        ));
        return save(new OpenClaudeState(
                current.activeProvider(),
                current.activeModelId(),
                current.activeSessionId(),
                nextConnections,
                current.settings()
        ));
    }

    public OpenClaudeState removeConnection(ProviderId providerId) {
        OpenClaudeState current = load();
        return save(current.withoutConnection(providerId));
    }

    public OpenClaudeState setActiveProvider(ProviderId providerId) {
        OpenClaudeState current = load();
        return save(current.withActiveProvider(providerId));
    }

    public OpenClaudeState setActiveModel(String modelId) {
        OpenClaudeState current = load();
        return save(current.withActiveModel(modelId));
    }

    public OpenClaudeState setActiveSession(String sessionId) {
        OpenClaudeState current = load();
        return save(current.withActiveSession(sessionId));
    }

    public OpenClaudeState setSettings(OpenClaudeSettings settings) {
        OpenClaudeState current = load();
        return save(current.withSettings(settings));
    }

    public String currentEffortLevel(String configuredEffort) {
        return OpenClaudeEffort.currentValue(configuredEffort, sessionEffortLevel);
    }

    public void setSessionEffortLevel(String effortLevel) {
        this.sessionEffortLevel = OpenClaudeEffort.normalizeConfiguredValue(effortLevel);
    }

    public void clearSessionEffortLevel() {
        this.sessionEffortLevel = null;
    }
}
