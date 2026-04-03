package io.openclaude.cli;

import io.openclaude.config.ProviderConnectionStore;
import io.openclaude.provider.spi.ProviderRegistry;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(
        name = "provider",
        mixinStandardHelpOptions = true,
        description = "List or manage provider connections.")
public final class ProviderCommand implements Callable<Integer> {
    private final ProviderRegistry providerRegistry;
    private final ProviderConnectionStore connectionStore;

    public ProviderCommand(ProviderRegistry providerRegistry, ProviderConnectionStore connectionStore) {
        this.providerRegistry = providerRegistry;
        this.connectionStore = connectionStore;
    }

    @Override
    public Integer call() {
        var state = connectionStore.load();
        System.out.println("Configured providers:");
        providerRegistry.providers().forEach(provider -> {
            String activeMarker = provider.id() == state.activeProvider() ? " [active]" : "";
            System.out.printf(
                    "- %s (%s)%s%n  %s%n",
                    provider.displayName(),
                    provider.id().name().toLowerCase(),
                    activeMarker,
                    provider.description());
            System.out.printf("  auth: %s%n", provider.supportedAuthMethods());
        });
        return 0;
    }
}

