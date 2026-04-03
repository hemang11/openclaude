package com.openclaude.core.tools;

import com.openclaude.provider.spi.ProviderToolDefinition;
import java.util.function.Consumer;

public final class ExitPlanModeToolRuntime extends AbstractSingleToolRuntime {
    private static final ProviderToolDefinition DEFINITION = new ProviderToolDefinition(
            "ExitPlanMode",
            "Prompts the user to exit plan mode and start coding.",
            """
            {
              "type": "object",
              "properties": {
                "allowedPrompts": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "tool": {"type": "string", "enum": ["Bash"]},
                      "prompt": {"type": "string"}
                    },
                    "required": ["tool", "prompt"],
                    "additionalProperties": false
                  }
                }
              },
              "additionalProperties": true
            }
            """
    );
    private static final String RESULT_TEXT =
            "Plan mode exited. The user should now review and approve your plan before implementation continues.";

    public ExitPlanModeToolRuntime() {
        super(DEFINITION);
    }

    @Override
    protected ToolExecutionResult executeSingle(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer
    ) {
        if (request.session() == null || !request.session().planMode()) {
            emit(updateConsumer, request, "failed", "You are not in plan mode.", "", true);
            return new ToolExecutionResult(
                    request.toolUseId(),
                    request.toolName(),
                    "You are not in plan mode. This tool is only for exiting plan mode after writing a plan.",
                    true
            );
        }

        ToolPermissionDecision decision = requestPermission(
                request,
                permissionGateway,
                updateConsumer,
                "",
                "Exit plan mode?",
                "exit_plan_mode",
                request.inputJson()
        );
        if (!decision.allowed()) {
            emit(updateConsumer, request, "failed", "Permission denied: " + decision.reason(), "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Permission denied: " + decision.reason(), true);
        }

        emit(updateConsumer, request, "completed", RESULT_TEXT, "", false);
        return new ToolExecutionResult(
                request.toolUseId(),
                request.toolName(),
                RESULT_TEXT,
                false,
                new ToolSessionEffect(false, null, null)
        );
    }
}
