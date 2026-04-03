package com.openclaude.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ProviderId;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

class WebFetchToolRuntimeTest {
    @Test
    void fetchesReadableHtmlContentFromLocalServer() throws Exception {
        try (TestServer server = new TestServer()) {
            server.handle("/page", exchange -> respond(
                    exchange,
                    200,
                    "text/html",
                    "<html><body><h1>OpenClaude</h1><p>Web fetch integration test.</p><ul><li>Fast</li><li>Reliable</li></ul><a href=\"https://example.com/docs\">Docs</a></body></html>"
            ));

            WebFetchToolRuntime runtime = new WebFetchToolRuntime();
            ToolExecutionResult result = runtime.execute(
                    new ToolExecutionRequest(
                            "tool-web-1",
                            "WebFetch",
                            """
                            {
                              "url": "%s/page",
                              "prompt": "Summarize the page"
                            }
                            """.formatted(server.baseUrl())
                    ),
                    ToolPermissionGateway.allowAll(),
                    update -> {}
            );

            assertFalse(result.error(), result.text());
            assertTrue(result.text().contains("Prompt: Summarize the page"));
            assertTrue(result.text().contains("# OpenClaude"));
            assertTrue(result.text().contains("- Fast"));
            assertTrue(result.text().contains("[Docs](https://example.com/docs)"));
        }
    }

    @Test
    void convertsTablesAndRelativeLinksToMarkdown() throws Exception {
        try (TestServer server = new TestServer()) {
            server.handle("/page", exchange -> respond(
                    exchange,
                    200,
                    "text/html",
                    """
                    <html><body>
                      <table>
                        <tr><th>Name</th><th>Type</th></tr>
                        <tr><td>README.md</td><td>Markdown</td></tr>
                      </table>
                      <p><a href="/docs/getting-started">Getting started</a></p>
                    </body></html>
                    """
            ));

            WebFetchToolRuntime runtime = new WebFetchToolRuntime();
            ToolExecutionResult result = runtime.execute(
                    new ToolExecutionRequest(
                            "tool-web-table-1",
                            "WebFetch",
                            """
                            {
                              "url": "%s/page",
                              "prompt": "Summarize the page"
                            }
                            """.formatted(server.baseUrl())
                    ),
                    ToolPermissionGateway.allowAll(),
                    update -> {}
            );

            assertFalse(result.error(), result.text());
            assertTrue(result.text().contains("| Name | Type |"));
            assertTrue(result.text().contains("| README.md | Markdown |"));
            assertTrue(result.text().contains("[Getting started](" + server.baseUrl() + "/docs/getting-started)"));
        }
    }

