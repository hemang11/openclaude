package com.openclaude.provider.spi;

public record ToolUseDiscoveredEvent(
        String toolUseId,
        String toolName,
        String inputJson
) implements PromptEvent {
    public ToolUseDiscoveredEvent {
        toolUseId = toolUseId == null ? "" : toolUseId;
        toolName = toolName == null ? "" : toolName;
        inputJson = inputJson == null ? "{}" : inputJson;
    }

    public ToolUseContentBlock toContentBlock() {
        return new ToolUseContentBlock(toolUseId, toolName, inputJson);
    }
}
