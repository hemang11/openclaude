package com.openclaude.provider.spi;

public record ToolPermissionEvent(
        String requestId,
        String toolId,
        String toolName,
        String inputJson,
        String command,
        String reason,
        String interactionType,
        String interactionJson
) implements PromptEvent {
}
