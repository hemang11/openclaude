package com.openclaude.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class OpenAiJwtClaims {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private OpenAiJwtClaims() {
    }

    static String extractAccountId(OpenAiTokenResponse tokens) {
        String accountId = extractAccountId(tokens.idToken());
        return accountId != null ? accountId : extractAccountId(tokens.accessToken());
    }

    static String extractEmail(OpenAiTokenResponse tokens) {
        String email = extractEmail(tokens.idToken());
        return email != null ? email : extractEmail(tokens.accessToken());
    }

    static String extractAccountId(String jwtToken) {
        JsonNode claims = parse(jwtToken);
        if (claims == null) {
            return null;
        }

        String direct = text(claims.path("chatgpt_account_id"));
        if (direct != null) {
            return direct;
        }

        String authClaim = text(claims.path("https://api.openai.com/auth").path("chatgpt_account_id"));
        if (authClaim != null) {
            return authClaim;
        }

        JsonNode organizations = claims.path("organizations");
        if (organizations.isArray() && !organizations.isEmpty()) {
            return text(organizations.get(0).path("id"));
        }
        return null;
    }

    static String extractEmail(String jwtToken) {
        JsonNode claims = parse(jwtToken);
        return claims == null ? null : text(claims.path("email"));
    }

    private static JsonNode parse(String jwtToken) {
        if (jwtToken == null || jwtToken.isBlank()) {
            return null;
        }

        String[] parts = jwtToken.split("\\.");
        if (parts.length != 3) {
            return null;
        }

        try {
            byte[] payload = Base64.getUrlDecoder().decode(pad(parts[1]));
            return OBJECT_MAPPER.readTree(new String(payload, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("");
        return value.isBlank() ? null : value;
    }

    private static String pad(String value) {
        int padding = value.length() % 4;
        if (padding == 0) {
            return value;
        }
        return value + "=".repeat(4 - padding);
    }
}
