package com.openclaude.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.openclaude.provider.spi.ProviderToolDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class FileEditToolRuntime extends AbstractSingleToolRuntime {
    private static final ProviderToolDefinition DEFINITION = new ProviderToolDefinition(
            "Edit",
            "Perform exact string replacements in files.",
            """
            {
              "type": "object",
              "properties": {
                "file_path": {"type": "string", "description": "Absolute path to the file to edit."},
                "old_string": {"type": "string", "description": "Exact text to replace."},
                "new_string": {"type": "string", "description": "Replacement text."},
                "replace_all": {"type": "boolean", "description": "Replace all occurrences instead of requiring a unique match."}
              },
              "required": ["file_path", "old_string", "new_string"],
              "additionalProperties": false
            }
            """
    );

    public FileEditToolRuntime() {
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
        String oldString = ToolJson.string(input, "old_string");
        String newString = ToolJson.string(input, "new_string");
        boolean replaceAll = Boolean.TRUE.equals(ToolJson.bool(input, "replace_all"));

        if (filePathValue.isBlank()) {
            emit(updateConsumer, request, "failed", "Missing required Edit.file_path.", "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Missing required Edit.file_path.", true);
        }
        Path filePath = Path.of(filePathValue);
        if (!filePath.isAbsolute()) {
            emit(updateConsumer, request, "failed", "Edit.file_path must be an absolute path.", filePathValue, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Edit.file_path must be an absolute path.", true);
        }
        if (!Files.exists(filePath)) {
            emit(updateConsumer, request, "failed", "File does not exist: " + filePath, filePathValue, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "File does not exist: " + filePath, true);
        }

        try {
            ToolPermissionDecision decision = requestPermission(
                    request,
                    permissionGateway,
                    updateConsumer,
                    filePath.toString(),
                    "Allow editing file " + filePath + "?",
                    "file_edit",
                    request.inputJson()
            );
            if (!decision.allowed()) {
                emit(updateConsumer, request, "failed", "Permission denied: " + decision.reason(), filePath.toString(), true);
                return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Permission denied: " + decision.reason(), true);
            }

            if (!decision.updatedInputJson().isBlank() && !"{}".equals(decision.updatedInputJson().trim())) {
                input = ToolJson.parse(decision.updatedInputJson());
                filePathValue = ToolJson.string(input, "file_path");
                oldString = ToolJson.string(input, "old_string");
                newString = ToolJson.string(input, "new_string");
                replaceAll = Boolean.TRUE.equals(ToolJson.bool(input, "replace_all"));
                if (filePathValue.isBlank()) {
                    emit(updateConsumer, request, "failed", "Missing required Edit.file_path.", "", true);
                    return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Missing required Edit.file_path.", true);
                }
                filePath = Path.of(filePathValue);
                if (!filePath.isAbsolute()) {
                    emit(updateConsumer, request, "failed", "Edit.file_path must be an absolute path.", filePathValue, true);
                    return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Edit.file_path must be an absolute path.", true);
                }
                if (!Files.exists(filePath)) {
                    emit(updateConsumer, request, "failed", "File does not exist: " + filePath, filePathValue, true);
                    return new ToolExecutionResult(request.toolUseId(), request.toolName(), "File does not exist: " + filePath, true);
                }
            }

            String original = Files.readString(filePath, StandardCharsets.UTF_8);
            String mutationGuardError = FileMutationGuards.requireFreshReadForMutation(request.session(), filePath, original);
            if (mutationGuardError != null) {
                emit(updateConsumer, request, "failed", mutationGuardError, filePath.toString(), true);
                return new ToolExecutionResult(request.toolUseId(), request.toolName(), mutationGuardError, true);
            }

            int occurrences = countOccurrences(original, oldString);
            if (occurrences == 0) {
                String text = "Edit failed: old_string not found in " + filePath;
                emit(updateConsumer, request, "failed", text, filePath.toString(), true);
                return new ToolExecutionResult(request.toolUseId(), request.toolName(), text, true);
            }
            if (!replaceAll && occurrences > 1) {
                String text = "Edit failed: old_string is not unique in " + filePath + " (" + occurrences + " matches).";
                emit(updateConsumer, request, "failed", text, filePath.toString(), true);
                return new ToolExecutionResult(request.toolUseId(), request.toolName(), text, true);
            }

            emit(updateConsumer, request, "progress", "Editing " + filePath, filePath.toString(), false);
            String updated = replaceAll ? original.replace(oldString, newString) : original.replaceFirst(java.util.regex.Pattern.quote(oldString), java.util.regex.Matcher.quoteReplacement(newString));
            String patch = UnifiedPatchRenderer.render(filePath.toString(), original, updated);
            Files.writeString(filePath, updated, StandardCharsets.UTF_8);
            int replacedCount = replaceAll ? occurrences : 1;
            String text = "Edited %s (%s replacement%s).".formatted(
                    filePath,
                    replacedCount,
                    replacedCount == 1 ? "" : "s"
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
                            updated,
                            FileMutationGuards.getFileModificationTime(filePath),
                            null,
                            null,
                            false
                    )
            );
        } catch (IOException exception) {
            emit(updateConsumer, request, "failed", "Failed to edit file: " + exception.getMessage(), filePath.toString(), true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Failed to edit file: " + exception.getMessage(), true);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count += 1;
            index += needle.length();
        }
        return count;
    }
}
