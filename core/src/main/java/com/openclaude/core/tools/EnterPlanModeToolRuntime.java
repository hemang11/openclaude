package com.openclaude.core.tools;

import com.openclaude.provider.spi.ProviderToolDefinition;
import java.util.function.Consumer;

public final class EnterPlanModeToolRuntime extends AbstractSingleToolRuntime {
    private static final ProviderToolDefinition DEFINITION = new ProviderToolDefinition(
            "EnterPlanMode",
            "Requests permission to enter plan mode for complex tasks requiring exploration and design.",
            """
            {
              "type": "object",
              "properties": {},
              "additionalProperties": false
            }
            """
    );
    private static final String RESULT_TEXT = """
            Entered plan mode. You should now focus on exploring the codebase and designing an implementation approach.
            
            In plan mode, you should:
            1. Thoroughly explore the codebase to understand existing patterns
            2. Identify similar features and architectural approaches
            3. Consider multiple approaches and their trade-offs
            4. Use AskUserQuestion if you need to clarify the approach
            5. Design a concrete implementation strategy
            6. When ready, use ExitPlanMode to present your plan for approval
            
            Remember: DO NOT write or edit any files yet. This is a read-only exploration and planning phase.
            """.strip();

    public EnterPlanModeToolRuntime() {
        super(DEFINITION);
    }

    @Override
    protected ToolExecutionResult executeSingle(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer
    ) {
        ToolPermissionDecision decision = requestPermission(
                request,
                permissionGateway,
                updateConsumer,
                "",
                "Enter plan mode?",
                "enter_plan_mode",
                "{\"mode\":\"plan\"}"
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
                new ToolSessionEffect(true, null, null)
        );
    }
}
