package com.openclaude.core.tools;

public record ToolExecutionUpdate(
        String toolUseId,
        String toolName,
        String phase,
        String text,
        String inputJson,
        String permissionRequestId,
        String command,
        String interactionType,
        String interactionJson,
        boolean error
) {
    public ToolExecutionUpdate {
        toolUseId = toolUseId == null ? "" : toolUseId;
        toolName = toolName == null || toolName.isBlank() ? "tool" : toolName;
        phase = phase == null || phase.isBlank() ? "status" : phase;
        text = text == null ? "" : text;
        inputJson = inputJson == null ? "{}" : inputJson;
        permissionRequestId = permissionRequestId == null ? "" : permissionRequestId;
        command = command == null ? "" : command;
        interactionType = interactionType == null ? "" : interactionType;
        interactionJson = interactionJson == null ? "" : interactionJson;
    }
}
