package io.openclaude.config;

import io.openclaude.provider.spi.ProviderConnection;
import io.openclaude.provider.spi.ProviderId;
import java.util.List;

public record ProviderConnectionState(
        ProviderId activeProvider,
        List<ProviderConnection> connections) {
    public static ProviderConnectionState empty() {
        return new ProviderConnectionState(null, List.of());
    }
}

