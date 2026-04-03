package com.openclaude.provider.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static com.openclaude.provider.spi.testing.ToolSmokeAssertions.assertBashToolAdvertised;
import static com.openclaude.provider.spi.testing.ToolSmokeAssertions.assertLeadingTextBlock;
import static com.openclaude.provider.spi.testing.ToolSmokeAssertions.assertNoPhantomFunctionToolUses;
import static com.openclaude.provider.spi.testing.ToolSmokeAssertions.assertSingleBashToolUse;
import static com.openclaude.provider.spi.testing.ToolSmokeAssertions.assertToolLifecycleEvents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.PromptEvent;
import com.openclaude.provider.spi.PromptExecutionContext;
import com.openclaude.provider.spi.PromptMessage;
import com.openclaude.provider.spi.PromptMessageRole;
import com.openclaude.provider.spi.PromptRequest;
import com.openclaude.provider.spi.PromptResult;
import com.openclaude.provider.spi.ReasoningDeltaEvent;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderHttpException;
import com.openclaude.provider.spi.TextDeltaEvent;
import com.openclaude.provider.spi.ToolCallEvent;
import com.openclaude.provider.spi.ToolUseDiscoveredEvent;
import com.openclaude.provider.spi.ToolUseContentBlock;
import com.openclaude.provider.spi.WebSearchResultContentBlock;
import java.io.ByteArrayInputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class OpenAiApiClientTest {
    @TempDir
    Path tempDir;

    private final OpenAiApiClient client = new OpenAiApiClient(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            name -> "env-value-for-" + name
    );

    @Test
    void readStreamAggregatesTextDeltas() throws Exception {
        String stream = ""
                + "data: {\"type\":\"response.created\"}\n"
                + "\n"
                + "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"Thinking\"}\n"
                + "\n"
                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\n"
                + "\n"
                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\" world\"}\n"
                + "\n"
                + "data: {\"type\":\"response.completed\"}\n"
                + "\n"
                + "data: [DONE]\n"
                + "\n";

        List<String> deltas = new ArrayList<>();
        List<String> reasoning = new ArrayList<>();
        PromptResult result = client.readStream(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                event -> recordEvents(event, deltas, reasoning)
        );

        assertEquals("Hello world", result.text());
        assertEquals(List.of("Hello", " world"), deltas);
        assertEquals(List.of("Thinking"), reasoning);
    }

    @Test
    void readStreamThrowsOnErrorEvent() {
        String stream = ""
                + "data: {\"type\":\"error\",\"error\":{\"message\":\"bad request\"}}\n"
                + "\n";

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> client.readStream(
                        new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                        event -> {
                        }
                )
        );

        assertTrue(exception.getMessage().contains("OpenAI streaming event error"));
    }

    @Test
    void createPayloadIncludesChatHistoryAndStreamFlag() {
        PromptRequest request = new PromptRequest(
                new PromptExecutionContext(ProviderId.OPENAI, AuthMethod.API_KEY, "env:OPENAI_API_KEY", "gpt-5.4"),
                List.of(
                        new PromptMessage(PromptMessageRole.SYSTEM, "You are terse."),
                        new PromptMessage(PromptMessageRole.USER, "Say hi."),
                        new PromptMessage(PromptMessageRole.ASSISTANT, "Hi.")
                ),
                true
        );

        String payload = client.createPayload(request, true);

        assertEquals(
                "{\"model\":\"gpt-5.4\",\"stream\":true,\"instructions\":\"You are terse.\",\"input\":[{\"role\":\"user\",\"content\":\"Say hi.\"},{\"role\":\"assistant\",\"content\":\"Hi.\"}]}",
                payload
        );
    }

    @Test
    void createPayloadIncludesRequiredToolChoiceWhenRequested() {
        PromptRequest request = new PromptRequest(
                new PromptExecutionContext(ProviderId.OPENAI, AuthMethod.API_KEY, "env:OPENAI_API_KEY", "gpt-5.4"),
                List.of(new PromptMessage(PromptMessageRole.USER, "List my Desktop files.")),
                List.of(new com.openclaude.provider.spi.ProviderToolDefinition(
                        "bash",
                        "Run a bash command",
                        "{\"type\":\"object\"}"
                )),
                true,
                "bash"
        );

        String payload = client.createPayload(request, true);

        assertTrue(payload.contains("\"tool_choice\":{\"type\":\"function\",\"name\":\"bash\"}"));
        assertBashToolAdvertised(payload);
    }

    @Test
    void createPayloadIncludesNativeWebSearchToolDefinitions() {
        PromptRequest request = new PromptRequest(
                new PromptExecutionContext(ProviderId.OPENAI, AuthMethod.API_KEY, "env:OPENAI_API_KEY", "gpt-5.4"),
                List.of(new PromptMessage(PromptMessageRole.USER, "Search for the latest OpenAI API docs.")),
                List.of(com.openclaude.provider.spi.ProviderToolDefinition.nativeTool(
                        "web_search",
                        "web_search",
                        """
                        {
                          "search_context_size": "medium",
                          "filters": {
                            "allowed_domains": ["openai.com"]
                          }
                        }
                        """
                )),
                true
        );

        String payload = client.createPayload(request, true);

        assertTrue(payload.contains("\"type\":\"web_search\""));
        assertTrue(payload.contains("\"allowed_domains\":[\"openai.com\"]"));
    }

    @Test
    void createPayloadIncludesNativeWebSearchToolDefinition() {
        PromptRequest request = new PromptRequest(
                new PromptExecutionContext(ProviderId.OPENAI, AuthMethod.API_KEY, "env:OPENAI_API_KEY", "gpt-5.4"),
                List.of(new PromptMessage(PromptMessageRole.USER, "Search the web.")),
                List.of(com.openclaude.provider.spi.ProviderToolDefinition.nativeTool(
                        "web_search",
                        "web_search",
                        """
                        {
                          "filters": {
                            "allowed_domains": ["docs.openai.com"]
                          }
                        }
                        """
                )),
                false
        );

        String payload = client.createPayload(request, false);

        assertTrue(payload.contains("\"type\":\"web_search\""));
        assertTrue(payload.contains("\"allowed_domains\":[\"docs.openai.com\"]"));
        assertFalse(payload.contains("\"type\":\"function\",\"name\":\"web_search\""));
    }

    @Test
    void createPayloadIncludesReasoningEffortWhenConfigured() {
        PromptRequest request = new PromptRequest(
                new PromptExecutionContext(ProviderId.OPENAI, AuthMethod.API_KEY, "env:OPENAI_API_KEY", "gpt-5.4"),
                List.of(new PromptMessage(PromptMessageRole.USER, "Think carefully.")),
                List.of(),
                true,
                null,
                "high"
        );

        String payload = client.createPayload(request, true);

        assertTrue(payload.contains("\"reasoning\":{\"effort\":\"high\"}"));
    }

    @Test
    void readStreamUsesFunctionNameFromOutputItemEvents() throws Exception {
        String stream = ""
                + "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"bash\"}}\n"
                + "\n"
                + "data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"call_id\":\"call_1\",\"delta\":\"{\\\"command\\\":\\\"ls -la ~/Desktop\\\"}\"}\n"
                + "\n"
                + "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"bash\",\"arguments\":\"{\\\"command\\\":\\\"ls -la ~/Desktop\\\"}\"}}\n"
                + "\n"
                + "data: [DONE]\n"
                + "\n";

        List<String> toolNames = new ArrayList<>();
        PromptResult result = client.readStream(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                event -> {
                    if (event instanceof ToolCallEvent toolCallEvent) {
                        toolNames.add(toolCallEvent.toolName());
                    }
                }
        );

        ToolUseContentBlock toolUse = assertSingleBashToolUse(result, "{\"command\":\"ls -la ~/Desktop\"}");
        assertEquals("bash", toolUse.toolName());
        assertTrue(toolNames.contains("bash"));
    }

    @Test
    void readStreamEmitsToolUseDiscoveredEventWhenFunctionCallCompletes() throws Exception {
        String stream = ""
                + "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"bash\"}}\n"
                + "\n"
                + "data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"call_id\":\"call_1\",\"delta\":\"{\\\"command\\\":\\\"ls -la ~/Desktop\\\"}\"}\n"
                + "\n"
                + "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"bash\",\"arguments\":\"{\\\"command\\\":\\\"ls -la ~/Desktop\\\"}\"}}\n"
                + "\n"
                + "data: [DONE]\n"
                + "\n";

        List<ToolUseDiscoveredEvent> discoveredEvents = new ArrayList<>();
        client.readStream(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                event -> {
                    if (event instanceof ToolUseDiscoveredEvent discoveredEvent) {
                        discoveredEvents.add(discoveredEvent);
                    }
                }
        );

        assertEquals(1, discoveredEvents.size());
        assertEquals("call_1", discoveredEvents.getFirst().toolUseId());
        assertEquals("bash", discoveredEvents.getFirst().toolName());
        assertEquals("{\"command\":\"ls -la ~/Desktop\"}", discoveredEvents.getFirst().inputJson());
    }

    @Test
    void readStreamCollectsWebSearchCitationsFromCompletedMessageItems() throws Exception {
        String stream = ""
                + "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"web_search_call\",\"id\":\"ws_1\",\"action\":{\"query\":\"latest openai docs\"}}}\n"
                + "\n"
                + "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Latest docs are available.\",\"annotations\":[{\"type\":\"url_citation\",\"url_citation\":{\"title\":\"OpenAI Docs\",\"url\":\"https://openai.com/docs\"}}]}]}}\n"
                + "\n"
                + "data: [DONE]\n"
                + "\n";

        List<ToolCallEvent> toolEvents = new ArrayList<>();
        PromptResult result = client.readStream(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                event -> {
                    if (event instanceof ToolCallEvent toolCallEvent) {
                        toolEvents.add(toolCallEvent);
                    }
                }
        );

        WebSearchResultContentBlock resultBlock = result.content().stream()
                .filter(WebSearchResultContentBlock.class::isInstance)
                .map(WebSearchResultContentBlock.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(1, resultBlock.hits().size());
        assertEquals("OpenAI Docs", resultBlock.hits().getFirst().title());
        assertEquals("https://openai.com/docs", resultBlock.hits().getFirst().url());
        assertTrue(toolEvents.stream().anyMatch(event ->
                "web_search".equals(event.toolName()) && "latest openai docs".equals(event.command())));
    }

    @Test
    void readStreamDropsUnnamedFunctionArtifactsWhenARealToolCallExists() throws Exception {
        String stream = ""
                + "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function\",\"id\":\"fc_orphan\",\"call_id\":\"fc_orphan\"}}\n"
                + "\n"
                + "data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_orphan\",\"call_id\":\"fc_orphan\",\"delta\":\"{\\\"command\\\":\\\"ls -1 ~/Desktop\\\"}\"}\n"
                + "\n"
                + "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"bash\"}}\n"
                + "\n"
                + "data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"call_id\":\"call_1\",\"delta\":\"{\\\"command\\\":\\\"ls -1 ~/Desktop\\\"}\"}\n"
                + "\n"
                + "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"bash\",\"arguments\":\"{\\\"command\\\":\\\"ls -1 ~/Desktop\\\"}\"}}\n"
                + "\n"
                + "data: [DONE]\n"
                + "\n";

        PromptResult result = client.readStream(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                null
        );

        ToolUseContentBlock toolUse = assertSingleBashToolUse(result, "{\"command\":\"ls -1 ~/Desktop\"}");
        assertEquals("bash", toolUse.toolName());
        assertNoPhantomFunctionToolUses(result);
    }

    @Test
    void toolSmokeRoundTripMatchesSharedBashContract() throws Exception {
        PromptRequest request = new PromptRequest(
                new PromptExecutionContext(ProviderId.OPENAI, AuthMethod.API_KEY, "env:OPENAI_API_KEY", "gpt-5.4"),
                List.of(new PromptMessage(PromptMessageRole.USER, "Summarize my Desktop folders.")),
                List.of(new com.openclaude.provider.spi.ProviderToolDefinition(
                        "bash",
                        "Run a bash command",
                        "{\"type\":\"object\"}"
                )),
                true,
                "bash"
        );
        String payload = client.createPayload(request, true);

        String stream = ""
                + "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"bash\"}}\n"
                + "\n"
                + "data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"call_id\":\"call_1\",\"delta\":\"{\\\"command\\\":\\\"ls -1 ~/Desktop\\\"}\"}\n"
                + "\n"
                + "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"bash\",\"arguments\":\"{\\\"command\\\":\\\"ls -1 ~/Desktop\\\"}\"}}\n"
                + "\n"
                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Desktop contains alpha and beta.\"}\n"
                + "\n"
                + "data: {\"type\":\"response.completed\"}\n"
                + "\n"
                + "data: [DONE]\n"
                + "\n";

        List<PromptEvent> events = new ArrayList<>();
        PromptResult result = client.readStream(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                events::add
        );

        assertBashToolAdvertised(payload);
        assertSingleBashToolUse(result, "{\"command\":\"ls -1 ~/Desktop\"}");
        assertNoPhantomFunctionToolUses(result);
        assertToolLifecycleEvents(events, "bash", "started", "completed");
        assertLeadingTextBlock(result, "Desktop contains alpha and beta.");
    }

    @Test
    void extractResultCollectsWebSearchCitationsFromUrlCitationAnnotations() throws Exception {
        String response = """
                {
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "Latest docs are available.",
                          "annotations": [
                            {
                              "type": "url_citation",
                              "url_citation": {
                                "title": "OpenAI Docs",
                                "url": "https://openai.com/docs"
                              }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

        PromptResult result = OpenAiResponsesSupport.extractResult(new ObjectMapper(), response);

        WebSearchResultContentBlock resultBlock = result.content().stream()
                .filter(WebSearchResultContentBlock.class::isInstance)
                .map(WebSearchResultContentBlock.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(1, resultBlock.hits().size());
        assertEquals("OpenAI Docs", resultBlock.hits().getFirst().title());
        assertEquals("https://openai.com/docs", resultBlock.hits().getFirst().url());
    }

    @Test
    void extractResultCapturesUrlCitationsAsSearchHits() throws Exception {
        String response = """
                {
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "Here are the latest docs.",
                          "annotations": [
                            {
                              "type": "url_citation",
                              "title": "OpenAI Responses Guide",
                              "url": "https://docs.openai.com/guides/responses"
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

        PromptResult result = OpenAiResponsesSupport.extractResult(new ObjectMapper(), response);

        assertTrue(result.content().stream().anyMatch(block ->
                block instanceof WebSearchResultContentBlock searchBlock
                        && searchBlock.hits().stream().anyMatch(hit -> "https://docs.openai.com/guides/responses".equals(hit.url()))
        ));
    }

    @Test
    void resolveBearerTokenReadsApiKeyEnvReference() {
        String token = client.resolveBearerToken(
                new PromptExecutionContext(ProviderId.OPENAI, AuthMethod.API_KEY, "env:OPENAI_API_KEY", "gpt-5.4")
        );

        assertEquals("env-value-for-OPENAI_API_KEY", token);
    }

    @Test
    void resolveBearerTokenReadsStoredApiKeyFile() throws Exception {
        Path file = tempDir.resolve("openai-key.txt");
        Files.writeString(file, "stored-openai-key");

        String token = client.resolveBearerToken(
                new PromptExecutionContext(ProviderId.OPENAI, AuthMethod.API_KEY, "file:" + file, "gpt-5.4")
        );

        assertEquals("stored-openai-key", token);
    }

    @Test
    void resolveBearerTokenAcceptsLegacyEnvPrefixedRawKey() {
        OpenAiApiClient fallbackClient = new OpenAiApiClient(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                name -> null
        );

        String token = fallbackClient.resolveBearerToken(
                new PromptExecutionContext(ProviderId.OPENAI, AuthMethod.API_KEY, "env:sk-proj-raw-key", "gpt-5.4")
        );

        assertEquals("sk-proj-raw-key", token);
    }

    @Test
    void executeThrowsProviderHttpExceptionOnNonSuccessStatus() {
        OpenAiApiClient failingClient = new OpenAiApiClient(
                new SequencedHttpClient(List.of(ResponseSpec.of(
                        429,
                        """
                        {"error":{"message":"rate limit exceeded"}}
                        """,
                        Map.of("retry-after", List.of("60"))
                ))),
                new ObjectMapper(),
                name -> "env-value-for-" + name
        );

        ProviderHttpException exception = assertThrows(
                ProviderHttpException.class,
                () -> failingClient.execute(new PromptRequest(
                        new PromptExecutionContext(ProviderId.OPENAI, AuthMethod.API_KEY, "env:OPENAI_API_KEY", "gpt-5.4"),
                        List.of(new PromptMessage(PromptMessageRole.USER, "Hello")),
                        false
                ))
        );

        assertEquals(429, exception.statusCode());
        assertTrue(exception.responseBody().contains("rate limit exceeded"));
    }

    @Test
    void executeStreamingThrowsProviderHttpExceptionOnNonSuccessStatus() {
        OpenAiApiClient failingClient = new OpenAiApiClient(
                new SequencedHttpClient(List.of(ResponseSpec.of(
                        500,
                        """
                        {"error":{"message":"server exploded"}}
                        """,
                        Map.of()
                ))),
                new ObjectMapper(),
                name -> "env-value-for-" + name
        );

        ProviderHttpException exception = assertThrows(
                ProviderHttpException.class,
                () -> failingClient.executeStreaming(
                        new PromptRequest(
                                new PromptExecutionContext(ProviderId.OPENAI, AuthMethod.API_KEY, "env:OPENAI_API_KEY", "gpt-5.4"),
                                List.of(new PromptMessage(PromptMessageRole.USER, "Hello")),
                                false
                        ),
                        event -> {
                        }
                )
        );

        assertEquals(500, exception.statusCode());
        assertTrue(exception.responseBody().contains("server exploded"));
    }

    private static void recordEvents(PromptEvent event, List<String> deltas, List<String> reasoning) {
        if (event instanceof TextDeltaEvent textDeltaEvent) {
            deltas.add(textDeltaEvent.text());
        } else if (event instanceof ReasoningDeltaEvent reasoningDeltaEvent) {
            reasoning.add(reasoningDeltaEvent.text());
        }
    }

    private record ResponseSpec(int statusCode, String body, Map<String, List<String>> headers) {
        private static ResponseSpec of(int statusCode, String body, Map<String, List<String>> headers) {
            return new ResponseSpec(statusCode, body, headers == null ? Map.of() : headers);
        }
    }

    private static final class SequencedHttpClient extends HttpClient {
        private final List<ResponseSpec> responses;
        private int index = 0;

        private SequencedHttpClient(List<ResponseSpec> responses) {
            this.responses = responses;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
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
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            ResponseSpec spec = responses.get(index++);
            T body = materializeBody(responseBodyHandler, spec);
            return new RecordingHttpResponse<>(request, spec.statusCode(), body, spec.headers());
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("sendAsync is not used");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException("sendAsync is not used");
        }

        private static <T> T materializeBody(
                HttpResponse.BodyHandler<T> responseBodyHandler,
                ResponseSpec spec
        ) {
            HttpResponse.ResponseInfo responseInfo = new HttpResponse.ResponseInfo() {
                @Override
                public int statusCode() {
                    return spec.statusCode();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(spec.headers(), (left, right) -> true);
                }

                @Override
                public HttpClient.Version version() {
                    return HttpClient.Version.HTTP_1_1;
                }
            };
            HttpResponse.BodySubscriber<T> subscriber = responseBodyHandler.apply(responseInfo);
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                }

                @Override
                public void cancel() {
                }
            });
            subscriber.onNext(List.of(ByteBuffer.wrap(spec.body().getBytes(StandardCharsets.UTF_8))));
            subscriber.onComplete();
            return subscriber.getBody().toCompletableFuture().join();
        }
    }

    private record RecordingHttpResponse<T>(
            HttpRequest request,
            int statusCode,
            T body,
            Map<String, List<String>> headersMap
    ) implements HttpResponse<T> {
        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(headersMap == null ? Map.of() : headersMap, (left, right) -> true);
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }
    }
}
