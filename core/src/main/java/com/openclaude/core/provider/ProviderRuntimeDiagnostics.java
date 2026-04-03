package com.openclaude.core.provider;

import java.time.Instant;

public record ProviderRuntimeDiagnostics(
        Instant lastSuccessfulPromptAt,
        Instant lastFailureAt,
        String lastFailureCategory,
        String lastFailureMessage,
        ProviderLimitState lastLimitState
) {
    public ProviderRuntimeDiagnostics {
        lastFailureCategory = lastFailureCategory == null || lastFailureCategory.isBlank()
                ? null
                : lastFailureCategory.strip();
        lastFailureMessage = lastFailureMessage == null || lastFailureMessage.isBlank()
                ? null
                : lastFailureMessage.strip();
    }

    public static ProviderRuntimeDiagnostics empty() {
        return new ProviderRuntimeDiagnostics(null, null, null, null, null);
    }

    public ProviderRuntimeDiagnostics recordSuccess(Instant when) {
        Instant effective = when == null ? Instant.now() : when;
        return new ProviderRuntimeDiagnostics(effective, lastFailureAt, lastFailureCategory, lastFailureMessage, lastLimitState);
    }

    public ProviderRuntimeDiagnostics recordFailure(
            Instant when,
            String category,
            String message,
            ProviderLimitState limitState
    ) {
        Instant effective = when == null ? Instant.now() : when;
        return new ProviderRuntimeDiagnostics(
                lastSuccessfulPromptAt,
                effective,
                category,
                message,
                limitState == null ? lastLimitState : limitState
        );
    }
}
