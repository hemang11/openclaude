package com.openclaude.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static com.openclaude.provider.spi.testing.ToolSmokeAssertions.assertLeadingTextBlock;
import static com.openclaude.provider.spi.testing.ToolSmokeAssertions.assertNoPhantomFunctionToolUses;
import static com.openclaude.provider.spi.testing.ToolSmokeAssertions.assertBashToolAdvertised;
import static com.openclaude.provider.spi.testing.ToolSmokeAssertions.assertSingleBashToolUse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.PromptEvent;
import com.openclaude.provider.spi.PromptExecutionContext;
import com.openclaude.provider.spi.PromptMessage;
import com.openclaude.provider.spi.PromptMessageRole;
import com.openclaude.provider.spi.PromptRequest;
import com.openclaude.provider.spi.PromptResult;
import com.openclaude.provider.spi.ProviderHttpException;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderToolDefinition;
import com.openclaude.provider.spi.TextContentBlock;
import com.openclaude.provider.spi.ToolResultContentBlock;
import com.openclaude.provider.spi.ToolUseContentBlock;
import com.openclaude.provider.spi.WebSearchResultContentBlock;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class AnthropicApiClientTest {
    @TempDir
    Path tempDir;

    private final AnthropicApiClient client = new AnthropicApiClient(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            name -> "env-value-for-" + name
    );

    @Test
    void createPayloadSeparatesSystemPromptAndMapsLegacyModelIds() {
        PromptRequest request = new PromptRequest(
                new PromptExecutionContext(ProviderId.ANTHROPIC, AuthMethod.API_KEY, "env:ANTHROPIC_API_KEY", "anthropic/sonnet"),
                List.of(
                        new PromptMessage(PromptMessageRole.SYSTEM, "You are terse."),
                        new PromptMessage(PromptMessageRole.USER, "Say hi."),
                        new PromptMessage(PromptMessageRole.ASSISTANT, "Hi.")
                ),
                List.of(new ProviderToolDefinition(
                        "bash",
                        "Run a bash command",
                        "{\"type\":\"object\"}"
                )),
                false
        );

        String payload = client.createPayload(request);

        assertTrue(payload.contains("\"model\":\"claude-sonnet-4-20250514\""));
        assertTrue(payload.contains("\"system\":\"You are terse.\""));
        assertTrue(payload.contains("\"role\":\"user\""));
        assertTrue(payload.contains("\"role\":\"assistant\""));
        assertBashToolAdvertised(payload);
    }

    @Test
    void createPayloadIncludesStructuredToolTrajectoryAndForcedToolChoice() {
        PromptRequest request = new PromptRequest(
                new PromptExecutionContext(ProviderId.ANTHROPIC, AuthMethod.API_KEY, "env:ANTHROPIC_API_KEY", "anthropic/sonnet"),
                List.of(
                        new PromptMessage(PromptMessageRole.SYSTEM, "Use tools."),
                        new PromptMessage(PromptMessageRole.ASSISTANT, List.of(
                                new TextContentBlock("I should inspect the folder."),
                                new ToolUseContentBlock("toolu_1", "bash", "{\"command\":\"ls -1 ~/Desktop\"}")
                        )),
                        new PromptMessage(PromptMessageRole.USER, List.of(
                                new ToolResultContentBlock("toolu_1", "bash", "alpha\nbeta", false)
                        ))
                ),
                List.of(new ProviderToolDefinition(
                        "bash",
                        "Run a bash command",
                        "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}},\"required\":[\"command\"]}"
                )),
                true,
                "bash"
        );

        String payload = client.createPayload(request);

        assertTrue(payload.contains("\"type\":\"tool_use\""));
        assertTrue(payload.contains("\"id\":\"toolu_1\""));
        assertTrue(payload.contains("\"tool_use_id\":\"toolu_1\""));
        assertTrue(payload.contains("\"tool_choice\":{\"type\":\"tool\",\"name\":\"bash\"}"));
        assertBashToolAdvertised(payload);
    }

    @Test
    void createPayloadIncludesNativeWebSearchToolDefinition() {
        PromptRequest request = new PromptRequest(
                new PromptExecutionContext(ProviderId.ANTHROPIC, AuthMethod.API_KEY, "env:ANTHROPIC_API_KEY", "anthropic/sonnet"),
                List.of(new PromptMessage(PromptMessageRole.USER, "Search the web.")),
                List.of(ProviderToolDefinition.nativeTool(
                        "web_search",
                        "web_search_20250305",
                        """
                        {
                          "max_uses": 8,
                          "allowed_domains": ["docs.openai.com"],
                          "blocked_domains": ["example.com"]
                        }
                        """
                )),
                false,
                "web_search"
        );

        String payload = client.createPayload(request);

        assertTrue(payload.contains("\"type\":\"web_search_20250305\""));
        assertTrue(payload.contains("\"name\":\"web_search\""));
        assertTrue(payload.contains("\"allowed_domains\":[\"docs.openai.com\"]"));
        assertTrue(payload.contains("\"blocked_domains\":[\"example.com\"]"));
        assertTrue(payload.contains("\"tool_choice\":{\"type\":\"tool\",\"name\":\"web_search\"}"));
    }

    @Test
    void extractTextJoinsTextContentBlocks() throws Exception {
        String response = """
                {
                  "content": [
                    {"type": "text", "text": "Hello"},
                    {"type": "thinking", "thinking": "hidden"},
                    {"type": "text", "text": "world"}
                  ]
                }
                """;

        assertEquals("Hello" + System.lineSeparator() + "world", client.extractText(response));
    }

    @Test
    void resolveApiKeyReadsEnvironmentReference() {
        String apiKey = client.resolveApiKey(
                new PromptExecutionContext(ProviderId.ANTHROPIC, AuthMethod.API_KEY, "env:ANTHROPIC_API_KEY", "anthropic/opus")
        );

        assertEquals("env-value-for-ANTHROPIC_API_KEY", apiKey);
    }

    @Test
    void resolveApiKeyReadsStoredFileReference() throws Exception {
        Path file = tempDir.resolve("anthropic-key.txt");
        Files.writeString(file, "stored-anthropic-key");

        String apiKey = client.resolveApiKey(
                new PromptExecutionContext(ProviderId.ANTHROPIC, AuthMethod.API_KEY, "file:" + file, "anthropic/opus")
        );

        assertEquals("stored-anthropic-key", apiKey);
    }

    @Test
    void resolveApiKeyAcceptsLegacyEnvPrefixedRawKey() {
        AnthropicApiClient fallbackClient = new AnthropicApiClient(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                name -> null
        );

        String apiKey = fallbackClient.resolveApiKey(
                new PromptExecutionContext(
                        ProviderId.ANTHROPIC,
                        AuthMethod.API_KEY,
                        "env:sk-ant-api03-raw-key-value",
                        "anthropic/opus"
                )
        );

        assertEquals("sk-ant-api03-raw-key-value", apiKey);
    }

    @Test
    void executeStreamingReturnsSharedToolUseBlocksWithoutExecutingThem() {
        SequencedHttpClient httpClient = new SequencedHttpClient(List.of(ResponseSpec.ok("""
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[]}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Need to inspect the folder."}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"bash"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"command\\":\\"pwd\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_stop
                data: {"type":"message_stop"}
                """)));
        AnthropicApiClient toolClient = new AnthropicApiClient(
                httpClient,
                new ObjectMapper(),
                name -> "anthropic-key"
        );

        List<PromptEvent> events = new java.util.ArrayList<>();
        var result = toolClient.executeStreaming(
                new PromptRequest(
                        new PromptExecutionContext(ProviderId.ANTHROPIC, AuthMethod.API_KEY, "env:ANTHROPIC_API_KEY", "anthropic/sonnet"),
                        List.of(new PromptMessage(PromptMessageRole.USER, "Where am I?")),
                        List.of(new ProviderToolDefinition(
                                "bash",
                                "Run a bash command",
                                "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}},\"required\":[\"command\"]}"
                        )),
                        true,
                        "bash"
                ),
                events::add
        );

        assertEquals("", result.text());
        assertEquals(1, httpClient.requestBodies.size());
        assertBashToolAdvertised(httpClient.requestBodies.getFirst());
        assertTrue(httpClient.requestBodies.getFirst().contains("\"stream\":true"));
        assertTrue(httpClient.requestBodies.getFirst().contains("\"tool_choice\":{\"type\":\"tool\",\"name\":\"bash\"}"));
        assertSingleBashToolUse(result, "{\"command\":\"pwd\"}");
        assertNoPhantomFunctionToolUses(result);
        assertTrue(events.stream().anyMatch(event -> "thinking:Need to inspect the folder.".equals(renderEvent(event))));
    }

    @Test
    void executeReturnsNativeWebSearchResultBlocks() {
        SequencedHttpClient httpClient = new SequencedHttpClient(List.of(ResponseSpec.ok("""
                {
                  "content": [
                    {
                      "type": "web_search_tool_result",
                      "tool_use_id": "search_1",
                      "content": [
                        {
                          "title": "OpenAI Responses Guide",
                          "url": "https://docs.openai.com/guides/responses"
                        }
                      ]
                    },
                    {
                      "type": "text",
                      "text": "Here are the latest docs."
                    }
                  ]
                }
                """)));
        AnthropicApiClient nativeSearchClient = new AnthropicApiClient(
                httpClient,
                new ObjectMapper(),
                name -> "anthropic-key"
        );

        PromptResult result = nativeSearchClient.execute(new PromptRequest(
                new PromptExecutionContext(ProviderId.ANTHROPIC, AuthMethod.API_KEY, "env:ANTHROPIC_API_KEY", "anthropic/sonnet"),
                List.of(new PromptMessage(PromptMessageRole.USER, "Search the web.")),
                List.of(),
                false
        ));

        assertTrue(result.content().stream().anyMatch(block ->
                block instanceof WebSearchResultContentBlock searchBlock
                        && searchBlock.hits().stream().anyMatch(hit -> "https://docs.openai.com/guides/responses".equals(hit.url()))
        ));
    }

    @Test
    void executeStreamingEmitsWebSearchQueryAndResultProgress() {
        SequencedHttpClient httpClient = new SequencedHttpClient(List.of(ResponseSpec.ok("""
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[]}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"search_1","name":"web_search"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"query\\":\\"latest openai responses docs\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"search_1","content":[{"title":"OpenAI Responses Guide","url":"https://docs.openai.com/guides/responses"}]}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_stop
                data: {"type":"message_stop"}
                """)));
        AnthropicApiClient nativeSearchClient = new AnthropicApiClient(
                httpClient,
                new ObjectMapper(),
                name -> "anthropic-key"
        );

        List<String> events = new java.util.ArrayList<>();
        nativeSearchClient.executeStreaming(
                new PromptRequest(
                        new PromptExecutionContext(ProviderId.ANTHROPIC, AuthMethod.API_KEY, "env:ANTHROPIC_API_KEY", "anthropic/sonnet"),
                        List.of(new PromptMessage(PromptMessageRole.USER, "Search the web.")),
                        List.of(),
                        true
                ),
                event -> events.add(renderEvent(event))
        );

        assertTrue(events.contains("tool:started:latest openai responses docs"));
        assertTrue(events.contains("tool:completed:Received 1 search result."));
    }

    @Test
    void executeStreamingEmitsTextAndThinkingDeltasFromSseEvents() {
        SequencedHttpClient httpClient = new SequencedHttpClient(List.of(ResponseSpec.ok("""
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[]}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Planning."}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":" world"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_stop
                data: {"type":"message_stop"}
                """)));
        AnthropicApiClient streamingClient = new AnthropicApiClient(
                httpClient,
                new ObjectMapper(),
                name -> "anthropic-key"
        );

        List<String> events = new java.util.ArrayList<>();
        PromptResult result = streamingClient.executeStreaming(
                new PromptRequest(
                        new PromptExecutionContext(ProviderId.ANTHROPIC, AuthMethod.API_KEY, "env:ANTHROPIC_API_KEY", "anthropic/sonnet"),
                        List.of(new PromptMessage(PromptMessageRole.USER, "Say hi.")),
                        List.of(),
                        true
                ),
                event -> events.add(renderEvent(event))
        );

        assertEquals("Hello world", result.text());
        assertTrue(events.contains("thinking:Planning."));
        assertTrue(events.contains("text:Hello"));
        assertTrue(events.contains("text: world"));
        assertLeadingTextBlock(result, "Hello world");
    }

    @Test
    void executeStreamingThrowsProviderHttpExceptionOnNonSuccessStatus() {
        SequencedHttpClient httpClient = new SequencedHttpClient(List.of(ResponseSpec.of(
                429,
                """
                {"type":"error","error":{"type":"rate_limit_error","message":"Too many requests"}}
                """,
                Map.of("retry-after", List.of("60"))
        )));
        AnthropicApiClient streamingClient = new AnthropicApiClient(
                httpClient,
                new ObjectMapper(),
                name -> "anthropic-key"
        );

        ProviderHttpException exception = assertThrows(
                ProviderHttpException.class,
                () -> streamingClient.executeStreaming(
                        new PromptRequest(
                                new PromptExecutionContext(ProviderId.ANTHROPIC, AuthMethod.API_KEY, "env:ANTHROPIC_API_KEY", "anthropic/sonnet"),
                                List.of(new PromptMessage(PromptMessageRole.USER, "Hello")),
                                List.of(),
                                true
                        ),
                        event -> {
                        }
                )
        );

        assertEquals(429, exception.statusCode());
        assertTrue(exception.responseBody().contains("rate_limit_error"));
    }

    @Test
    void executeStreamingThrowsOnStreamErrorEvent() {
        SequencedHttpClient httpClient = new SequencedHttpClient(List.of(ResponseSpec.ok("""
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[]}}

                event: error
                data: {"type":"error","error":{"type":"overloaded_error","message":"Try again later"}}
                """)));
        AnthropicApiClient streamingClient = new AnthropicApiClient(
                httpClient,
                new ObjectMapper(),
                name -> "anthropic-key"
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> streamingClient.executeStreaming(
                        new PromptRequest(
                                new PromptExecutionContext(ProviderId.ANTHROPIC, AuthMethod.API_KEY, "env:ANTHROPIC_API_KEY", "anthropic/sonnet"),
                                List.of(new PromptMessage(PromptMessageRole.USER, "Hello")),
                                List.of(),
                                true
                        ),
                        event -> {
                        }
                )
        );

        assertTrue(exception.getMessage().contains("Anthropic streaming event error"));
    }

    @Test
    void executeReturnsFinalTextAndLeadingTextBlock() {
        SequencedHttpClient httpClient = new SequencedHttpClient(List.of(ResponseSpec.ok("""
                {
                  "content": [
                    {"type": "text", "text": "Folder summary ready."}
                  ]
                }
                """)));
        AnthropicApiClient textClient = new AnthropicApiClient(
                httpClient,
                new ObjectMapper(),
                name -> "anthropic-key"
        );

        PromptResult result = textClient.execute(new PromptRequest(
                new PromptExecutionContext(ProviderId.ANTHROPIC, AuthMethod.API_KEY, "env:ANTHROPIC_API_KEY", "anthropic/sonnet"),
                List.of(new PromptMessage(PromptMessageRole.USER, "Summarize the folder.")),
                List.of(),
                false
        ));

        assertLeadingTextBlock(result, "Folder summary ready.");
    }

    private static String renderEvent(PromptEvent event) {
        if (event instanceof com.openclaude.provider.spi.ToolCallEvent toolCallEvent) {
            String text = toolCallEvent.text() == null ? "" : toolCallEvent.text().trim();
            return "tool:" + toolCallEvent.phase() + ":" + text;
        }
        if (event instanceof com.openclaude.provider.spi.TextDeltaEvent textDeltaEvent) {
            return "text:" + textDeltaEvent.text();
        }
        if (event instanceof com.openclaude.provider.spi.ReasoningDeltaEvent reasoningDeltaEvent) {
            return "thinking:" + reasoningDeltaEvent.text();
        }
        return event.toString();
    }

    private record ResponseSpec(int statusCode, String body, Map<String, List<String>> headers) {
        private static ResponseSpec ok(String body) {
            return of(200, body, Map.of());
        }

        private static ResponseSpec of(int statusCode, String body, Map<String, List<String>> headers) {
            return new ResponseSpec(statusCode, body, headers == null ? Map.of() : headers);
        }
    }

    private static final class SequencedHttpClient extends HttpClient {
        private final List<ResponseSpec> responses;
        private final List<String> requestBodies = new java.util.ArrayList<>();
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
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            requestBodies.add(request.bodyPublisher().map(RecordingBodySubscriber::readBody).orElse(""));
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

    private static final class RecordingBodySubscriber implements Flow.Subscriber<ByteBuffer> {
        private final java.util.concurrent.CompletableFuture<String> bodyFuture = new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            try {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                outputStream.write(bytes);
            } catch (IOException exception) {
                bodyFuture.completeExceptionally(exception);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            bodyFuture.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            bodyFuture.complete(outputStream.toString(StandardCharsets.UTF_8));
        }

        private static String readBody(java.net.http.HttpRequest.BodyPublisher publisher) {
            RecordingBodySubscriber subscriber = new RecordingBodySubscriber();
            publisher.subscribe(subscriber);
            return subscriber.bodyFuture.join();
        }
    }
}
