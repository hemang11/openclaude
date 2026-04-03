package com.openclaude.core.tools;

import com.openclaude.core.session.ConversationSession;

public record ToolExecutionRequest(
        String toolUseId,
        String toolName,
        String inputJson,
        ConversationSession session,
        ToolModelInvoker modelInvoker
) {
    public ToolExecutionRequest {
        toolUseId = toolUseId == null ? "" : toolUseId;
        toolName = toolName == null ? "" : toolName;
        inputJson = inputJson == null ? "{}" : inputJson;
    }

    public ToolExecutionRequest(String toolUseId, String toolName, String inputJson) {
        this(toolUseId, toolName, inputJson, null, null);
    }

    public ToolExecutionRequest(String toolUseId, String toolName, String inputJson, ConversationSession session) {
        this(toolUseId, toolName, inputJson, session, null);
    }
}
