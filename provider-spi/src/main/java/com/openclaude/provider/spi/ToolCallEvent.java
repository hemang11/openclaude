package com.openclaude.provider.spi;

public record ToolCallEvent(
        String toolId,
        String toolName,
        String phase,
        String text,
        String command
) implements PromptEvent {
}
