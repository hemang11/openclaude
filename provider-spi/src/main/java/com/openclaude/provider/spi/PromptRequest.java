package com.openclaude.provider.spi;

import java.util.List;
import java.util.Objects;

public record PromptRequest(
        PromptExecutionContext context,
        List<PromptMessage> messages,
        List<ProviderToolDefinition> tools,
        boolean stream,
        String requiredToolName,
        String effortLevel
) {
    public PromptRequest {
        context = Objects.requireNonNull(context, "context");
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        requiredToolName = requiredToolName == null || requiredToolName.isBlank() ? null : requiredToolName;
        effortLevel = effortLevel == null || effortLevel.isBlank() ? null : effortLevel;
    }

    public PromptRequest(
            PromptExecutionContext context,
            List<PromptMessage> messages,
            List<ProviderToolDefinition> tools,
            boolean stream
    ) {
        this(context, messages, tools, stream, null, null);
    }

    public PromptRequest(
            PromptExecutionContext context,
            List<PromptMessage> messages,
            boolean stream
    ) {
        this(context, messages, List.of(), stream, null, null);
    }

    public PromptRequest(
            PromptExecutionContext context,
            List<PromptMessage> messages,
            List<ProviderToolDefinition> tools,
            boolean stream,
            String requiredToolName
    ) {
        this(context, messages, tools, stream, requiredToolName, null);
    }
}
