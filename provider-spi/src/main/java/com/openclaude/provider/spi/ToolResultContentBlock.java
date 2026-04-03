package com.openclaude.provider.spi;

public record ToolResultContentBlock(
        String toolUseId,
        String toolName,
        String text,
        boolean isError
) implements PromptContentBlock {
    public ToolResultContentBlock {
        toolUseId = toolUseId == null ? "" : toolUseId;
        toolName = toolName == null ? "" : toolName;
        text = text == null ? "" : text;
    }
}
