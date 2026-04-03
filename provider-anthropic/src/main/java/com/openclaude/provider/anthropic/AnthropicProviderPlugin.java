package com.openclaude.provider.anthropic;

import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.PromptEvent;
import com.openclaude.provider.spi.PromptRequest;
import com.openclaude.provider.spi.PromptResult;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderPlugin;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class AnthropicProviderPlugin implements ProviderPlugin {
    private final AnthropicApiClient apiClient;

    public AnthropicProviderPlugin() {
        this(new AnthropicApiClient());
    }

    AnthropicProviderPlugin(AnthropicApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public ProviderId id() {
        return ProviderId.ANTHROPIC;
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
                new ModelDescriptor("anthropic/opus", "Claude Opus 4.1", id(), 200_000),
                new ModelDescriptor("anthropic/sonnet", "Claude Sonnet 4", id(), 200_000),
                new ModelDescriptor("anthropic/haiku", "Claude Haiku 3.5", id(), 200_000)
        );
    }

    @Override
    public boolean supportsPromptExecution() {
        return true;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public boolean supportsTools() {
        return true;
    }

    @Override
    public PromptResult executePrompt(PromptRequest request) {
        if (!supports(request.context().authMethod())) {
            throw new UnsupportedOperationException(
                    "Anthropic prompt execution does not support auth method: "
                            + request.context().authMethod().cliValue()
            );
        }
        return apiClient.execute(request);
    }

    @Override
    public PromptResult executePromptStream(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
        if (!supports(request.context().authMethod())) {
            throw new UnsupportedOperationException(
                    "Anthropic prompt execution does not support auth method: "
                            + request.context().authMethod().cliValue()
            );
        }
        return apiClient.executeStreaming(request, eventConsumer);
    }
}
