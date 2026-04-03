package io.openclaude.provider.spi;

import java.util.Set;

public record ProviderDescriptor(
        ProviderId id,
        String displayName,
        String description,
        Set<AuthMethod> supportedAuthMethods) {
}

