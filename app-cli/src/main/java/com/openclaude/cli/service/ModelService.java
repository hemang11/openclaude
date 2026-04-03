package com.openclaude.cli.service;

import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.provider.OpenClaudeState;
import com.openclaude.core.provider.ProviderRegistry;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderPlugin;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class ModelService {
    private final ProviderRegistry providerRegistry;
    private final OpenClaudeStateStore stateStore;

    public ModelService(ProviderRegistry providerRegistry, OpenClaudeStateStore stateStore) {
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    public List<ModelView> listConnectedModels() {
        OpenClaudeState state = stateStore.load();
        return state.connections().keySet().stream()
                .sorted(Comparator.comparing(ProviderId::cliValue))
                .flatMap(providerId -> providerRegistry.findExecutable(providerId)
                        .stream()
                        .flatMap(provider -> provider.supportedModels().stream()
                                .map(model -> toModelView(state, provider, model))))
                .toList();
    }

    public List<ModelView> listModelsForActiveProvider() {
        OpenClaudeState state = stateStore.load();
        if (state.activeProvider() == null) {
            return List.of();
        }

        return providerRegistry.findExecutable(state.activeProvider())
                .stream()
                .flatMap(provider -> provider.supportedModels().stream()
                        .map(model -> toModelView(state, provider, model)))
                .toList();
    }

    public CurrentSelection currentSelection() {
        OpenClaudeState state = stateStore.load();
        return new CurrentSelection(state.activeProvider(), state.activeModelId());
    }

    public String selectModel(ProviderId providerId, String modelId) {
        OpenClaudeState state = stateStore.load();
        if (!state.connections().containsKey(providerId)) {
            throw new IllegalStateException("Provider is not connected: " + providerId.cliValue());
        }

        ProviderPlugin providerPlugin = providerRegistry.findExecutable(providerId)
                .orElseGet(() -> {
                    if (providerRegistry.find(providerId).isPresent()) {
                        throw new IllegalStateException(
                                "Prompt execution is not implemented for provider " + providerId.cliValue()
                        );
                    }
                    throw new IllegalStateException("Unsupported provider: " + providerId.cliValue());
                });

        boolean exists = providerPlugin.supportedModels().stream()
                .anyMatch(candidate -> candidate.id().equals(modelId));
        if (!exists) {
            throw new IllegalArgumentException("Unknown model for provider " + providerId.cliValue() + ": " + modelId);
        }

        stateStore.setActiveProvider(providerId);
        stateStore.setActiveModel(modelId);
        return "Active model set to " + modelId;
    }

    private static ModelView toModelView(OpenClaudeState state, ProviderPlugin provider, ModelDescriptor model) {
        boolean providerActive = state.activeProvider() == provider.id();
        boolean active;
        if (!providerActive) {
            active = false;
        } else if (state.activeModelId() == null || state.activeModelId().isBlank()) {
            active = !provider.supportedModels().isEmpty() && provider.supportedModels().get(0).id().equals(model.id());
        } else {
            active = state.activeModelId().equals(model.id());
        }

        return new ModelView(
                model.id(),
                model.displayName(),
                provider.id(),
                provider.displayName(),
                providerActive,
                active
        );
    }

    public record CurrentSelection(
            ProviderId activeProvider,
            String activeModelId
    ) {
    }

    public record ModelView(
            String id,
            String displayName,
            ProviderId providerId,
            String providerDisplayName,
            boolean providerActive,
            boolean active
    ) {
    }
}
