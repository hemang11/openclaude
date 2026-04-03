package com.openclaude.provider.spi;

public record PromptExecutionContext(
        ProviderId providerId,
        AuthMethod authMethod,
        String credentialReference,
        String modelId
) {
}

