package com.openclaude.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.openclaude.provider.spi.ProviderToolDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class GrepToolRuntime extends AbstractSingleToolRuntime {
    private static final List<String> VCS_DIRECTORIES_TO_EXCLUDE = List.of(".git", ".svn", ".hg", ".bzr", ".jj", ".sl");
    private static final int DEFAULT_HEAD_LIMIT = 250;
    private static final ProviderToolDefinition DEFINITION = new ProviderToolDefinition(
            "Grep",
            "A powerful search tool built on ripgrep semantics.",
            """
            {
              "type": "object",
              "properties": {
                "pattern": {"type": "string", "description": "Regular expression pattern to search for."},
                "path": {"type": "string", "description": "Absolute file or directory path to search. Defaults to current working directory."},
                "glob": {"type": "string", "description": "Optional glob filter such as *.java or **/*.md."},
                "output_mode": {"type": "string", "enum": ["content", "files_with_matches", "count"], "description": "Result mode. Defaults to files_with_matches."},
                "-B": {"type": "integer", "minimum": 0, "description": "Number of lines to show before each match in content mode."},
                "-A": {"type": "integer", "minimum": 0, "description": "Number of lines to show after each match in content mode."},
                "-C": {"type": "integer", "minimum": 0, "description": "Alias for context."},
                "context": {"type": "integer", "minimum": 0, "description": "Number of lines to show before and after each match in content mode."},
                "-n": {"type": "boolean", "description": "Show line numbers in content mode. Defaults to true."},
                "-i": {"type": "boolean", "description": "Case-insensitive search."},
                "type": {"type": "string", "description": "Optional ripgrep file type filter such as java, ts, py."},
                "head_limit": {"type": "integer", "minimum": 0, "description": "Limit number of returned entries."},
                "offset": {"type": "integer", "minimum": 0, "description": "Skip the first N entries before applying head_limit."},
                "multiline": {"type": "boolean", "description": "Enable multiline ripgrep mode."}
              },
              "required": ["pattern"],
              "additionalProperties": false
            }
            """
    );

    public GrepToolRuntime() {
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
        String patternValue = ToolJson.string(input, "pattern");
        String pathValue = ToolJson.string(input, "path");
        String globValue = ToolJson.string(input, "glob");
        String outputMode = ToolJson.string(input, "output_mode");
        Integer beforeContext = ToolJson.integer(input, "-B");
        Integer afterContext = ToolJson.integer(input, "-A");
        Integer context = ToolJson.integer(input, "-C");
        if (context == null) {
            context = ToolJson.integer(input, "context");
        }
        Boolean showLineNumbers = ToolJson.bool(input, "-n");
        Boolean caseInsensitive = ToolJson.bool(input, "-i");
        String type = ToolJson.string(input, "type");
        Integer headLimit = ToolJson.integer(input, "head_limit");
        Integer offset = ToolJson.integer(input, "offset");
        Boolean multiline = ToolJson.bool(input, "multiline");

        if (patternValue.isBlank()) {
            emit(updateConsumer, request, "failed", "Missing required Grep.pattern.", "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Missing required Grep.pattern.", true);
        }

        Path root = pathValue.isBlank() ? Path.of("").toAbsolutePath().normalize() : Path.of(pathValue);
        if (!root.isAbsolute()) {
            emit(updateConsumer, request, "failed", "Grep.path must be an absolute path when provided.", pathValue, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Grep.path must be an absolute path when provided.", true);
        }
        if (!Files.exists(root)) {
            emit(updateConsumer, request, "failed", "Grep.path does not exist: " + root, pathValue, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Grep.path does not exist: " + root, true);
        }

        emit(updateConsumer, request, "started", "Searching for " + patternValue, root.toString(), false);
        try {
            String normalizedMode = outputMode == null || outputMode.isBlank() ? "files_with_matches" : outputMode;
            int effectiveOffset = Math.max(0, offset == null ? 0 : offset);
            int effectiveLimit = headLimit == null ? DEFAULT_HEAD_LIMIT : Math.max(0, headLimit);
            List<String> command = buildCommand(
                    patternValue,
                    root,
                    globValue,
                    normalizedMode,
                    beforeContext,
                    afterContext,
                    context,
                    showLineNumbers,
                    caseInsensitive,
                    type,
                    multiline
            );
            ProcessResult processResult = run(command, root);
            String text = renderResults(
                    root,
                    normalizedMode,
                    effectiveOffset,
                    effectiveLimit,
                    processResult
            );
            emit(updateConsumer, request, "completed", text, root.toString(), false);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), text, false);
        } catch (IOException exception) {
            emit(updateConsumer, request, "failed", "Grep failed: " + exception.getMessage(), root.toString(), true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Grep failed: " + exception.getMessage(), true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            emit(updateConsumer, request, "failed", "Grep was interrupted.", root.toString(), true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Grep was interrupted.", true);
        }
    }

    private static List<String> buildCommand(
            String pattern,
            Path root,
            String glob,
            String outputMode,
            Integer beforeContext,
            Integer afterContext,
            Integer context,
            Boolean showLineNumbers,
            Boolean caseInsensitive,
            String type,
            Boolean multiline
    ) {
        List<String> command = new ArrayList<>();
        command.add("rg");
        command.add("--hidden");
        command.add("--max-columns");
        command.add("500");
        for (String vcsDirectory : VCS_DIRECTORIES_TO_EXCLUDE) {
            command.add("--glob");
            command.add("!" + vcsDirectory);
        }
        if (Boolean.TRUE.equals(multiline)) {
            command.add("-U");
            command.add("--multiline-dotall");
        }
        if (Boolean.TRUE.equals(caseInsensitive)) {
            command.add("-i");
        }
        if ("files_with_matches".equals(outputMode)) {
            command.add("-l");
        } else if ("count".equals(outputMode)) {
            command.add("-c");
        }
        if ("content".equals(outputMode)) {
            if (showLineNumbers == null || showLineNumbers) {
                command.add("-n");
            }
            if (beforeContext != null) {
                command.add("-B");
                command.add(Integer.toString(beforeContext));
            }
            if (afterContext != null) {
                command.add("-A");
                command.add(Integer.toString(afterContext));
            }
            if (context != null) {
                command.add("-C");
                command.add(Integer.toString(context));
            }
        }
        if (glob != null && !glob.isBlank()) {
            command.add("--glob");
            command.add(glob);
        }
        if (type != null && !type.isBlank()) {
            command.add("--type");
            command.add(type);
        }
        command.add(pattern);
        command.add(root.toString());
        return command;
    }

    private static ProcessResult run(List<String> command, Path root) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(Files.isDirectory(root) ? root.toFile() : root.getParent().toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0 && exitCode != 1) {
            throw new IOException(output.isBlank() ? "rg exited with code " + exitCode : output.strip());
        }
        return new ProcessResult(exitCode, output);
    }

    private static String renderResults(
            Path root,
            String outputMode,
            int offset,
            int limit,
            ProcessResult processResult
    ) {
        List<String> lines = Arrays.stream(processResult.output().split("\\R"))
                .map(String::stripTrailing)
                .filter(line -> !line.isBlank())
                .toList();
        List<String> visible = applyWindow(lines, offset, limit);
        Path cwd = Path.of("").toAbsolutePath().normalize();

        return switch (outputMode) {
            case "content" -> {
                if (visible.isEmpty()) {
                    yield "No matches found";
                }
                String content = visible.stream()
                        .map(line -> relativizeResultLine(cwd, line))
                        .collect(Collectors.joining(System.lineSeparator()));
                String limitInfo = formatLimitInfo(lines.size(), offset, limit);
                yield limitInfo.isBlank() ? content : content + System.lineSeparator() + System.lineSeparator() + "[Showing results with pagination = " + limitInfo + "]";
            }
            case "count" -> {
                if (visible.isEmpty()) {
                    yield "No matches found";
                }
                int fileCount = visible.size();
                int matchCount = visible.stream().mapToInt(GrepToolRuntime::extractCount).sum();
                String raw = visible.stream()
                        .map(line -> relativizeResultLine(cwd, line))
                        .collect(Collectors.joining(System.lineSeparator()));
                String limitInfo = formatLimitInfo(lines.size(), offset, limit);
                String summary = "Found " + matchCount + " total " + (matchCount == 1 ? "occurrence" : "occurrences")
                        + " across " + fileCount + " " + (fileCount == 1 ? "file" : "files") + ".";
                if (!limitInfo.isBlank()) {
                    summary += " with pagination = " + limitInfo;
                }
                yield raw + System.lineSeparator() + System.lineSeparator() + summary;
            }
            default -> {
                if (visible.isEmpty()) {
                    yield "No files found";
                }
                String limitInfo = formatLimitInfo(lines.size(), offset, limit);
                String body = visible.stream()
                        .map(line -> relativizeResultLine(cwd, line))
                        .collect(Collectors.joining(System.lineSeparator()));
                String header = "Found " + visible.size() + " " + (visible.size() == 1 ? "file" : "files");
                if (!limitInfo.isBlank()) {
                    header += " " + limitInfo;
                }
                yield header + System.lineSeparator() + body;
            }
        };
    }

    private static List<String> applyWindow(List<String> items, int offset, int limit) {
        if (offset >= items.size()) {
            return List.of();
        }
        if (limit == 0) {
            return items.subList(offset, items.size());
        }
        int effectiveLimit = Math.max(0, limit);
        int endIndex = Math.min(items.size(), offset + effectiveLimit);
        return items.subList(offset, endIndex);
    }

    private static String formatLimitInfo(int totalItems, int offset, int limit) {
        if (limit == 0) {
            return offset > 0 ? "offset: " + offset : "";
        }
        boolean truncated = totalItems - offset > limit;
        List<String> parts = new ArrayList<>();
        if (truncated) {
            parts.add("limit: " + limit);
        }
        if (offset > 0) {
            parts.add("offset: " + offset);
        }
        return String.join(", ", parts);
    }

    private static int extractCount(String line) {
        int separator = line.lastIndexOf(':');
        if (separator < 0 || separator == line.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(line.substring(separator + 1).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String relativizeResultLine(Path cwd, String line) {
        int separator = line.indexOf(':');
        String candidatePath = separator >= 0 ? line.substring(0, separator) : line;
        Path candidate = Path.of(candidatePath).toAbsolutePath().normalize();
        if (!candidate.isAbsolute() || !candidate.startsWith(cwd)) {
            return line;
        }
        String relative = cwd.relativize(candidate).toString();
        return separator >= 0 ? relative + line.substring(separator) : relative;
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
