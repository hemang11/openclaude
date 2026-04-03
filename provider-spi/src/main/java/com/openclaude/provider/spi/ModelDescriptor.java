package com.openclaude.provider.spi;

public record ModelDescriptor(
        String id,
        String displayName,
        ProviderId providerId,
        Integer contextWindowTokens
) {
    public ModelDescriptor {
        contextWindowTokens = contextWindowTokens == null || contextWindowTokens <= 0
                ? null
                : contextWindowTokens;
    }

    public ModelDescriptor(String id, String displayName, ProviderId providerId) {
        this(id, displayName, providerId, null);
    }
}
