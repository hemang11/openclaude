package com.openclaude.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.openclaude.provider.spi.ProviderToolDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class FileWriteToolRuntime extends AbstractSingleToolRuntime {
    private static final ProviderToolDefinition DEFINITION = new ProviderToolDefinition(
            "Write",
            "Write a file to the local filesystem.",
            """
            {
              "type": "object",
              "properties": {
                "file_path": {"type": "string", "description": "Absolute path to the file to write."},
                "content": {"type": "string", "description": "Full file contents to write."}
              },
              "required": ["file_path", "content"],
              "additionalProperties": false
            }
            """
    );

    public FileWriteToolRuntime() {
        super(DEFINITION);
    }

    @Override
    protected ToolExecutionResult executeSingle(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer
    ) {
        JsonNode input = ToolJson.parse(request.inputJson());
        String filePathValue = ToolJson.string(input, "file_path");
        String content = ToolJson.string(input, "content");

        if (filePathValue.isBlank()) {
            emit(updateConsumer, request, "failed", "Missing required Write.file_path.", "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Missing required Write.file_path.", true);
        }
        Path filePath = Path.of(filePathValue);
        if (!filePath.isAbsolute()) {
            emit(updateConsumer, request, "failed", "Write.file_path must be an absolute path.", filePathValue, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Write.file_path must be an absolute path.", true);
        }

        try {
            ToolPermissionDecision decision = requestPermission(
                    request,
                    permissionGateway,
                    updateConsumer,
                    filePath.toString(),
                    "Allow writing file " + filePath + "?",
                    "file_write",
                    request.inputJson()
            );
            if (!decision.allowed()) {
                emit(updateConsumer, request, "failed", "Permission denied: " + decision.reason(), filePath.toString(), true);
                return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Permission denied: " + decision.reason(), true);
            }

            if (!decision.updatedInputJson().isBlank() && !"{}".equals(decision.updatedInputJson().trim())) {
                input = ToolJson.parse(decision.updatedInputJson());
                filePathValue = ToolJson.string(input, "file_path");
                content = ToolJson.string(input, "content");
                if (filePathValue.isBlank()) {
                    emit(updateConsumer, request, "failed", "Missing required Write.file_path.", "", true);
                    return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Missing required Write.file_path.", true);
                }
                filePath = Path.of(filePathValue);
                if (!filePath.isAbsolute()) {
                    emit(updateConsumer, request, "failed", "Write.file_path must be an absolute path.", filePathValue, true);
                    return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Write.file_path must be an absolute path.", true);
                }
            }

            String original = "";
            boolean updatingExistingFile = Files.exists(filePath);
            if (Files.exists(filePath)) {
                original = Files.readString(filePath, StandardCharsets.UTF_8);
                String mutationGuardError = FileMutationGuards.requireFreshReadForMutation(request.session(), filePath, original);
                if (mutationGuardError != null) {
                    emit(updateConsumer, request, "failed", mutationGuardError, filePath.toString(), true);
                    return new ToolExecutionResult(request.toolUseId(), request.toolName(), mutationGuardError, true);
                }
            }

            emit(updateConsumer, request, "progress", "Writing " + filePath, filePath.toString(), false);
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            String patch = UnifiedPatchRenderer.render(filePath.toString(), original, content);
            String text = "%s %s (%s chars).".formatted(
                    updatingExistingFile ? "Updated" : "Wrote",
                    filePath,
                    content.length()
            );
            emit(updateConsumer, request, "completed", patch, filePath.toString(), false);
            return new ToolExecutionResult(
                    request.toolUseId(),
                    request.toolName(),
                    text,
                    false,
                    patch,
                    FileMutationGuards.recordReadState(
                            request.session(),
                            filePath,
                            content,
                            FileMutationGuards.getFileModificationTime(filePath),
                            null,
                            null,
                            false
                    )
            );
        } catch (IOException exception) {
            emit(updateConsumer, request, "failed", "Failed to write file: " + exception.getMessage(), filePath.toString(), true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Failed to write file: " + exception.getMessage(), true);
        }
    }
}
