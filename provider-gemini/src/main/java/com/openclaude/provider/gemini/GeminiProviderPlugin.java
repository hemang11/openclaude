package com.openclaude.provider.gemini;

import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderPlugin;
import java.util.List;
import java.util.Set;

public final class GeminiProviderPlugin implements ProviderPlugin {
    @Override
    public ProviderId id() {
        return ProviderId.GEMINI;
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
                new ModelDescriptor("gemini/gemini-2.5-pro", "Gemini 2.5 Pro", id()),
                new ModelDescriptor("gemini/gemini-2.5-flash", "Gemini 2.5 Flash", id()),
                new ModelDescriptor("gemini/gemini-2.0-flash", "Gemini 2.0 Flash", id())
        );
    }
}
