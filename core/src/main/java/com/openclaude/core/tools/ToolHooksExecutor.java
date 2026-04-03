package com.openclaude.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
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

public final class ToolHooksExecutor {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    private final ToolHookConfigLoader configLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolHooksExecutor() {
        this(new ToolHookConfigLoader());
    }

    public ToolHooksExecutor(ToolHookConfigLoader configLoader) {
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
    }

    public List<SessionMessage> executePreToolHooks(
            ConversationSession session,
            Path transcriptPath,
            ToolExecutionRequest request
    ) {
        Map<String, Object> hookInput = buildBaseHookInput(session, transcriptPath, request);
        hookInput.put("hook_event_name", ToolHookConfigLoader.ToolHookEvent.PRE_TOOL_USE.configKey());
        return executeMatchingHooks(
                session,
                request,
                ToolHookConfigLoader.ToolHookEvent.PRE_TOOL_USE,
                hookInput
        );
    }

    public List<SessionMessage> executePostToolHooks(
            ConversationSession session,
            Path transcriptPath,
            ToolExecutionRequest request,
            String toolResponse
    ) {
        Map<String, Object> hookInput = buildBaseHookInput(session, transcriptPath, request);
        hookInput.put("hook_event_name", ToolHookConfigLoader.ToolHookEvent.POST_TOOL_USE.configKey());
        hookInput.put("tool_response", toolResponse == null ? "" : toolResponse);
        return executeMatchingHooks(
                session,
                request,
                ToolHookConfigLoader.ToolHookEvent.POST_TOOL_USE,
                hookInput
        );
    }

    public List<SessionMessage> executePostToolUseFailureHooks(
            ConversationSession session,
            Path transcriptPath,
            ToolExecutionRequest request,
            String error
    ) {
        Map<String, Object> hookInput = buildBaseHookInput(session, transcriptPath, request);
        hookInput.put("hook_event_name", ToolHookConfigLoader.ToolHookEvent.POST_TOOL_USE_FAILURE.configKey());
        hookInput.put("error", error == null ? "" : error);
        return executeMatchingHooks(
                session,
                request,
                ToolHookConfigLoader.ToolHookEvent.POST_TOOL_USE_FAILURE,
                hookInput
        );
    }

    private List<SessionMessage> executeMatchingHooks(
            ConversationSession session,
            ToolExecutionRequest request,
            ToolHookConfigLoader.ToolHookEvent event,
            Map<String, Object> hookInput
    ) {
        List<ToolHookConfigLoader.ToolHookCommand> commands = configLoader.loadCommands(session, event, request.toolName());
        if (commands.isEmpty()) {
            return List.of();
        }

        Path workingDirectory = resolveWorkingDirectory(session);
        ArrayList<SessionMessage> messages = new ArrayList<>();
        for (ToolHookConfigLoader.ToolHookCommand hook : commands) {
            String hookName = hookDisplayName(event, request.toolName());
            String command = hook.command().trim();
            messages.add(SessionMessage.progress(
                    hookName + " [" + command + "] started",
                    "hook_started",
                    request.toolUseId(),
                    event.configKey(),
                    hookName,
                    command,
                    false
            ));

            HookExecutionResult result = executeCommand(hook, workingDirectory, hookInput);
            String output = result.output().trim();
            if (result.succeeded() && !output.isBlank()) {
                messages.add(SessionMessage.attachment(new SessionAttachment.HookAdditionalContextAttachment(
                        event.configKey(),
                        hookName,
                        command,
                        output,
                        request.toolUseId()
                )));
            }
            messages.add(SessionMessage.progress(
                    hookResponseText(hookName, command, result),
                    "hook_response",
                    request.toolUseId(),
                    event.configKey(),
                    hookName,
                    command,
                    !result.succeeded()
            ));
        }
        return List.copyOf(messages);
    }

    private Map<String, Object> buildBaseHookInput(
            ConversationSession session,
            Path transcriptPath,
            ToolExecutionRequest request
    ) {
        LinkedHashMap<String, Object> hookInput = new LinkedHashMap<>();
        hookInput.put("session_id", session == null ? "" : session.sessionId());
        hookInput.put("transcript_path", transcriptPath == null ? "" : transcriptPath.toAbsolutePath().normalize().toString());
        hookInput.put("cwd", resolveWorkingDirectory(session).toAbsolutePath().normalize().toString());
        hookInput.put("tool_name", request.toolName());
        hookInput.put("tool_input", parseJsonObject(request.inputJson()));
        hookInput.put("tool_use_id", request.toolUseId());
        return hookInput;
    }

    private HookExecutionResult executeCommand(
            ToolHookConfigLoader.ToolHookCommand hook,
            Path workingDirectory,
            Map<String, Object> hookInput
    ) {
        String command = hook.command().trim();
        if (command.isBlank()) {
            return new HookExecutionResult(command, false, "Missing hook command.");
        }

        String shell = hook.shell().trim();
        if (!shell.isBlank() && !"bash".equals(shell)) {
            return new HookExecutionResult(command, false, "Unsupported tool hook shell: " + shell);
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

    private JsonNode parseJsonObject(String inputJson) {
        try {
            return objectMapper.readTree(inputJson == null || inputJson.isBlank() ? "{}" : inputJson);
        } catch (IOException exception) {
            return objectMapper.getNodeFactory().textNode(inputJson == null ? "" : inputJson);
        }
    }

    private static Path resolveWorkingDirectory(ConversationSession session) {
        if (session != null && session.workingDirectory() != null && !session.workingDirectory().isBlank()) {
            return Path.of(session.workingDirectory());
        }
        if (session != null && session.workspaceRoot() != null && !session.workspaceRoot().isBlank()) {
            return Path.of(session.workspaceRoot());
        }
        return Path.of(System.getProperty("user.dir"));
    }

    private static String hookDisplayName(ToolHookConfigLoader.ToolHookEvent event, String toolName) {
        String normalizedToolName = toolName == null || toolName.isBlank() ? "tool" : toolName;
        return event.configKey() + ":" + normalizedToolName;
    }

    private static String hookResponseText(
            String hookName,
            String command,
            HookExecutionResult result
    ) {
        String output = result.output().trim();
        if (result.succeeded()) {
            if (!output.isBlank()) {
                return hookName + " [" + command + "] completed successfully: " + output;
            }
            return hookName + " [" + command + "] completed successfully";
        }
        if (!output.isBlank()) {
            return hookName + " [" + command + "] failed: " + output;
        }
        return hookName + " [" + command + "] failed";
    }

    private static String readStream(java.io.InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
        }
    }

    private record HookExecutionResult(
            String command,
            boolean succeeded,
            String output
    ) {
        private HookExecutionResult {
            command = command == null ? "" : command;
            output = output == null ? "" : output;
        }
    }
}
