package com.openclaude.core.tools;

import com.openclaude.provider.spi.ProviderToolDefinition;
import java.util.List;
import java.util.function.Consumer;

public interface ToolRuntime {
    enum InterruptBehavior {
        CANCEL,
        BLOCK
    }

    List<ProviderToolDefinition> toolDefinitions();

    default boolean isConcurrencySafe(String toolName, String inputJson) {
        return false;
    }

    default InterruptBehavior interruptBehavior(String toolName, String inputJson) {
        return InterruptBehavior.BLOCK;
    }

    default ToolExecutionResult execute(ToolExecutionRequest request) {
        return execute(request, ToolPermissionGateway.allowAll(), update -> {});
    }

    ToolExecutionResult execute(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer
    );
}
