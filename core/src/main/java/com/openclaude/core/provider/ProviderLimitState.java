package com.openclaude.core.provider;

import java.time.Instant;

public record ProviderLimitState(
        String kind,
        int statusCode,
        String message,
        Instant observedAt,
        Instant resetAt,
        String retryAfter
) {
    public ProviderLimitState {
        kind = kind == null || kind.isBlank() ? "unknown" : kind;
        statusCode = Math.max(statusCode, 0);
        message = message == null ? "" : message.strip();
        observedAt = observedAt == null ? Instant.now() : observedAt;
        retryAfter = retryAfter == null || retryAfter.isBlank() ? null : retryAfter.strip();
    }
}
