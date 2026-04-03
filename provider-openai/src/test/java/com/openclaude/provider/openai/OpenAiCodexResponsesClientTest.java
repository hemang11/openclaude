package com.openclaude.provider.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaude.auth.BrowserAuthCoordinator;
import com.openclaude.auth.BrowserAuthRequest;
import com.openclaude.auth.BrowserAuthSession;
import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.PromptExecutionContext;
import com.openclaude.provider.spi.PromptMessage;
import com.openclaude.provider.spi.PromptMessageRole;
import com.openclaude.provider.spi.PromptRequest;
import com.openclaude.provider.spi.PromptResult;
import com.openclaude.provider.spi.ProviderHttpException;
import com.openclaude.provider.spi.ProviderId;
import java.io.ByteArrayInputStream;
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
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class OpenAiCodexResponsesClientTest {
    @Test
    void executeUsesStoredOAuthSessionAndCodexEndpoint() {
        Path authDir = Path.of(System.getProperty("java.io.tmpdir")).resolve("openclaude-codex-client-" + System.nanoTime());
        OpenAiOAuthStore store = new OpenAiOAuthStore(authDir);
        store.save(
                OpenAiOAuthStore.DEFAULT_CREDENTIAL_REFERENCE,
                new OpenAiOAuthSession(
                        "access-browser",
                        "refresh-browser",
                        null,
                        Instant.parse("2026-04-03T00:00:00Z"),
                        "acct_browser",
                        "user@example.com",
                        Instant.parse("2026-04-02T00:00:00Z")
                )
        );

        OpenAiBrowserAuthService authService = new OpenAiBrowserAuthService(
                new NoopCoordinator(),
                uri -> true,
                new NoopOAuthApi(),
                store,
                Clock.fixed(Instant.parse("2026-04-02T06:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(5)
        );
        RecordingHttpClient httpClient = new RecordingHttpClient(
                200,
                """
                data: {"type":"response.output_text.delta","delta":"Browser reply"}

                data: [DONE]

                """
        );

        OpenAiCodexResponsesClient client = new OpenAiCodexResponsesClient(httpClient, new ObjectMapper(), authService);
        PromptResult result = client.execute(request());

        assertEquals("Browser reply", result.text());
        assertEquals("https://chatgpt.com/backend-api/codex/responses", httpClient.lastRequest.uri().toString());
        assertEquals("Bearer access-browser", httpClient.lastRequest.headers().firstValue("Authorization").orElseThrow());
        assertEquals("acct_browser", httpClient.lastRequest.headers().firstValue("ChatGPT-Account-Id").orElseThrow());
        assertEquals("openclaude", httpClient.lastRequest.headers().firstValue("originator").orElseThrow());
        assertTrue(httpClient.requestBody.contains("\"model\":\"gpt-5.3-codex\""));
        assertTrue(httpClient.requestBody.contains("\"store\":false"));
        assertTrue(httpClient.requestBody.contains("\"stream\":true"));
        assertTrue(httpClient.requestBody.contains("\"instructions\":\""));
        assertTrue(httpClient.requestBody.contains("You are terse."));
        assertTrue(httpClient.requestBody.contains("\"role\":\"user\""));
        assertTrue(!httpClient.requestBody.contains("\"role\":\"system\""));
    }

    @Test
    void executeIncludesReasoningEffortWhenConfigured() {
        Path authDir = Path.of(System.getProperty("java.io.tmpdir")).resolve("openclaude-codex-client-effort-" + System.nanoTime());
        OpenAiOAuthStore store = new OpenAiOAuthStore(authDir);
        store.save(
                OpenAiOAuthStore.DEFAULT_CREDENTIAL_REFERENCE,
                new OpenAiOAuthSession(
                        "access-browser",
                        "refresh-browser",
                        null,
                        Instant.parse("2026-04-03T00:00:00Z"),
                        "acct_browser",
                        "user@example.com",
                        Instant.parse("2026-04-02T00:00:00Z")
                )
        );

        OpenAiBrowserAuthService authService = new OpenAiBrowserAuthService(
                new NoopCoordinator(),
                uri -> true,
                new NoopOAuthApi(),
                store,
                Clock.fixed(Instant.parse("2026-04-02T06:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(5)
        );
        RecordingHttpClient httpClient = new RecordingHttpClient(
                200,
                """
                data: {"type":"response.output_text.delta","delta":"Browser reply"}

                data: [DONE]

                """
        );

        OpenAiCodexResponsesClient client = new OpenAiCodexResponsesClient(httpClient, new ObjectMapper(), authService);
        client.execute(new PromptRequest(
                new PromptExecutionContext(
                        ProviderId.OPENAI,
                        AuthMethod.BROWSER_SSO,
                        OpenAiOAuthStore.DEFAULT_CREDENTIAL_REFERENCE,
                        "gpt-5.3-codex"
                ),
                List.of(
                        new PromptMessage(PromptMessageRole.SYSTEM, "You are terse."),
                        new PromptMessage(PromptMessageRole.USER, "Say hi.")
                ),
                List.of(),
                false,
                null,
                "medium"
        ));

        assertTrue(httpClient.requestBody.contains("\"reasoning\":{\"effort\":\"medium\"}"));
    }

    @Test
    void executeThrowsProviderHttpExceptionOnNonSuccessStatus() {
        Path authDir = Path.of(System.getProperty("java.io.tmpdir")).resolve("openclaude-codex-client-error-" + System.nanoTime());
        OpenAiOAuthStore store = new OpenAiOAuthStore(authDir);
        store.save(
                OpenAiOAuthStore.DEFAULT_CREDENTIAL_REFERENCE,
                new OpenAiOAuthSession(
                        "access-browser",
                        "refresh-browser",
                        null,
                        Instant.parse("2026-04-03T00:00:00Z"),
                        "acct_browser",
                        "user@example.com",
                        Instant.parse("2026-04-02T00:00:00Z")
                )
        );

        OpenAiBrowserAuthService authService = new OpenAiBrowserAuthService(
                new NoopCoordinator(),
                uri -> true,
                new NoopOAuthApi(),
                store,
                Clock.fixed(Instant.parse("2026-04-02T06:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(5)
        );
        RecordingHttpClient httpClient = new RecordingHttpClient(
                429,
                """
                {"error":{"message":"rate limit exceeded"}}
                """
        );

        OpenAiCodexResponsesClient client = new OpenAiCodexResponsesClient(httpClient, new ObjectMapper(), authService);

        ProviderHttpException exception = assertThrows(ProviderHttpException.class, () -> client.execute(request()));
        assertEquals(429, exception.statusCode());
        assertTrue(exception.responseBody().contains("rate limit exceeded"));
    }

    private static PromptRequest request() {
        return new PromptRequest(
                new PromptExecutionContext(
                        ProviderId.OPENAI,
                        AuthMethod.BROWSER_SSO,
                        OpenAiOAuthStore.DEFAULT_CREDENTIAL_REFERENCE,
                        "gpt-5.3-codex"
                ),
                List.of(
                        new PromptMessage(PromptMessageRole.SYSTEM, "You are terse."),
                        new PromptMessage(PromptMessageRole.USER, "Say hi.")
                ),
                false
        );
    }

    private static final class NoopCoordinator implements BrowserAuthCoordinator {
        @Override
        public boolean supports(ProviderId providerId) {
            return true;
        }

        @Override
        public BrowserAuthSession begin(BrowserAuthRequest request) {
            throw new UnsupportedOperationException("begin should not be called");
        }
    }

    private static final class NoopOAuthApi implements OpenAiOAuthApi {
        @Override
        public OpenAiTokenResponse exchangeAuthorizationCode(String code, URI redirectUri, com.openclaude.auth.PkceChallenge pkceChallenge) {
            throw new UnsupportedOperationException("exchangeAuthorizationCode should not be called");
        }

        @Override
        public OpenAiTokenResponse refreshAccessToken(String refreshToken) {
            throw new UnsupportedOperationException("refreshAccessToken should not be called");
        }
    }

    private static final class RecordingHttpClient extends HttpClient {
        private final int statusCode;
        private final String responseBody;
        private HttpRequest lastRequest;
        private String requestBody;

        private RecordingHttpClient(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
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
            this.lastRequest = request;
            this.requestBody = request.bodyPublisher()
                    .map(RecordingBodySubscriber::readBody)
                    .orElse("");
            @SuppressWarnings("unchecked")
            T body = (T) new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8));
            return new RecordingHttpResponse<>(request, statusCode, body);
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
    }

    private record RecordingHttpResponse<T>(HttpRequest request, int statusCode, T body) implements HttpResponse<T> {
        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (left, right) -> true);
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
