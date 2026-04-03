package com.openclaude.core.tools;

import com.openclaude.provider.spi.ProviderToolDefinition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public class CompositeToolRuntime implements ToolRuntime {
    private final List<ToolRuntime> runtimes;

    public CompositeToolRuntime(List<ToolRuntime> runtimes) {
        this.runtimes = List.copyOf(Objects.requireNonNull(runtimes, "runtimes"));
    }

    @Override
    public List<ProviderToolDefinition> toolDefinitions() {
        List<ProviderToolDefinition> definitions = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();
        for (ToolRuntime runtime : runtimes) {
            for (ProviderToolDefinition definition : runtime.toolDefinitions()) {
                if (seenNames.add(definition.name())) {
                    definitions.add(definition);
                }
            }
        }
        return definitions;
    }

    @Override
    public boolean isConcurrencySafe(String toolName, String inputJson) {
        for (ToolRuntime runtime : runtimes) {
            boolean supportsTool = runtime.toolDefinitions().stream()
                    .anyMatch(definition -> definition.name().equals(toolName));
            if (supportsTool) {
                return runtime.isConcurrencySafe(toolName, inputJson);
            }
        }
        return false;
    }

    @Override
    public InterruptBehavior interruptBehavior(String toolName, String inputJson) {
        for (ToolRuntime runtime : runtimes) {
            boolean supportsTool = runtime.toolDefinitions().stream()
                    .anyMatch(definition -> definition.name().equals(toolName));
            if (supportsTool) {
                return runtime.interruptBehavior(toolName, inputJson);
            }
        }
        return InterruptBehavior.BLOCK;
    }

    @Override
    public ToolExecutionResult execute(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer
    ) {
        for (ToolRuntime runtime : runtimes) {
            boolean supportsTool = runtime.toolDefinitions().stream()
                    .anyMatch(definition -> definition.name().equals(request.toolName()));
            if (supportsTool) {
                return runtime.execute(request, permissionGateway, updateConsumer);
            }
        }

        if (updateConsumer != null) {
            updateConsumer.accept(new ToolExecutionUpdate(
                    request.toolUseId(),
                    request.toolName(),
                    "failed",
                    "Unsupported tool: " + request.toolName(),
                    request.inputJson(),
                    "",
                    "",
                    "",
                    "",
                    true
            ));
        }
        return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Unsupported tool: " + request.toolName(), true);
    }
}
