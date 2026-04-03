package com.openclaude.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaude.provider.spi.ProviderToolDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class BashToolRuntime implements ToolRuntime {
    private static final int MAX_CAPTURE_CHARS = 24_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ProviderToolDefinition BASH_TOOL = new ProviderToolDefinition(
            "bash",
            "Run a local shell command in the current workspace. Use this for inspecting files, directories, and command output.",
            """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "The exact shell command to run."
                }
              },
              "required": ["command"],
              "additionalProperties": false
            }
            """
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ShellPermissionPolicy permissionPolicy = new ShellPermissionPolicy();

    @Override
    public List<ProviderToolDefinition> toolDefinitions() {
        return List.of(BASH_TOOL);
    }

    @Override
    public boolean isConcurrencySafe(String toolName, String inputJson) {
        if (!"bash".equals(toolName)) {
            return false;
        }
        String command = extractCommand(inputJson);
        return !command.isBlank() && ShellPermissionPolicy.isReadOnlyCommand(command);
    }

    @Override
    public ToolRuntime.InterruptBehavior interruptBehavior(String toolName, String inputJson) {
        return "bash".equals(toolName) ? ToolRuntime.InterruptBehavior.CANCEL : ToolRuntime.InterruptBehavior.BLOCK;
    }

    @Override
    public ToolExecutionResult execute(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer
    ) {
        if (!"bash".equals(request.toolName())) {
            emit(updateConsumer, request, "failed", "Unsupported tool: " + request.toolName(), request.inputJson(), "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Unsupported tool: " + request.toolName(), true);
        }

        String command = extractCommand(request.inputJson());
        if (command.isBlank()) {
            emit(updateConsumer, request, "failed", "Missing bash.command input.", request.inputJson(), "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Missing bash.command input.", true);
        }

        emit(updateConsumer, request, "started", "Queued bash command.", request.inputJson(), command, false);
        ShellPermissionPolicy.PermissionDecision permissionDecision = permissionPolicy.evaluate(command);
        if (permissionDecision.denied()) {
            emit(
                    updateConsumer,
                    request,
                    "failed",
                    "Permission denied: " + permissionDecision.reason(),
                    request.inputJson(),
                    command,
                    true
            );
            return new ToolExecutionResult(
                    request.toolUseId(),
                    request.toolName(),
                    "Permission denied: " + permissionDecision.reason(),
                    true
            );
        }
        ToolPermissionRequest permissionRequest = new ToolPermissionRequest(
                UUID.randomUUID().toString(),
                request.toolUseId(),
                request.toolName(),
                request.inputJson(),
                command,
                "Allow bash command?",
                "bash",
                request.inputJson()
        );
        ToolPermissionDecision persistedDecision = permissionGateway.lookupPersistedDecision(permissionRequest);
        ToolPermissionDecision approvalDecision = persistedDecision != null && !persistedDecision.asks()
                ? persistedDecision
                : null;
        boolean explicitAsk = persistedDecision != null && persistedDecision.asks();
        if (approvalDecision != null && approvalDecision.interrupt()) {
            throw new ToolExecutionCancelledException(approvalDecision.reason());
        }
        if (approvalDecision != null && !approvalDecision.allowed()) {
            String reason = approvalDecision.reason();
            emit(
                    updateConsumer,
                    request,
                    "failed",
                    "Permission denied: " + reason,
                    request.inputJson(),
                    command,
                    true
            );
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Permission denied: " + reason, true);
        }

        if (permissionDecision.requiresApproval() || explicitAsk) {
            if (approvalDecision == null) {
                emit(
                        updateConsumer,
                        request,
                        "permission_requested",
                        permissionRequest.reason(),
                        request.inputJson(),
                        command,
                        false,
                        permissionRequest.requestId(),
                        permissionRequest.interactionType(),
                        permissionRequest.interactionJson()
                );
                approvalDecision = permissionGateway.requestPermission(permissionRequest);
            }
            if (approvalDecision != null && approvalDecision.interrupt()) {
                throw new ToolExecutionCancelledException(approvalDecision.reason());
            }
            if (approvalDecision == null || !approvalDecision.allowed()) {
                String reason = approvalDecision == null ? "Permission request failed." : approvalDecision.reason();
                emit(
                        updateConsumer,
                        request,
                        "failed",
                        "Permission denied: " + reason,
                        request.inputJson(),
                        command,
                        true
                );
                return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Permission denied: " + reason, true);
            }
        }

        if (approvalDecision != null && approvalDecision.allowed()) {
            String updatedInputJson = approvalDecision.updatedInputJson();
            if (!updatedInputJson.isBlank() && !"{}".equals(updatedInputJson.trim())) {
                String updatedCommand = extractCommand(updatedInputJson);
                if (!updatedCommand.isBlank()) {
                    command = updatedCommand;
                }
            }
        }

        emit(updateConsumer, request, "progress", "Running " + command, request.inputJson(), command, false);
        Process process;
        try {
            process = new ProcessBuilder("/bin/zsh", "-lc", command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException exception) {
            emit(updateConsumer, request, "failed", "Failed to start bash tool: " + exception.getMessage(), request.inputJson(), command, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Failed to start bash tool: " + exception.getMessage(), true);
        }

        try {
            boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                emit(
                        updateConsumer,
                        request,
                        "failed",
                        "Command timed out after " + TIMEOUT.toSeconds() + "s: " + command,
                        request.inputJson(),
                        command,
                        true
                );
                return new ToolExecutionResult(
                        request.toolUseId(),
                        request.toolName(),
                        "Command timed out after " + TIMEOUT.toSeconds() + "s: " + command,
                        true
                );
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String normalized = output.length() > MAX_CAPTURE_CHARS
                    ? output.substring(0, MAX_CAPTURE_CHARS) + System.lineSeparator() + "... truncated ..."
                    : output;
            String text = """
                    Command: %s
                    Exit code: %s

                    %s
                    """.formatted(command, process.exitValue(), normalized.stripTrailing()).stripTrailing();
            boolean informationalExit = isInformationalNonZeroExit(command, process.exitValue());
            emit(
                    updateConsumer,
                    request,
                    (process.exitValue() == 0 || informationalExit) ? "completed" : "failed",
                    text,
                    request.inputJson(),
                    command,
                    process.exitValue() != 0 && !informationalExit
            );
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), text, process.exitValue() != 0 && !informationalExit);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionCancelledException("Prompt cancelled.");
        } catch (IOException exception) {
            emit(updateConsumer, request, "failed", "Failed to read bash output: " + exception.getMessage(), request.inputJson(), command, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Failed to read bash output: " + exception.getMessage(), true);
        }
    }

    private static void emit(
            Consumer<ToolExecutionUpdate> updateConsumer,
            ToolExecutionRequest request,
            String phase,
            String text,
            String inputJson,
            String command,
            boolean error
    ) {
        emit(updateConsumer, request, phase, text, inputJson, command, error, "");
    }

    private static void emit(
            Consumer<ToolExecutionUpdate> updateConsumer,
            ToolExecutionRequest request,
            String phase,
            String text,
            String inputJson,
            String command,
            boolean error,
            String permissionRequestId
    ) {
        emit(updateConsumer, request, phase, text, inputJson, command, error, permissionRequestId, "", "");
    }

    private static void emit(
            Consumer<ToolExecutionUpdate> updateConsumer,
            ToolExecutionRequest request,
            String phase,
            String text,
            String inputJson,
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
                inputJson,
                permissionRequestId,
                command,
                interactionType,
                interactionJson,
                error
        ));
    }

    private String extractCommand(String inputJson) {
        try {
            JsonNode root = objectMapper.readTree(inputJson);
            return root.path("command").asText("");
        } catch (Exception exception) {
            return "";
        }
    }

    private static boolean isInformationalNonZeroExit(String command, int exitCode) {
        if (exitCode != 1 || command == null || command.isBlank()) {
            return false;
        }
        String trimmed = command.trim();
        String firstToken = trimmed.split("\\s+", 2)[0];
        return switch (firstToken) {
            case "grep", "rg", "find", "diff", "test", "[" -> true;
            default -> false;
        };
    }
}
