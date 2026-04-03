package com.openclaude.core.tools;

import com.openclaude.provider.spi.ProviderToolDefinition;
import java.util.List;
import java.util.Objects;

public record ToolModelRequest(
        String prompt,
        String systemPrompt,
        String preferredModelId,
        List<ProviderToolDefinition> tools,
        String requiredToolName,
        boolean stream
) {
    public ToolModelRequest {
        prompt = prompt == null ? "" : prompt;
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        preferredModelId = preferredModelId == null || preferredModelId.isBlank() ? null : preferredModelId;
        tools = tools == null ? List.of() : List.copyOf(tools);
        requiredToolName = requiredToolName == null || requiredToolName.isBlank() ? null : requiredToolName;
    }

    public ToolModelRequest(String prompt) {
        this(prompt, "", null, List.of(), null, false);
    }
}
