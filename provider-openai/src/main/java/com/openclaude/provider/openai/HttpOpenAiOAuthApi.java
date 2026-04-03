package com.openclaude.provider.openai;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaude.auth.PkceChallenge;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

final class HttpOpenAiOAuthApi implements OpenAiOAuthApi {
    private static final URI TOKEN_ENDPOINT = URI.create("https://auth.openai.com/oauth/token");
    static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    HttpOpenAiOAuthApi() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(), new ObjectMapper());
    }

    HttpOpenAiOAuthApi(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public OpenAiTokenResponse exchangeAuthorizationCode(String code, URI redirectUri, PkceChallenge pkceChallenge) {
        return tokenRequest(Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", redirectUri.toString(),
                "client_id", CLIENT_ID,
                "code_verifier", pkceChallenge.verifier()
        ));
    }

    @Override
    public OpenAiTokenResponse refreshAccessToken(String refreshToken) {
        return tokenRequest(Map.of(
                "grant_type", "refresh_token",
                "refresh_token", refreshToken,
                "client_id", CLIENT_ID
        ));
    }

    private OpenAiTokenResponse tokenRequest(Map<String, String> formFields) {
        HttpRequest request = HttpRequest.newBuilder(TOKEN_ENDPOINT)
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody(formFields)))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "OpenAI OAuth token request failed: HTTP " + response.statusCode() + " body=" + response.body()
                );
            }
            return parse(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI OAuth token request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to call OpenAI OAuth token endpoint", exception);
        }
    }

    private OpenAiTokenResponse parse(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        return new OpenAiTokenResponse(
                root.path("id_token").asText(null),
                root.path("access_token").asText(null),
                root.path("refresh_token").asText(null),
                root.path("expires_in").asLong(3600)
        );
    }

    private static String formBody(Map<String, String> formFields) {
        Map<String, String> ordered = new LinkedHashMap<>(formFields);
        StringJoiner joiner = new StringJoiner("&");
        ordered.forEach((key, value) -> joiner.add(
                URLEncoder.encode(key, StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(value, StandardCharsets.UTF_8)
        ));
        return joiner.toString();
    }
}
