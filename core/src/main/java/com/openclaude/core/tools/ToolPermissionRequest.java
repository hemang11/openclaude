package com.openclaude.core.tools;

public record ToolPermissionRequest(
        String requestId,
        String toolUseId,
        String toolName,
        String inputJson,
        String command,
        String reason,
        String interactionType,
        String interactionJson
) {
    public ToolPermissionRequest {
        requestId = requestId == null ? "" : requestId;
        toolUseId = toolUseId == null ? "" : toolUseId;
        toolName = toolName == null || toolName.isBlank() ? "tool" : toolName;
        inputJson = inputJson == null ? "{}" : inputJson;
        command = command == null ? "" : command;
        reason = reason == null ? "" : reason;
        interactionType = interactionType == null ? "" : interactionType;
        interactionJson = interactionJson == null ? "" : interactionJson;
    }

    public ToolPermissionRequest(
            String requestId,
            String toolUseId,
            String toolName,
            String inputJson,
            String command,
            String reason
    ) {
        this(requestId, toolUseId, toolName, inputJson, command, reason, "", "");
    }
}
