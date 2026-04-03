package com.openclaude.provider.spi;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public interface ProviderPlugin {
    ProviderId id();

    String displayName();

    Set<AuthMethod> supportedAuthMethods();

    List<ModelDescriptor> supportedModels();

    default boolean supports(AuthMethod authMethod) {
        return supportedAuthMethods().contains(authMethod);
    }

    default boolean supportsPromptExecution() {
        return false;
    }

    default boolean supportsStreaming() {
        return false;
    }

    default boolean supportsTools() {
        return false;
    }

    default PromptResult executePrompt(PromptRequest request) {
        throw new UnsupportedOperationException("Prompt execution is not implemented for provider " + id().cliValue());
    }

    default PromptResult executePromptStream(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
        PromptResult result = executePrompt(new PromptRequest(
                request.context(),
                request.messages(),
                request.tools(),
                false,
                request.requiredToolName(),
                request.effortLevel()
        ));
        if (eventConsumer != null && !result.text().isBlank()) {
            eventConsumer.accept(new TextDeltaEvent(result.text()));
        }
        return result;
    }
}
