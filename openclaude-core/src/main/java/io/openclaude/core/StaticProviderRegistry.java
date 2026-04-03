package io.openclaude.core;

import io.openclaude.provider.spi.AuthMethod;
import io.openclaude.provider.spi.ProviderDescriptor;
import io.openclaude.provider.spi.ProviderId;
import io.openclaude.provider.spi.ProviderRegistry;
import java.util.List;
import java.util.Set;

public final class StaticProviderRegistry implements ProviderRegistry {
    private static final List<ProviderDescriptor> PROVIDERS = List.of(
            new ProviderDescriptor(
                    ProviderId.ANTHROPIC,
                    "Anthropic",
                    "Claude direct provider with API key and browser auth support.",
                    Set.of(AuthMethod.API_KEY, AuthMethod.BROWSER_OAUTH)),
            new ProviderDescriptor(
                    ProviderId.OPENAI,
                    "OpenAI",
                    "OpenAI provider with API key and browser auth support.",
                    Set.of(AuthMethod.API_KEY, AuthMethod.BROWSER_OAUTH)),
            new ProviderDescriptor(
                    ProviderId.GEMINI,
                    "Gemini",
                    "Gemini provider with API key support in v0.",
                    Set.of(AuthMethod.API_KEY)),
            new ProviderDescriptor(
                    ProviderId.MISTRAL,
                    "Mistral",
                    "Mistral provider with API key support in v0.",
                    Set.of(AuthMethod.API_KEY)),
            new ProviderDescriptor(
                    ProviderId.KIMI,
                    "Kimi",
                    "Kimi provider with API key support in v0.",
                    Set.of(AuthMethod.API_KEY)),
            new ProviderDescriptor(
                    ProviderId.BEDROCK,
                    "Bedrock",
                    "Anthropic-via-Bedrock provider for v0 using AWS credentials.",
                    Set.of(AuthMethod.AWS_CREDENTIALS)));

    @Override
    public List<ProviderDescriptor> providers() {
        return PROVIDERS;
    }
}

