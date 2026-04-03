package com.openclaude.provider.openai;

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

public final class OpenAiProviderPlugin implements ProviderPlugin {
    private final OpenAiApiClient apiClient;
    private final OpenAiCodexResponsesClient codexResponsesClient;

    public OpenAiProviderPlugin() {
        this(new OpenAiApiClient(), new OpenAiCodexResponsesClient());
    }

    OpenAiProviderPlugin(OpenAiApiClient apiClient, OpenAiCodexResponsesClient codexResponsesClient) {
        this.apiClient = apiClient;
        this.codexResponsesClient = codexResponsesClient;
    }

    @Override
    public ProviderId id() {
        return ProviderId.OPENAI;
    }

    @Override
    public String displayName() {
        return id().displayName();
    }

    @Override
    public Set<AuthMethod> supportedAuthMethods() {
        return Set.of(AuthMethod.API_KEY, AuthMethod.BROWSER_SSO);
    }

    @Override
    public List<ModelDescriptor> supportedModels() {
        return List.of(
                new ModelDescriptor("gpt-5.3-codex", "GPT-5.3 Codex", id(), 400_000),
                new ModelDescriptor("gpt-5.2-codex", "GPT-5.2 Codex", id(), 400_000),
                new ModelDescriptor("gpt-5.2", "GPT-5.2", id(), 400_000),
                new ModelDescriptor("gpt-5.1-codex-max", "GPT-5.1 Codex Max", id(), 400_000),
                new ModelDescriptor("gpt-5.1-codex-mini", "GPT-5.1 Codex Mini", id(), 400_000),
                new ModelDescriptor("gpt-5.1-codex", "GPT-5.1 Codex", id(), 400_000),
                new ModelDescriptor("gpt-5.4", "GPT-5.4", id(), 400_000),
                new ModelDescriptor("gpt-5.4-mini", "GPT-5.4 mini", id(), 400_000),
                new ModelDescriptor("gpt-5-mini", "GPT-5 mini", id(), 400_000)
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
                    "OpenAI prompt execution does not support auth method: " + request.context().authMethod().cliValue()
            );
        }
        if (request.context().authMethod() == AuthMethod.BROWSER_SSO) {
            return codexResponsesClient.execute(request);
        }
        return apiClient.execute(request);
    }

    @Override
    public PromptResult executePromptStream(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
        if (!supports(request.context().authMethod())) {
            throw new UnsupportedOperationException(
                    "OpenAI prompt execution does not support auth method: " + request.context().authMethod().cliValue()
            );
        }
        if (request.context().authMethod() == AuthMethod.BROWSER_SSO) {
            return codexResponsesClient.executeStreaming(request, eventConsumer);
        }
        return apiClient.executeStreaming(request, eventConsumer);
    }
}
