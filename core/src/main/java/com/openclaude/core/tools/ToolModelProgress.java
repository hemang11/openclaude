package com.openclaude.core.tools;

public record ToolModelProgress(
        String toolUseId,
        String type,
        String text,
        int resultCount
) {
    public ToolModelProgress {
        toolUseId = toolUseId == null ? "" : toolUseId;
        type = type == null ? "" : type;
        text = text == null ? "" : text;
    }
}
