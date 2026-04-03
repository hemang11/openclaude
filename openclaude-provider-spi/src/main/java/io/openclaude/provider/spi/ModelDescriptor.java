package io.openclaude.provider.spi;

public record ModelDescriptor(
        String id,
        ProviderId providerId,
        String family,
        String displayName,
        boolean supportsToolCalling,
        boolean supportsStreaming,
        boolean supportsReasoningEffort,
        Integer contextWindow) {
}

