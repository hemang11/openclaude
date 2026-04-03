package com.openclaude.core.provider;

import com.openclaude.provider.spi.ProviderHttpException;
import com.openclaude.provider.spi.ProviderId;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProviderFailureClassifier {
    private ProviderFailureClassifier() {
    }

    public static ProviderFailure classify(RuntimeException exception) {
        return classify(exception, null);
    }

    public static ProviderFailure classify(RuntimeException exception, ProviderId providerId) {
        if (exception instanceof ProviderHttpException httpException) {
            return classifyHttpFailure(httpException);
        }
        String message = summarize(exception == null ? null : exception.getMessage());
        if (message.toLowerCase(Locale.ROOT).contains("rate limit")) {
            ProviderLimitState limitState = new ProviderLimitState(
                    "rate_limit",
                    0,
                    message,
                    Instant.now(),
                    null,
                    null
            );
            return new ProviderFailure("rate_limit", message, limitState);
        }
        return new ProviderFailure("provider_error", message, null);
    }

    private static ProviderFailure classifyHttpFailure(ProviderHttpException exception) {
        String body = summarize(exception.responseBody());
        String normalized = body.toLowerCase(Locale.ROOT);
        Map<String, List<String>> headers = exception.responseHeaders();
        ProviderLimitState limitState = null;

        if (exception.statusCode() == 429 || normalized.contains("rate limit")) {
            limitState = new ProviderLimitState(
                    "rate_limit",
                    exception.statusCode(),
                    body,
                    Instant.now(),
                    resolveResetAt(headers),
                    firstHeader(headers, "retry-after")
            );
            return new ProviderFailure("rate_limit", body, limitState);
        }

        if (exception.statusCode() == 403
                || normalized.contains("policy")
                || normalized.contains("not allowed")
                || normalized.contains("usage limit")
                || normalized.contains("extra usage")
                || normalized.contains("spending limit")) {
            limitState = new ProviderLimitState(
                    "policy_limit",
                    exception.statusCode(),
                    body,
                    Instant.now(),
                    resolveResetAt(headers),
                    firstHeader(headers, "retry-after")
            );
            return new ProviderFailure("policy_limit", body, limitState);
        }

        if (exception.statusCode() == 401) {
            return new ProviderFailure("auth_error", body, null);
        }

        return new ProviderFailure("provider_error", body, null);
    }

    private static Instant resolveResetAt(Map<String, List<String>> headers) {
        String retryAfter = firstHeader(headers, "retry-after");
        if (retryAfter != null) {
            try {
                return Instant.now().plusSeconds(Long.parseLong(retryAfter.trim()));
            } catch (NumberFormatException ignored) {
                try {
                    return Instant.parse(retryAfter.trim());
                } catch (DateTimeParseException ignoredToo) {
                    // Fall through to provider-specific reset headers.
                }
            }
        }

        for (String name : List.of(
                "anthropic-ratelimit-requests-reset",
                "anthropic-ratelimit-tokens-reset",
                "x-ratelimit-reset-requests",
                "x-ratelimit-reset-tokens"
        )) {
            String value = firstHeader(headers, name);
            if (value == null) {
                continue;
            }
            try {
                return Instant.parse(value.trim());
            } catch (DateTimeParseException ignored) {
                try {
                    return Instant.ofEpochSecond(Long.parseLong(value.trim()));
                } catch (NumberFormatException ignoredToo) {
                    // Ignore malformed reset headers.
                }
            }
        }
        return null;
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null || headers.isEmpty() || name == null || name.isBlank()) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (!name.equalsIgnoreCase(entry.getKey()) || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            String value = entry.getValue().getFirst();
            return value == null || value.isBlank() ? null : value.trim();
        }
        return null;
    }

    private static String summarize(String message) {
        if (message == null || message.isBlank()) {
            return "Provider request failed.";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 239) + "…";
    }

    public record ProviderFailure(
            String category,
            String message,
            ProviderLimitState limitState
    ) {
    }
}
