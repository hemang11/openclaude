package com.openclaude.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.openclaude.provider.spi.ProviderToolDefinition;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public final class GlobToolRuntime extends AbstractSingleToolRuntime {
    private static final int MAX_RESULTS = 100;
    private static final ProviderToolDefinition DEFINITION = new ProviderToolDefinition(
            "Glob",
            "Fast file pattern matching tool that works with any codebase size.",
            """
            {
              "type": "object",
              "properties": {
                "pattern": {"type": "string", "description": "Glob pattern such as **/*.java or src/**/*.ts."},
                "path": {"type": "string", "description": "Absolute directory path to search from. Defaults to current working directory."}
              },
              "required": ["pattern"],
              "additionalProperties": false
            }
            """
    );

    public GlobToolRuntime() {
        super(DEFINITION);
    }

    @Override
    protected boolean isConcurrencySafeSingle(String inputJson) {
        JsonNode input = ToolJson.parse(inputJson);
        return !ToolJson.string(input, "pattern").isBlank();
    }

    @Override
    protected ToolExecutionResult executeSingle(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer
    ) {
        JsonNode input = ToolJson.parse(request.inputJson());
        String pattern = ToolJson.string(input, "pattern");
        String rootValue = ToolJson.string(input, "path");
        Path root = rootValue.isBlank() ? Path.of("").toAbsolutePath().normalize() : Path.of(rootValue);

        if (pattern.isBlank()) {
            emit(updateConsumer, request, "failed", "Missing required Glob.pattern.", pattern, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Missing required Glob.pattern.", true);
        }
        if (!root.isAbsolute()) {
            emit(updateConsumer, request, "failed", "Glob.path must be an absolute path when provided.", rootValue, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Glob.path must be an absolute path when provided.", true);
        }
        if (!Files.isDirectory(root)) {
            emit(updateConsumer, request, "failed", "Glob.path must be an existing directory: " + root, rootValue, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Glob.path must be an existing directory: " + root, true);
        }

        emit(updateConsumer, request, "started", "Globbing " + pattern + " under " + root, root.toString(), false);
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<PathWithTime> matches = Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(path -> matcher.matches(root.relativize(path)))
                    .map(path -> new PathWithTime(path, lastModified(path)))
                    .sorted(Comparator.comparing(PathWithTime::modifiedAt).reversed())
                    .limit(MAX_RESULTS)
                    .toList();

            StringBuilder output = new StringBuilder();
            if (matches.isEmpty()) {
                output.append("No files found");
            } else {
                Path cwd = Path.of("").toAbsolutePath().normalize();
                for (PathWithTime match : matches) {
                    output.append(toDisplayPath(cwd, match.path())).append(System.lineSeparator());
                }
            }

            String text = output.toString().stripTrailing();
            emit(updateConsumer, request, "completed", text, root.toString(), false);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), text, false);
        } catch (IOException exception) {
            emit(updateConsumer, request, "failed", "Glob failed: " + exception.getMessage(), root.toString(), true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Glob failed: " + exception.getMessage(), true);
        }
    }

    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException exception) {
            return FileTime.fromMillis(0L);
        }
    }

    private static String toDisplayPath(Path cwd, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(cwd)) {
            return cwd.relativize(normalized).toString();
        }
        return normalized.toString();
    }

    private record PathWithTime(Path path, FileTime modifiedAt) {
    }
}
