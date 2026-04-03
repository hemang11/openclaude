package com.openclaude.core.instructions;

import com.openclaude.core.config.OpenClaudePaths;
import com.openclaude.core.session.ConversationSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AgentsInstructionsLoader {
    private static final String INSTRUCTION_PROMPT = """
            Codebase and user instructions are shown below. Be sure to adhere to these instructions.
            IMPORTANT: These instructions OVERRIDE any default behavior and you MUST follow them exactly as written.
            """.strip();
    private static final Pattern INCLUDE_PATTERN = Pattern.compile("(?:^|\\s)@((?:[^\\s\\\\]|\\\\ )+)");
    private static final Set<String> ALLOWED_INCLUDE_EXTENSIONS = Set.of(
            ".md", ".txt", ".text", ".json", ".yaml", ".yml", ".toml", ".xml", ".csv",
            ".html", ".htm", ".css", ".scss", ".sass", ".less",
            ".js", ".ts", ".tsx", ".jsx", ".mjs", ".cjs", ".mts", ".cts",
            ".py", ".pyi", ".pyw", ".rb", ".erb", ".rake",
            ".go", ".rs", ".java", ".kt", ".kts", ".scala",
            ".c", ".cpp", ".cc", ".cxx", ".h", ".hpp", ".hxx", ".cs", ".swift",
            ".sh", ".bash", ".zsh", ".fish", ".ps1", ".bat", ".cmd",
            ".env", ".ini", ".cfg", ".conf", ".config", ".properties",
            ".sql", ".graphql", ".gql", ".proto", ".vue", ".svelte", ".astro",
            ".ejs", ".hbs", ".pug", ".jade", ".php", ".pl", ".pm", ".lua", ".r", ".dart",
            ".ex", ".exs", ".erl", ".hrl", ".clj", ".cljs", ".cljc", ".edn", ".hs", ".lhs",
            ".elm", ".ml", ".mli", ".f", ".f90", ".f95", ".for", ".cmake", ".make",
            ".makefile", ".gradle", ".sbt", ".rst", ".adoc", ".asciidoc", ".org", ".tex",
            ".latex", ".lock", ".log", ".diff", ".patch", ".groovy"
    );
    private static final int MAX_INCLUDE_DEPTH = 5;

    private final Path managedRoot;
    private final Path userRoot;

    public AgentsInstructionsLoader() {
        this(Path.of("/etc/openclaude"), OpenClaudePaths.configHome());
    }

    public AgentsInstructionsLoader(Path managedRoot, Path userRoot) {
        this.managedRoot = normalizeNullable(managedRoot);
        this.userRoot = normalizeNullable(userRoot);
    }

    public List<InstructionFile> load(ConversationSession session) {
        Objects.requireNonNull(session, "session");
        Path workingDirectory = resolveWorkingDirectory(session);
        List<InstructionFile> result = new ArrayList<>();
        Set<String> processedPaths = new LinkedHashSet<>();

        if (managedRoot != null) {
            result.addAll(loadFile(managedRoot.resolve("AGENTS.md"), InstructionScope.MANAGED, processedPaths, null, 0));
            result.addAll(loadRulesDirectory(managedRoot.resolve("rules"), InstructionScope.MANAGED, processedPaths));
        }
        if (userRoot != null) {
            result.addAll(loadFile(userRoot.resolve("AGENTS.md"), InstructionScope.USER, processedPaths, null, 0));
            result.addAll(loadRulesDirectory(userRoot.resolve("rules"), InstructionScope.USER, processedPaths));
        }

        for (Path directory : ancestorDirectoriesRootToCwd(workingDirectory)) {
            result.addAll(loadFile(directory.resolve("AGENTS.md"), InstructionScope.PROJECT, processedPaths, null, 0));
            result.addAll(loadFile(directory.resolve(".openclaude").resolve("AGENTS.md"), InstructionScope.PROJECT, processedPaths, null, 0));
            result.addAll(loadRulesDirectory(directory.resolve(".openclaude").resolve("rules"), InstructionScope.PROJECT, processedPaths));
            result.addAll(loadFile(directory.resolve("AGENTS.local.md"), InstructionScope.LOCAL, processedPaths, null, 0));
        }

        return List.copyOf(result);
    }

    public String renderSystemPrompt(ConversationSession session) {
        List<InstructionFile> files = load(session);
        if (files.isEmpty()) {
            return "";
        }

        List<String> sections = new ArrayList<>();
        for (InstructionFile file : files) {
            if (file.content().isBlank()) {
                continue;
            }
            sections.add("Contents of " + file.path() + descriptionFor(file.scope()) + ":" + System.lineSeparator()
                    + System.lineSeparator()
                    + file.content());
        }
        if (sections.isEmpty()) {
            return "";
        }
        return INSTRUCTION_PROMPT + System.lineSeparator() + System.lineSeparator() + String.join(
                System.lineSeparator() + System.lineSeparator(),
                sections
        );
    }

    private List<InstructionFile> loadRulesDirectory(
            Path rulesDirectory,
            InstructionScope scope,
            Set<String> processedPaths
    ) {
        if (rulesDirectory == null || !Files.isDirectory(rulesDirectory)) {
            return List.of();
        }

        List<Path> markdownFiles = new ArrayList<>();
        try {
            Files.walkFileTree(rulesDirectory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")) {
                        markdownFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            return List.of();
        }

        markdownFiles.sort(Path::compareTo);
        List<InstructionFile> result = new ArrayList<>();
        for (Path file : markdownFiles) {
            result.addAll(loadFile(file, scope, processedPaths, null, 0));
        }
        return result;
    }

    private List<InstructionFile> loadFile(
            Path file,
            InstructionScope scope,
            Set<String> processedPaths,
            Path parent,
            int depth
    ) {
        if (file == null || depth >= MAX_INCLUDE_DEPTH) {
            return List.of();
        }

        Path normalized = normalizeNullable(file);
        if (normalized == null || !Files.isRegularFile(normalized)) {
            return List.of();
        }

        String processedKey = normalizedForDedup(normalized);
        if (!processedPaths.add(processedKey)) {
            return List.of();
        }

        String content;
        try {
            content = Files.readString(normalized, StandardCharsets.UTF_8).strip();
        } catch (IOException ignored) {
            return List.of();
        }
        if (content.isBlank()) {
            return List.of();
        }

        List<InstructionFile> result = new ArrayList<>();
        result.add(new InstructionFile(normalized, scope, content, parent));

        for (Path includePath : extractIncludePaths(content, normalized)) {
            if (!isAllowedIncludePath(includePath)) {
                continue;
            }
            result.addAll(loadFile(includePath, scope, processedPaths, normalized, depth + 1));
        }
        return result;
    }

    private List<Path> extractIncludePaths(String content, Path baseFile) {
        List<Path> includePaths = new ArrayList<>();
        boolean inFence = false;
        for (String line : content.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }

            Matcher matcher = INCLUDE_PATTERN.matcher(line);
            while (matcher.find()) {
                String rawPath = matcher.group(1);
                if (rawPath == null || rawPath.isBlank()) {
                    continue;
                }
                String includePath = rawPath.replace("\\ ", " ");
                int hashIndex = includePath.indexOf('#');
                if (hashIndex >= 0) {
                    includePath = includePath.substring(0, hashIndex);
                }
                if (includePath.isBlank()) {
                    continue;
                }
                Path resolved = resolveIncludePath(includePath, baseFile.getParent());
                if (resolved != null) {
                    includePaths.add(resolved);
                }
            }
        }
        return includePaths;
    }

    private static Path resolveIncludePath(String includePath, Path baseDirectory) {
        if (includePath.startsWith("~/")) {
            return Path.of(System.getProperty("user.home")).resolve(includePath.substring(2)).toAbsolutePath().normalize();
        }
        if (includePath.startsWith("/")) {
            return Path.of(includePath).toAbsolutePath().normalize();
        }
        if (includePath.startsWith("./")) {
            return baseDirectory.resolve(includePath.substring(2)).toAbsolutePath().normalize();
        }
        return baseDirectory.resolve(includePath).toAbsolutePath().normalize();
    }

    private static boolean isAllowedIncludePath(Path includePath) {
        String fileName = includePath.getFileName() == null ? "" : includePath.getFileName().toString();
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex < 0) {
            return true;
        }
        String extension = fileName.substring(extensionIndex).toLowerCase(Locale.ROOT);
        return ALLOWED_INCLUDE_EXTENSIONS.contains(extension);
    }

    private static List<Path> ancestorDirectoriesRootToCwd(Path workingDirectory) {
        List<Path> directories = new ArrayList<>();
        Path current = workingDirectory;
        Path root = current.getRoot();
        while (current != null && !current.equals(root)) {
            directories.add(current);
            current = current.getParent();
        }
        Collections.reverse(directories);
        return directories;
    }

    private static Path resolveWorkingDirectory(ConversationSession session) {
        if (session.workingDirectory() != null && !session.workingDirectory().isBlank()) {
            return Path.of(session.workingDirectory()).toAbsolutePath().normalize();
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static String normalizedForDedup(Path path) {
        try {
            return path.toRealPath().toString();
        } catch (IOException ignored) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    private static Path normalizeNullable(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static String descriptionFor(InstructionScope scope) {
        return switch (scope) {
            case MANAGED -> " (managed global instructions for all users)";
            case USER -> " (user's private global instructions for all projects)";
            case PROJECT -> " (project instructions, checked into the codebase)";
            case LOCAL -> " (user's private project instructions, not checked in)";
        };
    }
}
