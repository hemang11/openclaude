package com.openclaude.provider.spi;

public record ToolUseContentBlock(
        String toolUseId,
        String toolName,
        String inputJson
) implements PromptContentBlock {
    public ToolUseContentBlock {
        toolUseId = toolUseId == null ? "" : toolUseId;
        toolName = toolName == null ? "" : toolName;
        inputJson = inputJson == null ? "{}" : inputJson;
    }
}
