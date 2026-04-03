package io.openclaude.provider.spi;

import java.util.List;
import java.util.Optional;

public interface ProviderRegistry {
    List<ProviderDescriptor> providers();

    default Optional<ProviderDescriptor> provider(ProviderId providerId) {
        return providers().stream()
                .filter(descriptor -> descriptor.id() == providerId)
                .findFirst();
    }
}

