package com.openclaude.core.provider;

import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderPlugin;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

public final class ProviderRegistry {
    private final List<ProviderPlugin> providers;

    public ProviderRegistry() {
        this(ServiceLoader.load(ProviderPlugin.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList());
    }

    public ProviderRegistry(List<ProviderPlugin> providers) {
        this.providers = providers.stream()
                .sorted(Comparator.comparing(ProviderPlugin::displayName))
                .toList();
    }

    public List<ProviderPlugin> list() {
        return providers;
    }

    public List<ProviderPlugin> listExecutable() {
        return providers.stream()
                .filter(ProviderPlugin::supportsPromptExecution)
                .toList();
    }

    public Optional<ProviderPlugin> find(ProviderId providerId) {
        return providers.stream()
                .filter(provider -> provider.id() == providerId)
                .findFirst();
    }

    public Optional<ProviderPlugin> findExecutable(ProviderId providerId) {
        return find(providerId)
                .filter(ProviderPlugin::supportsPromptExecution);
    }

    public boolean isExecutable(ProviderId providerId) {
        return findExecutable(providerId).isPresent();
    }
}