    @Test
    void convertsRelativeLinksTablesAndImagesIntoMarkdown() {
        WebFetchToolRuntime runtime = new WebFetchToolRuntime(new StubHttpClient(
                200,
                "text/html",
                """
                <html><body>
                  <h1>OpenClaude</h1>
                  <table>
                    <tr><th>Name</th><th>Type</th></tr>
                    <tr><td>README.md</td><td>Markdown</td></tr>
                  </table>
                  <p><a href="/docs">Docs</a></p>
                  <img src="/logo.png" alt="Logo" />
                </body></html>
                """
        ));

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-web-markdown-1",
                        "WebFetch",
                        """
                        {
                          "url": "https://example.com/page",
                          "prompt": "Summarize the page"
                        }
                        """
                ),
                ToolPermissionGateway.allowAll(),
                update -> {}
        );

        assertFalse(result.error(), result.text());
        assertTrue(result.text().contains("| Name | Type |"));
        assertTrue(result.text().contains("| README.md | Markdown |"));
        assertTrue(result.text().contains("[Docs](https://example.com/docs)"));
        assertTrue(result.text().contains("![Logo](https://example.com/logo.png)"));
    }

    @Test
    void preservesMarkdownResponsesWithoutHtmlConversion() {
        WebFetchToolRuntime runtime = new WebFetchToolRuntime(new StubHttpClient(
                200,
                "text/markdown",
                """
                # OpenClaude

                - Fast
                - Reliable
                """
        ));

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-web-markdown-2",
                        "WebFetch",
                        """
                        {
                          "url": "https://example.com/page.md",
                          "prompt": "Summarize the page"
                        }
                        """
                ),
                ToolPermissionGateway.allowAll(),
                update -> {}
        );

        assertFalse(result.error(), result.text());
        assertTrue(result.text().contains("# OpenClaude"));
        assertTrue(result.text().contains("- Fast"));
        assertTrue(result.text().contains("- Reliable"));
    }

    @Test
    void reportsCrossHostRedirectWithoutFollowingIt() throws Exception {
        try (TestServer server = new TestServer()) {
            server.handle("/redirect", exchange -> {
                exchange.getResponseHeaders().add("Location", "http://localhost:" + server.port() + "/other");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });

            WebFetchToolRuntime runtime = new WebFetchToolRuntime();
            ToolExecutionResult result = runtime.execute(
                    new ToolExecutionRequest(
                            "tool-web-1",
                            "WebFetch",
                            """
                            {
                              "url": "%s/redirect",
                              "prompt": "Follow the redirect"
                            }
                            """.formatted(server.baseUrl())
                    ),
                    ToolPermissionGateway.allowAll(),
                    update -> {}
            );

            assertFalse(result.error(), result.text());
            assertTrue(result.text().contains("REDIRECT DETECTED"));
            assertTrue(result.text().contains("localhost"));
        }
    }

    @Test
    void followsRedirectsThatOnlyAddWww() {
        RoutingHttpClient httpClient = new RoutingHttpClient(Map.of(
                "https://example.com/article", new StubHttpResponseSpec(
                        302,
                        java.net.http.HttpHeaders.of(
                                Map.of(
                                        "content-type", List.of("text/plain"),
                                        "location", List.of("https://www.example.com/article")
                                ),
                                (left, right) -> true
                        ),
                        ""
                ),
                "https://www.example.com/article", new StubHttpResponseSpec(
                        200,
                        "text/html",
                        "<html><body><h1>Redirected</h1><p>Allowed redirect.</p></body></html>"
                )
        ));
        WebFetchToolRuntime runtime = new WebFetchToolRuntime(httpClient);

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-web-redirect-allowed",
                        "WebFetch",
                        """
                        {
                          "url": "https://example.com/article",
                          "prompt": "Summarize the page"
                        }
                        """
                ),
                ToolPermissionGateway.allowAll(),
                update -> {}
        );

        assertFalse(result.error(), result.text());
        assertTrue(result.text().contains("# Redirected"));
        assertTrue(result.text().contains("Allowed redirect."));
    }

    @Test
    void appliesThePromptToFetchedContentWhenAModelInvokerIsAvailable() throws Exception {
        try (TestServer server = new TestServer()) {
            server.handle("/page", exchange -> respond(
                    exchange,
                    200,
                    "text/html",
                    "<html><body><h1>OpenClaude</h1><p>Prompt application test.</p></body></html>"
            ));

            AtomicReference<String> invokedPrompt = new AtomicReference<>("");
            WebFetchToolRuntime runtime = new WebFetchToolRuntime();
            ToolExecutionResult result = runtime.execute(
                    new ToolExecutionRequest(
                            "tool-web-2",
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
                                return "Applied summary from fetched content.";
                            }
                    ),
                    ToolPermissionGateway.allowAll(),
                    update -> {}
            );

            assertFalse(result.error(), result.text());
            assertEquals("Applied summary from fetched content.", result.text());
            assertTrue(invokedPrompt.get().contains("Prompt application test."));
            assertTrue(invokedPrompt.get().contains("Summarize the key point"));
            assertTrue(invokedPrompt.get().contains("Enforce a strict 125-character maximum"));
        }
    }

    @Test
    void bypassesPermissionPromptForPreapprovedHosts() {
        WebFetchToolRuntime runtime = new WebFetchToolRuntime(new StubHttpClient(
                200,
                "text/html",
                "<html><body><h1>Python docs</h1><p>Sequence types documentation.</p></body></html>"
        ));

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-web-3",
                        "WebFetch",
                        """
                        {
                          "url": "https://docs.python.org/3/library/stdtypes-direct.md",
                          "prompt": "Summarize the page"
                        }
                        """
                ),
                request -> {
                    throw new AssertionError("Permission gateway should not be called for preapproved hosts.");
                },
                update -> {}
        );

        assertFalse(result.error(), result.text());
        assertTrue(result.text().contains("Python docs"));
        assertTrue(result.text().contains("Sequence types documentation."));
    }

    @Test
    void usesThePreapprovedDomainPromptBranchWhenApplyingThePrompt() {
        AtomicReference<String> invokedPrompt = new AtomicReference<>("");
        WebFetchToolRuntime runtime = new WebFetchToolRuntime(new StubHttpClient(
                200,
                "text/html",
                "<html><body><h1>Python docs</h1><p>Sequence types documentation.</p></body></html>"
        ));

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-web-4",
                        "WebFetch",
                        """
                        {
                          "url": "https://docs.python.org/3/library/stdtypes-direct.md",
                          "prompt": "Summarize the page"
                        }
                        """,
                        null,
                        prompt -> {
                            invokedPrompt.set(prompt);
                            return "Applied summary from preapproved docs.";
                        }
                ),
                request -> {
                    throw new AssertionError("Permission gateway should not be called for preapproved hosts.");
                },
                update -> {}
        );

        assertFalse(result.error(), result.text());
        assertEquals("Applied summary from preapproved docs.", result.text());
        assertTrue(invokedPrompt.get().contains("Include relevant details, code examples, and documentation excerpts as needed."));
        assertFalse(invokedPrompt.get().contains("Enforce a strict 125-character maximum"));
    }

    @Test
    void usesProviderSpecificSmallFastModelForSecondaryPrompting() {
        AtomicReference<ToolModelRequest> capturedRequest = new AtomicReference<>();
        WebFetchToolRuntime runtime = new WebFetchToolRuntime(new StubHttpClient(
                200,
                "text/html",
                "<html><body><h1>Python docs</h1><p>Sequence types documentation.</p></body></html>"
        ));

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-web-5",
                        "WebFetch",
                        """
                        {
                          "url": "https://docs.python.org/3/library/stdtypes.html",
                          "prompt": "Summarize the page"
                        }
                        """,
                        null,
                        new ToolModelInvoker() {
                            @Override
                            public String invoke(String prompt) {
                                throw new AssertionError("Structured tool invocation should be used for secondary prompting.");
                            }

                            @Override
                            public ToolModelResponse invoke(ToolModelRequest request) {
                                capturedRequest.set(request);
                                return new ToolModelResponse("Applied summary from preapproved docs.", List.of());
                            }

                            @Override
                            public ProviderId providerId() {
                                return ProviderId.OPENAI;
                            }
                        }
                ),
                request -> {
                    throw new AssertionError("Permission gateway should not be called for preapproved hosts.");
                },
                update -> {}
        );

        assertFalse(result.error(), result.text());
        assertEquals("Applied summary from preapproved docs.", result.text());
        assertEquals("gpt-5.4-mini", capturedRequest.get().preferredModelId());
        assertTrue(capturedRequest.get().prompt().contains("Summarize the page"));
    }

    @Test
    void skipsSecondaryPromptingForOpenAiBrowserAuthAndReturnsFetchedContent() {
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);
        WebFetchToolRuntime runtime = new WebFetchToolRuntime(new StubHttpClient(
                200,
                "text/html",
                "<html><body><h1>OpenClaude</h1><p>Browser auth fetch summary.</p></body></html>"
        ));

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-web-openai-browser-auth",
                        "WebFetch",
                        """
                        {
                          "url": "https://example.com/article",
                          "prompt": "Summarize the article"
                        }
                        """,
                        null,
                        new ToolModelInvoker() {
                            @Override
                            public String invoke(String prompt) {
                                invoked.set(true);
                                throw new AssertionError("Browser-auth WebFetch should not perform nested secondary prompting.");
                            }

                            @Override
                            public ToolModelResponse invoke(ToolModelRequest request) {
                                invoked.set(true);
                                throw new AssertionError("Browser-auth WebFetch should not perform nested secondary prompting.");
                            }

                            @Override
                            public ProviderId providerId() {
                                return ProviderId.OPENAI;
                            }

                            @Override
                            public AuthMethod authMethod() {
                                return AuthMethod.BROWSER_SSO;
                            }

                            @Override
                            public String currentModelId() {
                                return "gpt-5.3-codex";
                            }
                        }
                ),
                ToolPermissionGateway.allowAll(),
                update -> {}
        );

        assertFalse(result.error(), result.text());
        assertFalse(Boolean.TRUE.equals(invoked.get()));
        assertTrue(result.text().contains("Prompt: Summarize the article"));
        assertTrue(result.text().contains("# OpenClaude"));
        assertTrue(result.text().contains("Browser auth fetch summary."));
    }

    @Test
    void fallsBackToFetchedContentWhenSecondaryPromptingThrows() {
        WebFetchToolRuntime runtime = new WebFetchToolRuntime(new StubHttpClient(
                200,
                "text/html",
                "<html><body><h1>Guardian article</h1><p>Jonathan Trott discussed mental health.</p></body></html>"
        ));

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-web-secondary-failure",
                        "WebFetch",
                        """
                        {
                          "url": "https://www.theguardian.com/sport/2016/oct/03/jonathan-trott-mental-health-england",
                          "prompt": "Summarize the article"
                        }
                        """,
                        null,
                        new ToolModelInvoker() {
                            @Override
                            public String invoke(String prompt) {
                                throw new AssertionError("Structured tool invocation should be used for secondary prompting.");
                            }

                            @Override
                            public ToolModelResponse invoke(ToolModelRequest request) {
                                throw new IllegalStateException("OpenAI browser-auth streaming request failed: HTTP 400");
                            }

                            @Override
                            public ProviderId providerId() {
                                return ProviderId.OPENAI;
                            }
                        }
                ),
                ToolPermissionGateway.allowAll(),
                update -> {}
        );

        assertFalse(result.error(), result.text());
        assertTrue(result.text().contains("Guardian article"));
        assertTrue(result.text().contains("Jonathan Trott discussed mental health."));
        assertTrue(result.text().contains("Prompt: Summarize the article"));
    }

    @Test
    void appliesSecondaryPromptToPreapprovedMarkdownUsingThePreapprovedBranch() {
        AtomicReference<String> invokedPrompt = new AtomicReference<>("");
        WebFetchToolRuntime runtime = new WebFetchToolRuntime(new StubHttpClient(
                200,
                "text/markdown",
                "# Python docs\n\nSequence types documentation."
        ));

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-web-markdown-1",
                        "WebFetch",
                        """
                        {
                          "url": "https://docs.python.org/3/library/stdtypes.html",
                          "prompt": "Summarize the page"
                        }
                        """,
                        null,
                        prompt -> {
                            invokedPrompt.set(prompt);
                            return "Applied summary from preapproved markdown.";
                        }
                ),
                request -> {
                    throw new AssertionError("Permission gateway should not be called for preapproved hosts.");
                },
                update -> {}
        );

        assertFalse(result.error(), result.text());
        assertEquals("Applied summary from preapproved markdown.", result.text());
        assertTrue(invokedPrompt.get().contains("# Python docs"));
        assertTrue(invokedPrompt.get().contains("Include relevant details, code examples, and documentation excerpts as needed."));
    }

    @Test
    void blocksAnthropicFetchesWhenDomainPreflightRejectsTheHost() {
        RoutingHttpClient httpClient = new RoutingHttpClient(Map.of(
                "https://preflight.local/domain_info?domain=blocked.example", new StubHttpResponseSpec(
                        200,
                        "application/json",
                        "{\"can_fetch\":false}"
                )
        ));
        WebFetchToolRuntime runtime = new WebFetchToolRuntime(httpClient, URI.create("https://preflight.local/domain_info"));

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-web-preflight-blocked",
                        "WebFetch",
                        """
                        {
                          "url": "https://blocked.example/articles/latest",
                          "prompt": "Summarize the page"
                        }
                        """,
                        null,
                        new ToolModelInvoker() {
                            @Override
                            public String invoke(String prompt) {
                                return "";
                            }

                            @Override
                            public ProviderId providerId() {
                                return ProviderId.ANTHROPIC;
                            }
                        }
                ),
                request -> ToolPermissionDecision.allow("approved"),
                update -> {}
        );

        assertTrue(result.error());
        assertEquals("Claude Code is unable to fetch from blocked.example", result.text());
    }

    @Test
    void failsAnthropicFetchesWhenDomainPreflightCannotBeVerified() {
        RoutingHttpClient httpClient = new RoutingHttpClient(Map.of(
                "https://preflight.local/domain_info?domain=unknown.example", new StubHttpResponseSpec(
                        503,
                        "application/json",
                        "{\"error\":\"unavailable\"}"
                )
        ));
        WebFetchToolRuntime runtime = new WebFetchToolRuntime(httpClient, URI.create("https://preflight.local/domain_info"));

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-web-preflight-failed",
                        "WebFetch",
                        """
                        {
                          "url": "https://unknown.example/articles/latest",
                          "prompt": "Summarize the page"
                        }
                        """,
                        null,
                        new ToolModelInvoker() {
                            @Override
                            public String invoke(String prompt) {
                                return "";
                            }

                            @Override
                            public ProviderId providerId() {
                                return ProviderId.ANTHROPIC;
                            }
                        }
                ),
                request -> ToolPermissionDecision.allow("approved"),
                update -> {}
        );

        assertTrue(result.error());
        assertTrue(result.text().contains("Unable to verify if domain unknown.example is safe to fetch."));
    }

    @Test
    void doesNotApplyDomainPreflightOutsideAnthropic() {
        RoutingHttpClient httpClient = new RoutingHttpClient(Map.of(
                "https://preflight.local/domain_info?domain=blocked.example", new StubHttpResponseSpec(
                        200,
                        "application/json",
                        "{\"can_fetch\":false}"
                ),
                "https://blocked.example/articles/latest", new StubHttpResponseSpec(
                        200,
                        "text/html",
                        "<html><body><h1>Allowed for non-Anthropic</h1><p>OpenAI should skip Anthropic preflight.</p></body></html>"
                )
        ));
        WebFetchToolRuntime runtime = new WebFetchToolRuntime(httpClient, URI.create("https://preflight.local/domain_info"));

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-web-preflight-openai",
                        "WebFetch",
                        """
                        {
                          "url": "https://blocked.example/articles/latest",
                          "prompt": "Summarize the page"
                        }
                        """,
                        null,
                        new ToolModelInvoker() {
                            @Override
                            public String invoke(String prompt) {
                                return "";
                            }

                            @Override
                            public ProviderId providerId() {
                                return ProviderId.OPENAI;
                            }
                        }
                ),
                request -> ToolPermissionDecision.allow("approved"),
                update -> {}
        );

        assertFalse(result.error());
        assertTrue(result.text().contains("Allowed for non-Anthropic"));
    }

    @Test
    void cachesAllowedAnthropicDomainPreflightChecksByHost() {
        java.util.concurrent.atomic.AtomicInteger preflightCalls = new java.util.concurrent.atomic.AtomicInteger();
        RoutingHttpClient httpClient = new RoutingHttpClient(Map.of(
                "https://preflight.local/domain_info?domain=cache.example", new StubHttpResponseSpec(
                        200,
                        "application/json",
                        "{\"can_fetch\":true}"
                ),
                "https://cache.example/page-one", new StubHttpResponseSpec(
                        200,
                        "text/html",
                        "<html><body><p>First page.</p></body></html>"
                ),
                "https://cache.example/page-two", new StubHttpResponseSpec(
                        200,
                        "text/html",
                        "<html><body><p>Second page.</p></body></html>"
                )
        )) {
            @Override
            public <T> java.net.http.HttpResponse<T> send(
                    java.net.http.HttpRequest request,
                    java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler
            ) {
                if (request.uri().toString().startsWith("https://preflight.local/domain_info")) {
                    preflightCalls.incrementAndGet();
                }
                return super.send(request, responseBodyHandler);
            }
        };
        WebFetchToolRuntime runtime = new WebFetchToolRuntime(httpClient, URI.create("https://preflight.local/domain_info"));
        ToolModelInvoker anthropicInvoker = new ToolModelInvoker() {
            @Override
            public String invoke(String prompt) {
                return "";
            }

            @Override
            public ProviderId providerId() {
                return ProviderId.ANTHROPIC;
            }
        };

        ToolExecutionResult first = runtime.execute(
                new ToolExecutionRequest(
                        "tool-web-cache-1",
                        "WebFetch",
                        """
                        {
                          "url": "https://cache.example/page-one",
                          "prompt": "Summarize the page"
                        }
                        """,
                        null,
                        anthropicInvoker
                ),
                request -> ToolPermissionDecision.allow("approved"),
                update -> {}
        );
        ToolExecutionResult second = runtime.execute(
                new ToolExecutionRequest(
                        "tool-web-cache-2",
                        "WebFetch",
                        """
                        {
                          "url": "https://cache.example/page-two",
                          "prompt": "Summarize the page"
                        }
                        """,
                        null,
                        anthropicInvoker
                ),
                request -> ToolPermissionDecision.allow("approved"),
                update -> {}
        );

        assertFalse(first.error());
        assertFalse(second.error());
        assertEquals(1, preflightCalls.get());
    }

    @Test
    void preapprovedPathScopedHostsRespectSegmentBoundaries() {
        assertTrue(WebFetchPreapprovedHosts.isPreapprovedHost("github.com", "/anthropics/claude-code"));
        assertTrue(WebFetchPreapprovedHosts.isPreapprovedHost("github.com", "/anthropics"));
        assertFalse(WebFetchPreapprovedHosts.isPreapprovedHost("github.com", "/anthropics-evil/repo"));
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

        private int port() {
            return server.getAddress().getPort();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + port();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class StubHttpClient extends java.net.http.HttpClient {
        private final int statusCode;
        private final java.net.http.HttpHeaders headers;
        private final String body;

        private StubHttpClient(int statusCode, String contentType, String body) {
            this.statusCode = statusCode;
            this.headers = java.net.http.HttpHeaders.of(Map.of("content-type", List.of(contentType)), (left, right) -> true);
            this.body = body;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(1));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public java.net.http.HttpClient.Version version() {
            return java.net.http.HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) {
            return (java.net.http.HttpResponse<T>) new StubHttpResponse(request, statusCode, headers, body);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            return CompletableFuture.completedFuture((java.net.http.HttpResponse<T>) new StubHttpResponse(request, statusCode, headers, body));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler,
                java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return CompletableFuture.completedFuture((java.net.http.HttpResponse<T>) new StubHttpResponse(request, statusCode, headers, body));
        }
    }

    private static class RoutingHttpClient extends java.net.http.HttpClient {
        private final Map<String, StubHttpResponseSpec> responses;

        private RoutingHttpClient(Map<String, StubHttpResponseSpec> responses) {
            this.responses = new LinkedHashMap<>(responses);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(1));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public java.net.http.HttpClient.Version version() {
            return java.net.http.HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) {
            StubHttpResponseSpec spec = responses.get(request.uri().toString());
            if (spec == null) {
                throw new IllegalStateException("Unexpected URI: " + request.uri());
            }
            return (java.net.http.HttpResponse<T>) new StubHttpResponse(request, spec.statusCode(), spec.headers(), spec.body());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            return CompletableFuture.completedFuture((java.net.http.HttpResponse<T>) send(request, responseBodyHandler));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler,
                java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return CompletableFuture.completedFuture((java.net.http.HttpResponse<T>) send(request, responseBodyHandler));
        }
    }

    private record StubHttpResponseSpec(
            int statusCode,
            java.net.http.HttpHeaders headers,
            String body
    ) {
        private StubHttpResponseSpec(int statusCode, String contentType, String body) {
            this(
                    statusCode,
                    java.net.http.HttpHeaders.of(Map.of("content-type", List.of(contentType)), (left, right) -> true),
                    body
            );
        }
    }

    private record StubHttpResponse(
            java.net.http.HttpRequest request,
            int statusCode,
            java.net.http.HttpHeaders headers,
            String body
    ) implements java.net.http.HttpResponse<String> {
        @Override
        public Optional<java.net.http.HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public java.net.http.HttpClient.Version version() {
            return java.net.http.HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }
    }
}
