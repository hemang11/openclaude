package com.openclaude.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaude.core.config.OpenClaudePaths;
import com.openclaude.core.session.ConversationSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ToolHookConfigLoader {
    private static final Path MANAGED_SETTINGS_PATH = Path.of("/etc/openclaude/settings.json");
    private static final Pattern SIMPLE_MATCHER_PATTERN = Pattern.compile("^[a-zA-Z0-9_|]+$");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Path> baseSettingsFiles;

    public ToolHookConfigLoader() {
        this(List.of(MANAGED_SETTINGS_PATH, OpenClaudePaths.configHome().resolve("settings.json")));
    }

    public ToolHookConfigLoader(List<Path> baseSettingsFiles) {
        this.baseSettingsFiles = baseSettingsFiles == null ? List.of() : List.copyOf(baseSettingsFiles);
    }

    public List<ToolHookCommand> loadCommands(
            ConversationSession session,
            ToolHookEvent event,
            String matchQuery
    ) {
        List<ToolHookCommand> commands = new ArrayList<>();
        for (Path settingsFile : settingsFiles(session)) {
            commands.addAll(loadCommandsFromFile(settingsFile, event, matchQuery));
        }
        return List.copyOf(commands);
    }

    private List<Path> settingsFiles(ConversationSession session) {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        for (Path file : baseSettingsFiles) {
            addIfPresent(files, file);
        }

        Path workspaceRoot = resolveWorkspaceRoot(session);
        if (workspaceRoot != null) {
            addIfPresent(files, workspaceRoot.resolve(".openclaude").resolve("settings.json"));
            addIfPresent(files, workspaceRoot.resolve(".openclaude").resolve("settings.local.json"));
        }
        return List.copyOf(files);
    }

    private List<ToolHookCommand> loadCommandsFromFile(
            Path settingsFile,
            ToolHookEvent event,
            String matchQuery
    ) {
        if (settingsFile == null || !Files.exists(settingsFile)) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(settingsFile.toFile());
            JsonNode hooksNode = root.path("hooks").path(event.configKey());
            if (!hooksNode.isArray()) {
                return List.of();
            }

            List<ToolHookCommand> commands = new ArrayList<>();
            for (JsonNode matcherNode : hooksNode) {
                String matcher = matcherNode.path("matcher").asText("");
                if (!matchesPattern(matchQuery, matcher)) {
                    continue;
                }

                JsonNode hookList = matcherNode.path("hooks");
                if (!hookList.isArray()) {
                    continue;
                }

                for (JsonNode hookNode : hookList) {
                    if (!"command".equals(hookNode.path("type").asText(""))) {
                        continue;
                    }
                    String command = hookNode.path("command").asText("").trim();
                    if (command.isBlank()) {
                        continue;
                    }
                    String shell = hookNode.path("shell").asText("");
                    Integer timeoutSeconds = hookNode.has("timeout") && hookNode.get("timeout").canConvertToInt()
                            ? Math.max(1, hookNode.get("timeout").asInt())
                            : null;
                    commands.add(new ToolHookCommand(
                            command,
                            shell,
                            timeoutSeconds,
                            settingsFile.toAbsolutePath().normalize()
                    ));
                }
            }
            return List.copyOf(commands);
        } catch (IOException exception) {
            return List.of();
        }
    }

    private static boolean matchesPattern(String matchQuery, String matcher) {
        String normalizedQuery = matchQuery == null ? "" : matchQuery;
        if (matcher == null || matcher.isBlank() || "*".equals(matcher)) {
            return true;
        }
        if (SIMPLE_MATCHER_PATTERN.matcher(matcher).matches()) {
            if (matcher.contains("|")) {
                return java.util.Arrays.stream(matcher.split("\\|"))
                        .map(String::trim)
                        .anyMatch(normalizedQuery::equals);
            }
            return normalizedQuery.equals(matcher);
        }
        try {
            return Pattern.compile(matcher).matcher(normalizedQuery).find();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static Path resolveWorkspaceRoot(ConversationSession session) {
        if (session == null) {
            return null;
        }
        String workspaceRoot = session.workspaceRoot();
        if (workspaceRoot != null && !workspaceRoot.isBlank()) {
            return Path.of(workspaceRoot).toAbsolutePath().normalize();
        }
        String workingDirectory = session.workingDirectory();
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            return Path.of(workingDirectory).toAbsolutePath().normalize();
        }
        return null;
    }

    private static void addIfPresent(LinkedHashSet<Path> files, Path path) {
        if (path == null) {
            return;
        }
        files.add(path.toAbsolutePath().normalize());
    }

    public enum ToolHookEvent {
        PRE_TOOL_USE("PreToolUse"),
        POST_TOOL_USE("PostToolUse"),
        POST_TOOL_USE_FAILURE("PostToolUseFailure"),
        PERMISSION_REQUEST("PermissionRequest"),
        PERMISSION_DENIED("PermissionDenied");

        private final String configKey;

        ToolHookEvent(String configKey) {
            this.configKey = Objects.requireNonNull(configKey, "configKey");
        }

        public String configKey() {
            return configKey;
        }
    }

    public record ToolHookCommand(
            String command,
            String shell,
            Integer timeoutSeconds,
            Path sourceFile
    ) {
        public ToolHookCommand {
            command = command == null ? "" : command;
            shell = shell == null ? "" : shell;
            sourceFile = sourceFile == null ? null : sourceFile.toAbsolutePath().normalize();
        }
    }
}
