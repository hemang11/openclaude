package com.openclaude.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openclaude.core.session.ConversationSession;
import com.openclaude.provider.spi.ProviderToolDefinition;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolEndToEndTest {
    private static final Set<String> SHIPPED_TOOL_NAMES = Set.of(
            "bash",
            "Glob",
            "Grep",
            "ExitPlanMode",
            "Read",
            "Edit",
            "Write",
            "WebFetch",
            "TodoWrite",
            "WebSearch",
            "AskUserQuestion",
            "EnterPlanMode"
    );

    @TempDir
    Path tempDir;

    @Test
    void shippedDefaultToolSetMatchesEndToEndSuiteCoverage() {
        Set<String> defaultToolNames = new DefaultToolRuntime().toolDefinitions().stream()
                .map(ProviderToolDefinition::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertEquals(SHIPPED_TOOL_NAMES, defaultToolNames);
    }

    @Test
    void bashToolExecutesReadOnlyDesktopStyleCommandWithoutPermission() throws IOException {
        Files.writeString(tempDir.resolve("alpha.txt"), "alpha", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("beta.txt"), "beta", StandardCharsets.UTF_8);

        ToolRun run = runTool(
                new BashToolRuntime(),
                new ToolExecutionRequest(
                        "tool-bash",
                        "bash",
                        "{\"command\":\"cd " + escapeJson(tempDir) + " 2>/dev/null && find . -type f | wc -l\"}"
                ),
                request -> {
                    throw new AssertionError("Read-only bash commands should not request permission.");
                }
        );

        assertFalse(run.result().error());
        assertTrue(run.result().text().contains("Command: cd " + tempDir + " 2>/dev/null && find . -type f | wc -l"));
        assertPhase(run.updates(), "started");
        assertPhase(run.updates(), "completed");
        assertNoPhase(run.updates(), "permission_requested");
    }

    @Test
    void bashToolExecutesTildeScopedReadOnlyCommandWithoutPermission() {
        ToolRun run = runTool(
                new BashToolRuntime(),
                new ToolExecutionRequest(
                        "tool-bash-home",
                        "bash",
                        "{\"command\":\"cd ~ 2>/dev/null && pwd | wc -c\"}"
                ),
                request -> {
                    throw new AssertionError("Tilde-scoped read-only bash commands should not request permission.");
                }
        );

        assertFalse(run.result().error());
        assertTrue(run.result().text().contains("Command: cd ~ 2>/dev/null && pwd | wc -c"));
        assertPhase(run.updates(), "started");
        assertPhase(run.updates(), "completed");
        assertNoPhase(run.updates(), "permission_requested");
    }

    @Test
    void bashToolExecutesSedBasedDirectoryCountWithoutPermission() throws IOException {
        Files.createDirectories(tempDir.resolve("alpha"));
        Files.createDirectories(tempDir.resolve("beta"));

        ToolRun run = runTool(
                new BashToolRuntime(),
                new ToolExecutionRequest(
                        "tool-bash-sed",
                        "bash",
                        "{\"command\":\"cd " + escapeJson(tempDir) + " && find . -maxdepth 1 -type d | sed '1d' | wc -l\"}"
                ),
                request -> {
                    throw new AssertionError("Read-only sed-based bash commands should not request permission.");
                }
        );

        assertFalse(run.result().error());
        assertTrue(run.result().text().contains("Command: cd " + tempDir + " && find . -maxdepth 1 -type d | sed '1d' | wc -l"));
        assertPhase(run.updates(), "started");
        assertPhase(run.updates(), "completed");
        assertNoPhase(run.updates(), "permission_requested");
    }

    @Test
    void globToolFindsMatchingFilesEndToEnd() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(src.resolve("App.java"), "class App {}", StandardCharsets.UTF_8);
        Files.writeString(src.resolve("AppTest.java"), "class AppTest {}", StandardCharsets.UTF_8);

        ToolRun run = runTool(
                new GlobToolRuntime(),
                new ToolExecutionRequest(
                        "tool-glob",
                        "Glob",
                        "{\"pattern\":\"**/*.java\",\"path\":\"" + escapeJson(tempDir) + "\"}"
                ),
                ToolPermissionGateway.allowAll()
        );

        assertFalse(run.result().error());
        assertTrue(run.result().text().contains("App.java"));
        assertTrue(run.result().text().contains("AppTest.java"));
        assertPhase(run.updates(), "started");
        assertPhase(run.updates(), "completed");
    }

    @Test
    void grepToolSearchesContentEndToEnd() throws IOException {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "alpha\nhello beta\n", StandardCharsets.UTF_8);

        ToolRun run = runTool(
                new GrepToolRuntime(),
                new ToolExecutionRequest(
                        "tool-grep",
                        "Grep",
                        "{\"pattern\":\"hello\",\"path\":\"" + escapeJson(tempDir) + "\",\"output_mode\":\"content\"}"
                ),
                ToolPermissionGateway.allowAll()
        );

        assertFalse(run.result().error());
        assertTrue(run.result().text().contains("sample.txt:2:hello beta"));
        assertPhase(run.updates(), "started");
        assertPhase(run.updates(), "completed");
    }

    @Test
    void readToolReadsFileAndRecordsReadStateEndToEnd() throws IOException {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "alpha\nbeta\n", StandardCharsets.UTF_8);
        ConversationSession session = session();

        ToolRun run = runTool(
                new FileReadToolRuntime(),
                new ToolExecutionRequest(
                        "tool-read",
                        "Read",
                        "{\"file_path\":\"" + escapeJson(file) + "\"}",
                        session
                ),
                ToolPermissionGateway.allowAll()
        );

        assertFalse(run.result().error());
        assertEquals("1\talpha\n2\tbeta", run.result().text());
        ConversationSession updatedSession = run.result().sessionEffect().apply(session);
        assertTrue(updatedSession.readFileState().containsKey(file.toAbsolutePath().normalize().toString()));
        assertPhase(run.updates(), "started");
        assertPhase(run.updates(), "completed");
    }

    @Test
    void writeToolWritesAfterReadAndApprovalEndToEnd() throws IOException {
        Path file = tempDir.resolve("write.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        ConversationSession session = readFullFile(file);

        ToolRun run = runTool(
                new FileWriteToolRuntime(),
                new ToolExecutionRequest(
                        "tool-write",
                        "Write",
                        "{\"file_path\":\"" + escapeJson(file) + "\",\"content\":\"updated\"}",
                        session
                ),
                request -> ToolPermissionDecision.allow("approved")
        );

        assertFalse(run.result().error());
        assertEquals("updated", Files.readString(file, StandardCharsets.UTF_8));
        assertPhase(run.updates(), "permission_requested");
        assertEquals("file_write", findPhase(run.updates(), "permission_requested").interactionType());
        assertPhase(run.updates(), "completed");
    }

    @Test
    void editToolEditsAfterReadAndApprovalEndToEnd() throws IOException {
        Path file = tempDir.resolve("edit.txt");
        Files.writeString(file, "alpha\nbeta\n", StandardCharsets.UTF_8);
        ConversationSession session = readFullFile(file);

        ToolRun run = runTool(
                new FileEditToolRuntime(),
                new ToolExecutionRequest(
                        "tool-edit",
                        "Edit",
                        "{\"file_path\":\"" + escapeJson(file) + "\",\"old_string\":\"beta\",\"new_string\":\"gamma\"}",
                        session
                ),
                request -> ToolPermissionDecision.allow("approved")
        );

        assertFalse(run.result().error());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("gamma"));
        assertPhase(run.updates(), "permission_requested");
        assertEquals("file_edit", findPhase(run.updates(), "permission_requested").interactionType());
        assertPhase(run.updates(), "completed");
    }

    @Test
    void todoWriteToolUpdatesSessionTodosEndToEnd() {
        ConversationSession session = session();
        ToolRun run = runTool(
                new TodoWriteToolRuntime(),
                new ToolExecutionRequest(
                        "tool-todo",
                        "TodoWrite",
                        "{\"todos\":[{\"content\":\"Run tests\",\"status\":\"in_progress\",\"activeForm\":\"Running tests\"}]}",
                        session
                ),
                ToolPermissionGateway.allowAll()
        );

        assertFalse(run.result().error());
        ConversationSession updatedSession = run.result().sessionEffect().apply(session);
        assertEquals(1, updatedSession.todos().size());
        assertEquals("Run tests", updatedSession.todos().getFirst().content());
        assertPhase(run.updates(), "completed");
    }

    @Test
    void enterPlanModeToolSetsPlanModeEndToEnd() {
        ConversationSession session = session();
        ToolRun run = runTool(
                new EnterPlanModeToolRuntime(),
                new ToolExecutionRequest("tool-enter-plan", "EnterPlanMode", "{}", session),
                ToolPermissionGateway.allowAll()
        );

        assertFalse(run.result().error());
        ConversationSession updatedSession = run.result().sessionEffect().apply(session);
        assertTrue(updatedSession.planMode());
        assertPhase(run.updates(), "completed");
    }

    @Test
    void exitPlanModeToolRequestsApprovalAndClearsPlanModeEndToEnd() {
        ConversationSession session = session().withPlanMode(true);
        ToolRun run = runTool(
                new ExitPlanModeToolRuntime(),
                new ToolExecutionRequest("tool-exit-plan", "ExitPlanMode", "{}", session),
                request -> ToolPermissionDecision.allow("approved")
        );

        assertFalse(run.result().error());
        ConversationSession updatedSession = run.result().sessionEffect().apply(session);
        assertFalse(updatedSession.planMode());
        assertPhase(run.updates(), "permission_requested");
        assertEquals("exit_plan_mode", findPhase(run.updates(), "permission_requested").interactionType());
        assertPhase(run.updates(), "completed");
    }

    @Test
    void askUserQuestionToolRoundTripsStructuredAnswerEndToEnd() {
        ToolRun run = runTool(
                new AskUserQuestionToolRuntime(),
                new ToolExecutionRequest(
                        "tool-question",
                        "AskUserQuestion",
                        """
                        {
                          "questions": [
                            {
                              "question": "Which implementation path should OpenClaude take?",
                              "header": "Approach",
                              "options": [
                                { "label": "Safer refactor", "description": "Land the change in smaller steps." },
                                { "label": "Direct rewrite", "description": "Rewrite the flow in one pass." }
                              ]
                            }
                          ]
                        }
                        """
                ),
                request -> ToolPermissionDecision.allow(
                        "answered in test",
                        """
                        {
                          "answers": {
                            "Which implementation path should OpenClaude take?": "Safer refactor"
                          }
                        }
                        """
                )
        );

        assertFalse(run.result().error());
        assertTrue(run.result().text().contains("Safer refactor"));
        ToolExecutionUpdate permissionUpdate = findPhase(run.updates(), "permission_requested");
        assertNotNull(permissionUpdate);
        assertEquals("ask_user_question", permissionUpdate.interactionType());
        assertPhase(run.updates(), "completed");
    }

    @Test
    void webFetchToolFetchesAndAppliesPromptEndToEnd() throws Exception {
        try (TestServer server = new TestServer()) {
            server.handle("/page", exchange -> respond(
                    exchange,
                    200,
                    "text/html",
                    "<html><body><h1>OpenClaude</h1><p>Fetched integration content.</p></body></html>"
            ));

            AtomicReference<String> invokedPrompt = new AtomicReference<>("");
            ToolRun run = runTool(
                    new WebFetchToolRuntime(server.httpClient()),
                    new ToolExecutionRequest(
                            "tool-web-fetch",
                            "WebFetch",
                            """
                            {
                              "url": "%s/page",
                              "prompt": "Summarize the key point"
                            }
                            """.formatted(server.baseUrl()),
                            null,
                            prompt -> {
                                invokedPrompt.set(prompt);
                                return "Applied web fetch summary.";
                            }
                    ),
                    request -> ToolPermissionDecision.allow("approved")
            );

            assertFalse(run.result().error());
            assertEquals("Applied web fetch summary.", run.result().text());
            assertTrue(invokedPrompt.get().contains("Fetched integration content."));
            assertPhase(run.updates(), "permission_requested");
            assertEquals("web_fetch", findPhase(run.updates(), "permission_requested").interactionType());
            assertPhase(run.updates(), "completed");
        }
    }

    @Test
    void webSearchToolSearchesLocalEndpointEndToEnd() throws Exception {
        try (TestServer server = new TestServer()) {
            server.handle("/search", exchange -> respond(
                    exchange,
                    200,
                    "text/html",
                    """
                    <html><body>
                      <a class="result__a" href="https://docs.openai.com/guides/responses">OpenAI Responses Guide</a>
                      <a class="result__a" href="https://example.com/other">Other Result</a>
                    </body></html>
                    """
            ));

            ToolRun run = runTool(
                    new WebSearchToolRuntime(server.httpClient(), URI.create(server.baseUrl() + "/search")),
                    new ToolExecutionRequest(
                            "tool-web-search",
                            "WebSearch",
                            """
                            {
                              "query": "latest openai responses docs"
                            }
                            """
                    ),
                    request -> ToolPermissionDecision.allow("approved")
            );

            assertFalse(run.result().error());
            assertTrue(run.result().text().contains("OpenAI Responses Guide"));
            assertTrue(run.result().text().contains("REMINDER: You MUST include the sources above"));
            assertPhase(run.updates(), "permission_requested");
            assertEquals("web_search", findPhase(run.updates(), "permission_requested").interactionType());
            assertPhase(run.updates(), "completed");
        }
    }

    private ToolRun runTool(
            ToolRuntime runtime,
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway
    ) {
        List<ToolExecutionUpdate> updates = new ArrayList<>();
        ToolExecutionResult result = runtime.execute(request, permissionGateway, updates::add);
        return new ToolRun(result, updates);
    }

    private ConversationSession session() {
        return ConversationSession.create("session", tempDir.toString(), tempDir.toString());
    }

    private ConversationSession readFullFile(Path file) {
        ConversationSession session = session();
        ToolExecutionResult readResult = new FileReadToolRuntime().execute(new ToolExecutionRequest(
                "read-1",
                "Read",
                "{\"file_path\":\"" + escapeJson(file) + "\"}",
                session
        ));
        return readResult.sessionEffect().apply(session);
    }

    private static ToolExecutionUpdate findPhase(List<ToolExecutionUpdate> updates, String phase) {
        return updates.stream()
                .filter(update -> phase.equals(update.phase()))
                .findFirst()
                .orElse(null);
    }

    private static void assertPhase(List<ToolExecutionUpdate> updates, String phase) {
        assertTrue(updates.stream().anyMatch(update -> phase.equals(update.phase())), "Missing phase: " + phase);
    }

    private static void assertNoPhase(List<ToolExecutionUpdate> updates, String phase) {
        assertTrue(updates.stream().noneMatch(update -> phase.equals(update.phase())), "Unexpected phase: " + phase);
    }

    private static String escapeJson(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private record ToolRun(
            ToolExecutionResult result,
            List<ToolExecutionUpdate> updates
    ) {
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;

        private TestServer() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.start();
        }

        private void handle(String path, com.sun.net.httpserver.HttpHandler handler) {
            server.createContext(path, handler);
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private java.net.http.HttpClient httpClient() {
            return java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
