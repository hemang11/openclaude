package com.openclaude.core.tools;

public record ToolExecutionResult(
        String toolUseId,
        String toolName,
        String text,
        boolean error,
        String displayText,
        ToolSessionEffect sessionEffect
) {
    public ToolExecutionResult {
        toolUseId = toolUseId == null ? "" : toolUseId;
        toolName = toolName == null ? "" : toolName;
        text = text == null ? "" : text;
        displayText = displayText == null || displayText.isBlank() ? null : displayText;
        sessionEffect = sessionEffect == null ? ToolSessionEffect.none() : sessionEffect;
    }

    public ToolExecutionResult(
            String toolUseId,
            String toolName,
            String text,
            boolean error,
            ToolSessionEffect sessionEffect
    ) {
        this(toolUseId, toolName, text, error, null, sessionEffect);
    }

    public ToolExecutionResult(
            String toolUseId,
            String toolName,
            String text,
            boolean error,
            String displayText
    ) {
        this(toolUseId, toolName, text, error, displayText, ToolSessionEffect.none());
    }

    public ToolExecutionResult(String toolUseId, String toolName, String text, boolean error) {
        this(toolUseId, toolName, text, error, null, ToolSessionEffect.none());
    }
}
