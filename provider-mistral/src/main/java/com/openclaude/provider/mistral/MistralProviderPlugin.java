package com.openclaude.provider.mistral;

import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderPlugin;
import java.util.List;
import java.util.Set;

public final class MistralProviderPlugin implements ProviderPlugin {
    @Override
    public ProviderId id() {
        return ProviderId.MISTRAL;
    }

    @Override
    public String displayName() {
        return id().displayName();
    }

    @Override
    public Set<AuthMethod> supportedAuthMethods() {
        return Set.of(AuthMethod.API_KEY);
    }

    @Override
    public List<ModelDescriptor> supportedModels() {
        return List.of(
                new ModelDescriptor("mistral/mistral-large-3", "Mistral Large 3", id()),
                new ModelDescriptor("mistral/devstral-2", "Devstral 2", id()),
                new ModelDescriptor("mistral/mistral-small-4", "Mistral Small 4", id())
        );
    }
}
