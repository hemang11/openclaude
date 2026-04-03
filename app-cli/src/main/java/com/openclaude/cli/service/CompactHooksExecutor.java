package com.openclaude.cli.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.SessionAttachment;
import com.openclaude.core.session.SessionMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class CompactHooksExecutor {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    private final CompactHookConfigLoader configLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    CompactHooksExecutor() {
        this(new CompactHookConfigLoader());
    }

    CompactHooksExecutor(CompactHookConfigLoader configLoader) {
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
    }

    PreCompactHookResult executePreCompactHooks(
            ConversationSession session,
            Path transcriptPath,
            String trigger,
            String customInstructions
    ) {
        List<HookExecutionResult> results = executeMatchingHooks(
                session,
                transcriptPath,
                CompactHookConfigLoader.CompactHookEvent.PRE_COMPACT,
                trigger,
                linkedFields(
                        "hook_event_name", "PreCompact",
                        "trigger", trigger,
                        "custom_instructions", customInstructions
                )
        );

        List<String> successfulOutputs = results.stream()
                .filter(HookExecutionResult::succeeded)
                .map(HookExecutionResult::output)
                .map(String::trim)
                .filter(output -> !output.isBlank())
                .toList();

        return new PreCompactHookResult(
                successfulOutputs.isEmpty() ? null : String.join(System.lineSeparator() + System.lineSeparator(), successfulOutputs),
                formatDisplayMessages("PreCompact", results)
        );
    }

    PostCompactHookResult executePostCompactHooks(
            ConversationSession session,
            Path transcriptPath,
            String trigger,
            String compactSummary
    ) {
        List<HookExecutionResult> results = executeMatchingHooks(
                session,
                transcriptPath,
                CompactHookConfigLoader.CompactHookEvent.POST_COMPACT,
                trigger,
                linkedFields(
                        "hook_event_name", "PostCompact",
                        "trigger", trigger,
                        "compact_summary", compactSummary
                )
        );

        return new PostCompactHookResult(formatDisplayMessages("PostCompact", results));
    }

    SessionStartHookResult processSessionStartHooks(
            ConversationSession session,
            Path transcriptPath,
            String modelId
    ) {
        List<HookExecutionResult> results = executeMatchingHooks(
                session,
                transcriptPath,
                CompactHookConfigLoader.CompactHookEvent.SESSION_START,
                "compact",
                linkedFields(
                        "hook_event_name", "SessionStart",
                        "source", "compact",
                        "model", modelId
                )
        );

        List<SessionMessage> hookMessages = new ArrayList<>();
        for (HookExecutionResult result : results) {
            if (!result.succeeded()) {
                continue;
            }
            String output = result.output().trim();
            if (output.isBlank()) {
                continue;
            }
            hookMessages.add(SessionMessage.attachment(
                    new SessionAttachment.HookAdditionalContextAttachment(
                            "SessionStart",
                            result.command(),
                            output
                    )
            ));
        }
        return new SessionStartHookResult(List.copyOf(hookMessages));
    }

    private List<HookExecutionResult> executeMatchingHooks(
            ConversationSession session,
            Path transcriptPath,
            CompactHookConfigLoader.CompactHookEvent event,
            String matchQuery,
            Map<String, Object> eventFields
    ) {
        List<CompactHookConfigLoader.CompactHookCommand> commands = configLoader.loadCommands(session, event, matchQuery);
        if (commands.isEmpty()) {
            return List.of();
        }

        Map<String, Object> hookInput = new LinkedHashMap<>();
        hookInput.put("session_id", session.sessionId());
        hookInput.put("transcript_path", transcriptPath == null ? "" : transcriptPath.toAbsolutePath().normalize().toString());
        hookInput.put("cwd", resolveWorkingDirectory(session).toAbsolutePath().normalize().toString());
        hookInput.putAll(eventFields);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<HookExecutionResult>> futures = commands.stream()
                    .map(command -> CompletableFuture.supplyAsync(
                            () -> executeCommand(command, resolveWorkingDirectory(session), hookInput),
                            executor
                    ))
                    .toList();
            List<HookExecutionResult> results = new ArrayList<>(futures.size());
            for (CompletableFuture<HookExecutionResult> future : futures) {
                results.add(future.join());
            }
            return List.copyOf(results);
        }
    }

    private HookExecutionResult executeCommand(
            CompactHookConfigLoader.CompactHookCommand hook,
            Path workingDirectory,
            Map<String, Object> hookInput
    ) {
        String command = hook.command().trim();
        if (command.isBlank()) {
            return new HookExecutionResult(command, false, "Missing hook command.");
        }

        String shell = hook.shell().trim();
        if (!shell.isBlank() && !"bash".equals(shell)) {
            return new HookExecutionResult(command, false, "Unsupported compact hook shell: " + shell);
        }

        Process process;
        try {
            process = new ProcessBuilder("/bin/zsh", "-lc", command)
                    .directory(workingDirectory.toFile())
                    .start();
        } catch (IOException exception) {
            return new HookExecutionResult(command, false, exception.getMessage());
        }

        String jsonInput;
        try {
            jsonInput = objectMapper.writeValueAsString(hookInput);
        } catch (IOException exception) {
            process.destroyForcibly();
            return new HookExecutionResult(command, false, exception.getMessage());
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (var stdin = process.getOutputStream()) {
                stdin.write(jsonInput.getBytes(StandardCharsets.UTF_8));
            }

            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getInputStream()),
                    executor
            );
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getErrorStream()),
                    executor
            );

            Duration timeout = hook.timeoutSeconds() == null
                    ? DEFAULT_TIMEOUT
                    : Duration.ofSeconds(Math.max(1, hook.timeoutSeconds()));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new HookExecutionResult(command, false, "Hook timed out after " + timeout.toSeconds() + "s");
            }

            String stdout = stdoutFuture.join().trim();
            String stderr = stderrFuture.join().trim();
            if (process.exitValue() == 0) {
                return new HookExecutionResult(command, true, stdout);
            }
            String failureOutput = !stderr.isBlank() ? stderr : stdout;
            return new HookExecutionResult(command, false, failureOutput);
        } catch (IOException exception) {
            process.destroyForcibly();
            return new HookExecutionResult(command, false, exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new HookExecutionResult(command, false, "Hook interrupted");
        }
    }

    private static String formatDisplayMessages(String hookName, List<HookExecutionResult> results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        List<String> displayMessages = new ArrayList<>();
        for (HookExecutionResult result : results) {
            String output = result.output().trim();
            if (result.succeeded()) {
                if (!output.isBlank()) {
                    displayMessages.add(hookName + " [" + result.command() + "] completed successfully: " + output);
                } else {
                    displayMessages.add(hookName + " [" + result.command() + "] completed successfully");
                }
            } else if (!output.isBlank()) {
                displayMessages.add(hookName + " [" + result.command() + "] failed: " + output);
            } else {
                displayMessages.add(hookName + " [" + result.command() + "] failed");
            }
        }
        return displayMessages.isEmpty() ? null : String.join(System.lineSeparator(), displayMessages);
    }

    private static Path resolveWorkingDirectory(ConversationSession session) {
        if (session.workingDirectory() != null && !session.workingDirectory().isBlank()) {
            return Path.of(session.workingDirectory());
        }
        if (session.workspaceRoot() != null && !session.workspaceRoot().isBlank()) {
            return Path.of(session.workspaceRoot());
        }
        return Path.of(System.getProperty("user.dir"));
    }

    private static String readStream(java.io.InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
        }
    }

    private static Map<String, Object> linkedFields(Object... entries) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            Object key = entries[index];
            if (!(key instanceof String stringKey) || stringKey.isBlank()) {
                continue;
            }
            fields.put(stringKey, entries[index + 1]);
        }
        return fields;
    }

    record HookExecutionResult(
            String command,
            boolean succeeded,
            String output
    ) {
        HookExecutionResult {
            command = command == null ? "" : command;
            output = output == null ? "" : output;
        }
    }

    record PreCompactHookResult(
            String newCustomInstructions,
            String userDisplayMessage
    ) {
    }

    record PostCompactHookResult(
            String userDisplayMessage
    ) {
    }

    record SessionStartHookResult(
            List<SessionMessage> hookMessages
    ) {
        SessionStartHookResult {
            hookMessages = hookMessages == null ? List.of() : List.copyOf(hookMessages);
        }
    }
}
