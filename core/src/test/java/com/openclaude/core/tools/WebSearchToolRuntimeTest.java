package com.openclaude.core.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderToolDefinition;
import com.openclaude.provider.spi.WebSearchResultContentBlock;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WebSearchToolRuntimeTest {
    @Test
    void formatsSearchResultsUsingClaudesLinkBlockContract() throws Exception {
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

            WebSearchToolRuntime runtime = new WebSearchToolRuntime(server.httpClient(), URI.create(server.baseUrl() + "/search"));
            ToolExecutionResult result = runtime.execute(
                    new ToolExecutionRequest(
                            "tool-search-1",
                            "WebSearch",
                            """
                            {
                              "query": "latest openai responses docs"
                            }
                            """
                    ),
                    ToolPermissionGateway.allowAll(),
                    update -> {}
            );

            assertFalse(result.error());
            assertTrue(result.text().contains("Web search results for query: \"latest openai responses docs\""));
            assertTrue(result.text().contains("Links: [{\"title\":\"OpenAI Responses Guide\",\"url\":\"https://docs.openai.com/guides/responses\"},{\"title\":\"Other Result\",\"url\":\"https://example.com/other\"}]"));
            assertTrue(result.text().contains("REMINDER: You MUST include the sources above"));
        }
    }

    @Test
    void filtersSearchResultsToAllowedDomains() throws Exception {
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

            WebSearchToolRuntime runtime = new WebSearchToolRuntime(server.httpClient(), URI.create(server.baseUrl() + "/search"));
            ToolExecutionResult result = runtime.execute(
                    new ToolExecutionRequest(
                            "tool-search-1",
                            "WebSearch",
                            """
                            {
                              "query": "latest openai responses docs",
                              "allowed_domains": ["docs.openai.com"]
                            }
                            """
                    ),
                    ToolPermissionGateway.allowAll(),
                    update -> {}
            );

            assertFalse(result.error());
            assertTrue(result.text().contains("Links: [{\"title\":\"OpenAI Responses Guide\",\"url\":\"https://docs.openai.com/guides/responses\"}]"));
            assertFalse(result.text().contains("https://example.com/other"));
        }
    }

    @Test
    void advertisesClaudesMandatorySourcesContractInTheToolDescription() {
        WebSearchToolRuntime runtime = new WebSearchToolRuntime();
        ProviderToolDefinition definition = runtime.toolDefinitions().getFirst();

        assertTrue(definition.description().contains("After answering the user's question, you MUST include a \"Sources:\" section"));
        assertTrue(definition.description().contains("The current month is "));
        assertTrue(definition.description().contains("search for \"React documentation\" with the current year"));
    }

    @Test
    void rejectsConflictingDomainFilters() {
        WebSearchToolRuntime runtime = new WebSearchToolRuntime();
        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-search-1",
                        "WebSearch",
                        """
                        {
                          "query": "latest openai responses docs",
                          "allowed_domains": ["docs.openai.com"],
                          "blocked_domains": ["example.com"]
                        }
                        """
                ),
                ToolPermissionGateway.allowAll(),
                update -> {}
        );

        assertTrue(result.error());
        assertTrue(result.text().contains("cannot combine allowed_domains and blocked_domains"));
    }

    @Test
    void usesAnthropicNativeWebSearchToolWhenModelInvokerIsAvailable() {
        AtomicReference<ToolModelRequest> capturedRequest = new AtomicReference<>();
        WebSearchToolRuntime runtime = new WebSearchToolRuntime();

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-search-native-1",
                        "WebSearch",
                        """
                        {
                          "query": "latest openai responses docs",
                          "allowed_domains": ["docs.openai.com"]
                        }
                        """,
                        null,
                        new ToolModelInvoker() {
                            @Override
                            public String invoke(String prompt) {
                                throw new AssertionError("Structured tool invocation should be used for native web search.");
                            }

                            @Override
                            public ToolModelResponse invoke(ToolModelRequest request) {
                                capturedRequest.set(request);
                                return new ToolModelResponse(
                                        "",
                                        List.of(new WebSearchResultContentBlock(
                                                "search-1",
                                                List.of(
                                                        new WebSearchResultContentBlock.SearchHit(
                                                                "OpenAI Responses Guide",
                                                                "https://docs.openai.com/guides/responses"
                                                        )
                                                )
                                        ))
                                );
                            }

                            @Override
                            public ProviderId providerId() {
                                return ProviderId.ANTHROPIC;
                            }

                            @Override
                            public String currentModelId() {
                                return "anthropic/sonnet";
                            }
                        }
                ),
                ToolPermissionGateway.allowAll(),
                update -> {}
        );

        assertFalse(result.error());
        assertTrue(result.text().contains("https://docs.openai.com/guides/responses"));
        assertEquals("You are an assistant for performing a web search tool use.", capturedRequest.get().systemPrompt());
        assertEquals("web_search_20250305", capturedRequest.get().tools().getFirst().providerType());
        assertTrue(capturedRequest.get().tools().getFirst().providerConfigJson().contains("\"allowed_domains\": [\"docs.openai.com\"]"));
    }

    @Test
    void usesOpenAiNativeWebSearchToolWhenBlockedDomainsAreAbsent() {
        AtomicReference<ToolModelRequest> capturedRequest = new AtomicReference<>();
        WebSearchToolRuntime runtime = new WebSearchToolRuntime();
        List<ToolExecutionUpdate> updates = new java.util.ArrayList<>();

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-search-native-2",
                        "WebSearch",
                        """
                        {
                          "query": "latest openai responses docs",
                          "allowed_domains": ["docs.openai.com"]
                        }
                        """,
                        null,
                        new ToolModelInvoker() {
                            @Override
                            public String invoke(String prompt) {
                                throw new AssertionError("Structured tool invocation should be used for native web search.");
                            }

                            @Override
                            public ToolModelResponse invoke(ToolModelRequest request, java.util.function.Consumer<ToolModelProgress> progressConsumer) {
                                capturedRequest.set(request);
                                progressConsumer.accept(new ToolModelProgress("search-1", "query_update", "latest openai responses docs", 0));
                                progressConsumer.accept(new ToolModelProgress("search-1", "search_results_received", "", 1));
                                return new ToolModelResponse(
                                        "",
                                        List.of(new WebSearchResultContentBlock(
                                                "",
                                                List.of(
                                                        new WebSearchResultContentBlock.SearchHit(
                                                                "OpenAI Responses Guide",
                                                                "https://docs.openai.com/guides/responses"
                                                        )
                                                )
                                        ))
                                );
                            }

                            @Override
                            public ProviderId providerId() {
                                return ProviderId.OPENAI;
                            }

                            @Override
                            public String currentModelId() {
                                return "gpt-5.4";
                            }
                        }
                ),
                ToolPermissionGateway.allowAll(),
                updates::add
        );

        assertFalse(result.error());
        assertTrue(result.text().contains("https://docs.openai.com/guides/responses"));
        assertEquals("web_search", capturedRequest.get().tools().getFirst().providerType());
        assertTrue(capturedRequest.get().tools().getFirst().providerConfigJson().contains("\"allowed_domains\": [\"docs.openai.com\"]"));
        assertEquals("gpt-5.4-mini", capturedRequest.get().preferredModelId());
        assertTrue(capturedRequest.get().stream());
        assertTrue(
                updates.stream().anyMatch(update -> update.text().contains("Searching: latest openai responses docs")),
                updates.toString()
        );
        assertTrue(
                updates.stream().anyMatch(update -> update.text().contains("Found 1 result for \"latest openai responses docs\"")),
                updates.toString()
        );
    }

    @Test
    void usesOpenAiNativeWebSearchToolEvenWhenBlockedDomainsArePresent() {
        AtomicReference<ToolModelRequest> capturedRequest = new AtomicReference<>();
        WebSearchToolRuntime runtime = new WebSearchToolRuntime();

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-search-native-3",
                        "WebSearch",
                        """
                        {
                          "query": "latest openai responses docs",
                          "blocked_domains": ["example.com"]
                        }
                        """,
                        null,
                        new ToolModelInvoker() {
                            @Override
                            public String invoke(String prompt) {
                                throw new AssertionError("Structured tool invocation should be used for native web search.");
                            }

                            @Override
                            public ToolModelResponse invoke(ToolModelRequest request) {
                                capturedRequest.set(request);
                                return new ToolModelResponse(
                                        "",
                                        List.of(new WebSearchResultContentBlock(
                                                "",
                                                List.of(
                                                        new WebSearchResultContentBlock.SearchHit(
                                                                "Blocked Result",
                                                                "https://example.com/other"
                                                        ),
                                                        new WebSearchResultContentBlock.SearchHit(
                                                                "OpenAI Responses Guide",
                                                                "https://docs.openai.com/guides/responses"
                                                        )
                                                )
                                        ))
                                );
                            }

                            @Override
                            public ProviderId providerId() {
                                return ProviderId.OPENAI;
                            }

                            @Override
                            public String currentModelId() {
                                return "gpt-5.4";
                            }
                        }
                ),
                ToolPermissionGateway.allowAll(),
                update -> {}
        );

        assertFalse(result.error());
        assertTrue(result.text().contains("https://docs.openai.com/guides/responses"));
        assertFalse(result.text().contains("https://example.com/other"));
        assertEquals("web_search", capturedRequest.get().tools().getFirst().providerType());
    }

    @Test
    void doesNotFallBackToLocalSearchWhenNativeSearchReturnsNoHits() throws Exception {
        try (TestServer server = new TestServer()) {
            server.handle("/search", exchange -> respond(
                    exchange,
                    200,
                    "text/html",
                    """
                    <html><body>
                      <a class="result__a" href="https://fallback.example/result">Fallback Result</a>
                    </body></html>
                    """
            ));

            WebSearchToolRuntime runtime =
                    new WebSearchToolRuntime(server.httpClient(), URI.create(server.baseUrl() + "/search"));
            ToolExecutionResult result = runtime.execute(
                    new ToolExecutionRequest(
                            "tool-search-native-4",
                            "WebSearch",
                            """
                            {
                              "query": "latest openai responses docs"
                            }
                            """,
                            null,
                            new ToolModelInvoker() {
                                @Override
                                public String invoke(String prompt) {
                                    throw new AssertionError("Structured tool invocation should be used for native web search.");
                                }

                                @Override
                                public ToolModelResponse invoke(ToolModelRequest request) {
                                    return new ToolModelResponse("", List.of());
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

            assertFalse(result.error());
            assertTrue(result.text().contains("No links found."));
            assertFalse(result.text().contains("https://fallback.example/result"));
        }
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

        private java.net.http.HttpClient httpClient() {
            return java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
