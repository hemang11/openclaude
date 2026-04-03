package com.openclaude.provider.openai;

import java.time.Duration;
import java.time.Instant;

public record OpenAiOAuthSession(
        String accessToken,
        String refreshToken,
        String idToken,
        Instant expiresAt,
        String accountId,
        String email,
        Instant updatedAt
) {
    private static final Duration REFRESH_SAFETY_WINDOW = Duration.ofMinutes(1);

    public OpenAiOAuthSession {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken is required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    boolean expiresSoon(Instant now) {
        return !expiresAt.isAfter(now.plus(REFRESH_SAFETY_WINDOW));
    }

    OpenAiOAuthSession refresh(OpenAiTokenResponse tokens, Instant now) {
        String nextAccessToken = firstNonBlank(tokens.accessToken(), accessToken);
        String nextRefreshToken = firstNonBlank(tokens.refreshToken(), refreshToken);
        String nextIdToken = firstNonBlank(tokens.idToken(), idToken);
        OpenAiTokenResponse mergedTokens = new OpenAiTokenResponse(nextIdToken, nextAccessToken, nextRefreshToken, tokens.expiresInSeconds());
        return new OpenAiOAuthSession(
                nextAccessToken,
                nextRefreshToken,
                nextIdToken,
                now.plusSeconds(tokens.expiresInSeconds()),
                firstNonBlank(OpenAiJwtClaims.extractAccountId(mergedTokens), accountId),
                firstNonBlank(OpenAiJwtClaims.extractEmail(mergedTokens), email),
                now
        );
    }

    static OpenAiOAuthSession fromTokenResponse(OpenAiTokenResponse tokens, Instant now) {
        return new OpenAiOAuthSession(
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.idToken(),
                now.plusSeconds(tokens.expiresInSeconds()),
                OpenAiJwtClaims.extractAccountId(tokens),
                OpenAiJwtClaims.extractEmail(tokens),
                now
        );
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }
}
