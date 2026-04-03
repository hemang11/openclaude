package io.openclaude.provider.spi;

import java.util.Set;

public interface ProviderAuthService {
    ProviderId providerId();

    Set<AuthMethod> supportedAuthMethods();
}

