package com.openclaude.provider.bedrock;

import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderPlugin;
import java.util.List;
import java.util.Set;

public final class BedrockProviderPlugin implements ProviderPlugin {
    @Override
    public ProviderId id() {
        return ProviderId.BEDROCK;
    }

    @Override
    public String displayName() {
        return id().displayName();
    }

    @Override
    public Set<AuthMethod> supportedAuthMethods() {
        return Set.of(AuthMethod.AWS_CREDENTIALS);
    }

    @Override
    public List<ModelDescriptor> supportedModels() {
        return List.of(
                new ModelDescriptor("bedrock/claude-opus-4.1", "Bedrock Claude Opus 4.1", id()),
                new ModelDescriptor("bedrock/claude-sonnet-4", "Bedrock Claude Sonnet 4", id()),
                new ModelDescriptor("bedrock/claude-haiku-3.5", "Bedrock Claude Haiku 3.5", id())
        );
    }
}
