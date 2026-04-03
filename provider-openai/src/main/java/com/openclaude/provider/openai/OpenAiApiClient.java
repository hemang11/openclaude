package com.openclaude.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.PromptEvent;
import com.openclaude.provider.spi.PromptExecutionContext;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;

final class OpenAiApiClient {
    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Function<String, String> environmentReader;

    OpenAiApiClient() {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                new ObjectMapper(),
                System::getenv
        );
    }

    OpenAiApiClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Function<String, String> environmentReader
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.environmentReader = environmentReader;
    }

    PromptResult execute(PromptRequest request) {
        String bearerToken = resolveBearerToken(request.context());
        String payload = createPayload(request, false);

        HttpRequest httpRequest = HttpRequest.newBuilder(RESPONSES_URI)
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderHttpException(
                        ProviderId.OPENAI,
                        response.statusCode(),
                        response.body(),
                        response.headers().map(),
                        "OpenAI API request failed: HTTP " + response.statusCode() + " body=" + response.body()
                );
            }

            return OpenAiResponsesSupport.extractResult(objectMapper, response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to call OpenAI Responses API", exception);
        }
    }

    PromptResult executeStreaming(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
        String bearerToken = resolveBearerToken(request.context());
        String payload = createPayload(request, true);

        HttpRequest httpRequest = HttpRequest.newBuilder(RESPONSES_URI)
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
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
                        "OpenAI streaming request failed: HTTP " + response.statusCode() + " body=" + body
                );
            }

            try (InputStream responseBody = response.body()) {
                return readStream(responseBody, eventConsumer);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI streaming request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to stream OpenAI Responses API", exception);
        }
    }

    String createPayload(PromptRequest request, boolean stream) {
        return OpenAiResponsesSupport.createPayload(objectMapper, request, stream);
    }

    PromptResult readStream(InputStream stream, Consumer<PromptEvent> eventConsumer) throws IOException {
        return OpenAiResponsesSupport.readStream(objectMapper, stream, eventConsumer);
    }

    String resolveBearerToken(PromptExecutionContext context) {
        if (context.authMethod() != AuthMethod.API_KEY) {
            throw new IllegalStateException("OpenAI API client supports only API-key auth.");
        }
        return resolveApiKey(context.credentialReference());
    }

    private static String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String resolveApiKey(String credentialReference) {
        if (credentialReference == null || credentialReference.isBlank()) {
            throw new IllegalStateException("OpenAI API key reference is required.");
        }

        if (credentialReference.startsWith("file:")) {
            return readStoredKey(Path.of(credentialReference.substring("file:".length())));
        }

        if (!credentialReference.startsWith("env:")) {
            throw new IllegalStateException("OpenAI API key reference must use env:NAME or file:/path");
        }

        String envVar = credentialReference.substring("env:".length());
        String apiKey = environmentReader.apply(envVar);
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        if (!envVar.matches("[A-Z_][A-Z0-9_]*")) {
            return envVar;
        }
        throw new IllegalStateException("Environment variable is not set or empty: " + envVar);
    }

    private static String readStoredKey(Path path) {
        try {
            String apiKey = Files.readString(path).trim();
            if (apiKey.isBlank()) {
                throw new IllegalStateException("Stored API key file is empty: " + path);
            }
            return apiKey;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read stored API key: " + path, exception);
        }
    }
}
