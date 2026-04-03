package com.openclaude.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaude.provider.spi.PromptEvent;
import com.openclaude.provider.spi.PromptRequest;
import com.openclaude.provider.spi.PromptResult;
import com.openclaude.provider.spi.ProviderHttpException;
import com.openclaude.provider.spi.ProviderId;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

final class OpenAiCodexResponsesClient {
    private static final URI RESPONSES_URI = URI.create("https://chatgpt.com/backend-api/codex/responses");
    private static final String USER_AGENT = buildUserAgent();

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenAiBrowserAuthService browserAuthService;

    OpenAiCodexResponsesClient() {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                new ObjectMapper(),
                new OpenAiBrowserAuthService()
        );
    }

    OpenAiCodexResponsesClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            OpenAiBrowserAuthService browserAuthService
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.browserAuthService = Objects.requireNonNull(browserAuthService, "browserAuthService");
    }

    PromptResult execute(PromptRequest request) {
        return executeStreaming(request, event -> {
        });
    }

    PromptResult executeStreaming(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
        OpenAiOAuthSession session = browserAuthService.resolveSession(request.context().credentialReference());
        String payload = OpenAiResponsesSupport.createCodexPayload(objectMapper, request, true);

        HttpRequest httpRequest = baseRequest(session)
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = readBody(response.body());
                throw new ProviderHttpException(
                        ProviderId.OPENAI,
                        response.statusCode(),
                        body,
                        response.headers().map(),
                        "OpenAI browser-auth streaming request failed: HTTP " + response.statusCode() + " body=" + body
                );
            }

            try (InputStream responseBody = response.body()) {
                return OpenAiResponsesSupport.readStream(objectMapper, responseBody, eventConsumer);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI browser-auth streaming request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to stream OpenAI Codex responses endpoint", exception);
        }
    }

    private HttpRequest.Builder baseRequest(OpenAiOAuthSession session) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(RESPONSES_URI)
                .header("Authorization", "Bearer " + session.accessToken())
                .header("Content-Type", "application/json")
                .header("originator", "openclaude")
                .header("User-Agent", USER_AGENT);

        if (session.accountId() != null && !session.accountId().isBlank()) {
            builder.header("ChatGPT-Account-Id", session.accountId());
        }
        return builder;
    }

    private static String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String buildUserAgent() {
        String osName = System.getProperty("os.name", "unknown-os");
        String osVersion = System.getProperty("os.version", "unknown-version");
        String osArch = System.getProperty("os.arch", "unknown-arch");
        String version = System.getProperty("openclaude.version", "0.1.0-SNAPSHOT");
        return "openclaude/" + version + " (" + osName + " " + osVersion + "; " + osArch + ")";
    }
}
