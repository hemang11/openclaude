package com.openclaude.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.openclaude.provider.spi.ProviderToolDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public final class FileReadToolRuntime extends AbstractSingleToolRuntime {
    private static final int MAX_LINES = 2_000;
    private static final ProviderToolDefinition DEFINITION = new ProviderToolDefinition(
            "Read",
            "Read a file from the local filesystem.",
            """
            {
              "type": "object",
              "properties": {
                "file_path": {"type": "string", "description": "Absolute path to the file to read."},
                "offset": {"type": "integer", "minimum": 1, "description": "1-based line offset to start from."},
                "limit": {"type": "integer", "minimum": 1, "description": "Maximum number of lines to read."}
              },
              "required": ["file_path"],
              "additionalProperties": false
            }
            """
    );

    public FileReadToolRuntime() {
        super(DEFINITION);
    }

    @Override
    protected boolean isConcurrencySafeSingle(String inputJson) {
        JsonNode input = ToolJson.parse(inputJson);
        return !ToolJson.string(input, "file_path").isBlank();
    }

    @Override
    protected ToolExecutionResult executeSingle(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer
    ) {
        JsonNode input = ToolJson.parse(request.inputJson());
        String filePathValue = ToolJson.string(input, "file_path");
        Integer offset = ToolJson.integer(input, "offset");
        Integer limit = ToolJson.integer(input, "limit");

        if (filePathValue.isBlank()) {
            emit(updateConsumer, request, "failed", "Missing required Read.file_path.", filePathValue, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Missing required Read.file_path.", true);
        }

        Path filePath = Path.of(filePathValue);
        if (!filePath.isAbsolute()) {
            emit(updateConsumer, request, "failed", "Read.file_path must be an absolute path.", filePathValue, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Read.file_path must be an absolute path.", true);
        }
        if (!Files.exists(filePath)) {
            emit(updateConsumer, request, "failed", "File does not exist: " + filePath, filePathValue, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "File does not exist: " + filePath, true);
        }
        if (Files.isDirectory(filePath)) {
            emit(updateConsumer, request, "failed", "Read only supports files, not directories: " + filePath, filePathValue, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Read only supports files, not directories: " + filePath, true);
        }

        emit(updateConsumer, request, "started", "Reading " + filePath, filePathValue, false);
        try {
            String rawContent = Files.readString(filePath, StandardCharsets.UTF_8);
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            int startLine = Math.max(1, offset == null ? 1 : offset);
            int effectiveLimit = Math.max(1, limit == null ? MAX_LINES : Math.min(limit, MAX_LINES));
            int startIndex = Math.min(lines.size(), startLine - 1);
            int endIndex = Math.min(lines.size(), startIndex + effectiveLimit);

            StringBuilder output = new StringBuilder();
            if (lines.isEmpty()) {
                output.append("<system-reminder>Warning: the file exists but the contents are empty.</system-reminder>");
            } else if (startIndex >= lines.size()) {
                output.append("<system-reminder>Warning: the file exists but is shorter than the provided offset (")
                        .append(startLine)
                        .append("). The file has ")
                        .append(lines.size())
                        .append(" lines.</system-reminder>");
            }
            for (int lineIndex = startIndex; lineIndex < endIndex; lineIndex += 1) {
                if (output.length() > 0) {
                    output.append(System.lineSeparator());
                }
                output.append(lineIndex + 1).append('\t').append(lines.get(lineIndex));
            }

            String text = output.toString().stripTrailing();
            emit(updateConsumer, request, "completed", text, filePathValue, false);
            return new ToolExecutionResult(
                    request.toolUseId(),
                    request.toolName(),
                    text,
                    false,
                    FileMutationGuards.recordReadState(
                            request.session(),
                            filePath,
                            rawContent,
                            FileMutationGuards.getFileModificationTime(filePath),
                            offset,
                            limit,
                            false
                    )
            );
        } catch (IOException exception) {
            emit(updateConsumer, request, "failed", "Failed to read file: " + exception.getMessage(), filePathValue, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Failed to read file: " + exception.getMessage(), true);
        }
    }
}
