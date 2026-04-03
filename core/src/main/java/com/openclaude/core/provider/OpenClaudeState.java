package com.openclaude.core.provider;

import com.openclaude.core.config.OpenClaudeSettings;
import com.openclaude.provider.spi.ProviderId;
import java.util.EnumMap;
import java.util.Map;

public record OpenClaudeState(
        ProviderId activeProvider,
        String activeModelId,
        String activeSessionId,
        Map<ProviderId, ProviderConnectionState> connections,
        OpenClaudeSettings settings
) {
    public OpenClaudeState {
        connections = connections == null || connections.isEmpty()
                ? new EnumMap<>(ProviderId.class)
                : new EnumMap<>(connections);
        settings = settings == null ? OpenClaudeSettings.defaults() : settings;
    }

    public static OpenClaudeState empty() {
        return new OpenClaudeState(null, null, null, new EnumMap<>(ProviderId.class), OpenClaudeSettings.defaults());
    }

    public ProviderConnectionState get(ProviderId providerId) {
        return connections.get(providerId);
    }

    public OpenClaudeState withConnection(ProviderConnectionState connectionState) {
        EnumMap<ProviderId, ProviderConnectionState> next = new EnumMap<>(connections);
        next.put(connectionState.providerId(), connectionState);
        ProviderId nextActiveProvider = activeProvider == null ? connectionState.providerId() : activeProvider;
        return new OpenClaudeState(nextActiveProvider, activeModelId, activeSessionId, next, settings);
    }

    public OpenClaudeState withoutConnection(ProviderId providerId) {
        EnumMap<ProviderId, ProviderConnectionState> next = new EnumMap<>(connections);
        next.remove(providerId);

        ProviderId nextActiveProvider = activeProvider;
        String nextActiveModelId = activeModelId;
        if (activeProvider == providerId) {
            nextActiveProvider = next.keySet().stream().sorted().findFirst().orElse(null);
            nextActiveModelId = null;
        }

        return new OpenClaudeState(nextActiveProvider, nextActiveModelId, activeSessionId, next, settings);
    }

    public OpenClaudeState withActiveProvider(ProviderId providerId) {
        if (!connections.containsKey(providerId)) {
            throw new IllegalArgumentException("Provider is not connected: " + providerId.cliValue());
        }
        return new OpenClaudeState(providerId, null, activeSessionId, connections, settings);
    }

    public OpenClaudeState withActiveModel(String modelId) {
        return new OpenClaudeState(activeProvider, modelId, activeSessionId, connections, settings);
    }

    public OpenClaudeState withActiveSession(String sessionId) {
        return new OpenClaudeState(activeProvider, activeModelId, sessionId, connections, settings);
    }

    public OpenClaudeState withSettings(OpenClaudeSettings nextSettings) {
        return new OpenClaudeState(activeProvider, activeModelId, activeSessionId, connections, nextSettings);
    }
}
