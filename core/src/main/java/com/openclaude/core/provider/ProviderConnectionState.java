package com.openclaude.core.provider;

import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ProviderId;
import java.time.Instant;

public record ProviderConnectionState(
        ProviderId providerId,
        AuthMethod authMethod,
        String credentialReference,
        Instant connectedAt,
        ProviderRuntimeDiagnostics diagnostics
) {
    public ProviderConnectionState {
        diagnostics = diagnostics == null ? ProviderRuntimeDiagnostics.empty() : diagnostics;
    }

    public ProviderConnectionState(
            ProviderId providerId,
            AuthMethod authMethod,
            String credentialReference,
            Instant connectedAt
    ) {
        this(providerId, authMethod, credentialReference, connectedAt, ProviderRuntimeDiagnostics.empty());
    }
}
