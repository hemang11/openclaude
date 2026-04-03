package com.openclaude.provider.kimi;

import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderPlugin;
import java.util.List;
import java.util.Set;

public final class KimiProviderPlugin implements ProviderPlugin {
    @Override
    public ProviderId id() {
        return ProviderId.KIMI;
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
                new ModelDescriptor("kimi/kimi-k2.5", "Kimi K2.5", id()),
                new ModelDescriptor("kimi/kimi-k2", "Kimi K2", id()),
                new ModelDescriptor("kimi/kimi-k2-thinking", "Kimi K2 Thinking", id())
        );
    }
}
