package com.openclaude.core.tools;

import com.openclaude.provider.spi.ProviderToolDefinition;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

abstract class AbstractSingleToolRuntime implements ToolRuntime {
    private final ProviderToolDefinition definition;

    protected AbstractSingleToolRuntime(ProviderToolDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
    }

    @Override
    public final List<ProviderToolDefinition> toolDefinitions() {
        return List.of(definition);
    }

    @Override
    public final boolean isConcurrencySafe(String toolName, String inputJson) {
        if (!definition.name().equals(toolName)) {
            return false;
        }
        return isConcurrencySafeSingle(inputJson);
    }

    @Override
    public final ToolExecutionResult execute(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer
    ) {
        if (!definition.name().equals(request.toolName())) {
            emit(updateConsumer, request, "failed", "Unsupported tool: " + request.toolName(), "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Unsupported tool: " + request.toolName(), true);
        }

        return executeSingle(request, permissionGateway, updateConsumer);
    }

    protected abstract ToolExecutionResult executeSingle(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer
    );

    protected boolean isConcurrencySafeSingle(String inputJson) {
        return false;
    }

    protected final ToolPermissionDecision requestPermission(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer,
            String command,
            String reason
    ) {
        return requestPermission(request, permissionGateway, updateConsumer, command, reason, "", "");
    }

    protected final ToolPermissionDecision requestPermission(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer,
            String command,
            String reason,
            String interactionType,
            String interactionJson
    ) {
        String permissionRequestId = UUID.randomUUID().toString();
        ToolPermissionRequest permissionRequest = new ToolPermissionRequest(
                permissionRequestId,
                request.toolUseId(),
                request.toolName(),
                request.inputJson(),
                command,
                reason,
                interactionType,
                interactionJson
        );
        ToolPermissionDecision persistedDecision = permissionGateway.lookupPersistedDecision(permissionRequest);
        if (persistedDecision != null && !persistedDecision.asks()) {
            return normalizeDecision(persistedDecision);
        }
        emit(
                updateConsumer,
                request,
                "permission_requested",
                reason,
                command,
                false,
                permissionRequestId,
                interactionType,
                interactionJson
        );
        return normalizeDecision(permissionGateway.requestPermission(permissionRequest));
    }

    private static ToolPermissionDecision normalizeDecision(ToolPermissionDecision decision) {
        if (decision == null) {
            return null;
        }
        if (decision.interrupt()) {
            throw new ToolExecutionCancelledException(decision.reason());
        }
        return decision;
    }

    protected final void emit(
            Consumer<ToolExecutionUpdate> updateConsumer,
            ToolExecutionRequest request,
            String phase,
            String text,
            String command,
            boolean error
    ) {
        emit(updateConsumer, request, phase, text, command, error, "", "", "");
    }

    protected final void emit(
            Consumer<ToolExecutionUpdate> updateConsumer,
            ToolExecutionRequest request,
            String phase,
            String text,
            String command,
            boolean error,
            String permissionRequestId
    ) {
        emit(updateConsumer, request, phase, text, command, error, permissionRequestId, "", "");
    }

    protected final void emit(
            Consumer<ToolExecutionUpdate> updateConsumer,
            ToolExecutionRequest request,
            String phase,
            String text,
            String command,
            boolean error,
            String permissionRequestId,
            String interactionType,
            String interactionJson
    ) {
        if (updateConsumer == null) {
            return;
        }
        updateConsumer.accept(new ToolExecutionUpdate(
                request.toolUseId(),
                request.toolName(),
                phase,
                text,
                request.inputJson(),
                permissionRequestId,
                command == null ? "" : command,
                interactionType,
                interactionJson,
                error
        ));
    }
}
