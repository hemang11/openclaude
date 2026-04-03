package io.openclaude.provider.spi;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderConnection(
        ProviderId providerId,
        AuthMethod authMethod,
        ConnectionStatus status,
        String accountLabel,
        String organizationLabel,
        String defaultModelId,
        Instant lastValidatedAt) {
}

