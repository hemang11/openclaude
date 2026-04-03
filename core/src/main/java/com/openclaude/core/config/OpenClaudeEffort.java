package com.openclaude.core.config;

import com.openclaude.provider.spi.ProviderId;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

public final class OpenClaudeEffort {
    public static final String ENV_OVERRIDE_NAME = "CLAUDE_CODE_EFFORT_LEVEL";
    private static final Set<String> VALID_LEVELS = Set.of("low", "medium", "high", "max");

    private OpenClaudeEffort() {
    }

    public static String normalizeConfiguredValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("auto".equals(normalized) || "unset".equals(normalized)) {
            return null;
        }
        if (!VALID_LEVELS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Invalid effort level: " + value + ". Valid options are: low, medium, high, max, auto"
            );
        }
        return normalized;
    }

    public static boolean isValidCommandArgument(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "auto".equals(normalized)
                || "unset".equals(normalized)
                || VALID_LEVELS.contains(normalized);
    }

    public static String description(String effortLevel) {
        return switch (normalizeConfiguredValue(effortLevel)) {
            case "low" -> "Quick, straightforward implementation with minimal overhead";
            case "medium" -> "Balanced approach with standard implementation and testing";
            case "high" -> "Comprehensive implementation with extensive testing and documentation";
            case "max" -> "Maximum capability with deepest reasoning (Opus 4.6 only)";
            case null -> "Use the provider/model default effort";
            default -> throw new IllegalStateException("Unexpected effort level: " + effortLevel);
        };
    }

    public static String resolveForPrompt(ProviderId providerId, String modelId, String configuredEffort) {
        return resolveForPrompt(providerId, modelId, configuredEffort, System::getenv);
    }

    public static String resolveForPrompt(
            ProviderId providerId,
            String modelId,
            String configuredEffort,
            Function<String, String> environmentLookup
    ) {
        EffortEnvironmentOverride environmentOverride = environmentOverride(environmentLookup);
        String normalized = environmentOverride.present()
                ? environmentOverride.value()
                : normalizeConfiguredValue(configuredEffort);
        if (normalized == null) {
            return null;
        }
        if (!supportsEffort(providerId, modelId)) {
            return null;
        }
        if ("max".equals(normalized)) {
            return "high";
        }
        return normalized;
    }

    public static String currentValue(String configuredEffort, String sessionEffortLevel) {
        String sessionValue = normalizeConfiguredValue(sessionEffortLevel);
        return sessionValue != null ? sessionValue : normalizeConfiguredValue(configuredEffort);
    }

    public static String persistableValue(String effortLevel) {
        String normalized = normalizeConfiguredValue(effortLevel);
        if (normalized == null || "max".equals(normalized)) {
            return null;
        }
        return normalized;
    }

    public static String displayedLevel(ProviderId providerId, String modelId, String configuredEffort) {
        return displayedLevel(providerId, modelId, configuredEffort, System::getenv);
    }

    public static String displayedLevel(
            ProviderId providerId,
            String modelId,
            String configuredEffort,
            Function<String, String> environmentLookup
    ) {
        String resolved = resolveForPrompt(providerId, modelId, configuredEffort, environmentLookup);
        return resolved == null ? "high" : resolved;
    }

    public static EffortEnvironmentOverride environmentOverride() {
        return environmentOverride(System::getenv);
    }

    public static EffortEnvironmentOverride environmentOverride(Function<String, String> environmentLookup) {
        if (environmentLookup == null) {
            return EffortEnvironmentOverride.absent();
        }
        String raw = environmentLookup.apply(ENV_OVERRIDE_NAME);
        if (raw == null || raw.isBlank()) {
            return EffortEnvironmentOverride.absent();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("auto".equals(normalized) || "unset".equals(normalized)) {
            return new EffortEnvironmentOverride(true, raw, null);
        }
        if (!VALID_LEVELS.contains(normalized)) {
            return EffortEnvironmentOverride.absent();
        }
        return new EffortEnvironmentOverride(true, raw, normalized);
    }

    public static boolean supportsEffort(ProviderId providerId, String modelId) {
        if (providerId != ProviderId.OPENAI) {
            return false;
        }
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        String normalizedModel = modelId.toLowerCase(Locale.ROOT);
        return normalizedModel.startsWith("gpt-5");
    }

    public record EffortEnvironmentOverride(boolean present, String rawValue, String value) {
        public static EffortEnvironmentOverride absent() {
            return new EffortEnvironmentOverride(false, null, null);
        }
    }
}
