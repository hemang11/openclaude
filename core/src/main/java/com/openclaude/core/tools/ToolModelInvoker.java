package com.openclaude.core.tools;

import com.openclaude.provider.spi.PromptContentBlock;
import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ProviderId;
import java.util.List;
import java.util.function.Consumer;

@FunctionalInterface
public interface ToolModelInvoker {
    String invoke(String prompt);

    default ToolModelResponse invoke(ToolModelRequest request) {
        String text = invoke(request == null ? "" : request.prompt());
        return new ToolModelResponse(
                text == null ? "" : text,
                text == null || text.isBlank() ? List.of() : List.of(new com.openclaude.provider.spi.TextContentBlock(text))
        );
    }

    default ToolModelResponse invoke(ToolModelRequest request, Consumer<ToolModelProgress> progressConsumer) {
        return invoke(request);
    }

    default ProviderId providerId() {
        return null;
    }

    default String currentModelId() {
        return null;
    }

    default AuthMethod authMethod() {
        return null;
    }
}
