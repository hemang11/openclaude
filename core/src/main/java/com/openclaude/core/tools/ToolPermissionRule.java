package com.openclaude.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Locale;

public record ToolPermissionRule(
        String signature,
        String toolName,
        String interactionType,
        String command,
        String reason,
        String behavior,
        Instant createdAt,
        String source
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public ToolPermissionRule {
        signature = normalizeField(signature);
        toolName = normalizeField(toolName);
        interactionType = normalizeField(interactionType);
        command = normalizeField(command);
        reason = normalizeField(reason);
        behavior = normalizeBehavior(behavior);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        source = ToolPermissionSources.normalize(source);
    }

    public ToolPermissionRule(
            String signature,
            String toolName,
            String interactionType,
            String command,
            String reason,
            String behavior,
            Instant createdAt
    ) {
        this(signature, toolName, interactionType, command, reason, behavior, createdAt, ToolPermissionSources.SESSION);
    }

    public static ToolPermissionRule allow(ToolPermissionRequest request) {
        return fromRequest(request, "allow", ToolPermissionSources.SESSION);
    }

    public static ToolPermissionRule deny(ToolPermissionRequest request) {
        return fromRequest(request, "deny", ToolPermissionSources.SESSION);
    }

    public static ToolPermissionRule fromRequest(ToolPermissionRequest request, String behavior) {
        return fromRequest(request, behavior, ToolPermissionSources.SESSION);
    }

    public static ToolPermissionRule fromRequest(ToolPermissionRequest request, String behavior, String source) {
        return new ToolPermissionRule(
                signatureFor(request),
                request.toolName(),
                request.interactionType(),
                request.command(),
                request.reason(),
                behavior,
                Instant.now(),
                source
        );
    }

    public static ToolPermissionRule fromPermissionRuleString(String source, String behavior, String ruleString) {
        PermissionRuleStringCodec.RuleValue parsed = PermissionRuleStringCodec.parse(ruleString);
        return new ToolPermissionRule(
                "",
                parsed.toolName(),
                "",
                parsed.ruleContent(),
                "",
                behavior,
                Instant.now(),
                source
        );
    }

    public boolean matches(ToolPermissionRequest request) {
        if (!normalizeField(toolName).equalsIgnoreCase(normalizeField(request.toolName()))) {
            return false;
        }
        if (!signature.isBlank()) {
            return signature.equals(signatureFor(request));
        }

        String ruleContent = ruleContent();
        if (ruleContent.isBlank()) {
            return true;
        }

        if (!command.isBlank() && matchesRuleValue(command, normalizeField(request.command()))) {
            return true;
        }
        return !reason.isBlank() && matchesRuleValue(reason, normalizeField(request.reason()));
    }

    public boolean allows() {
        return "allow".equals(behavior);
    }

    public boolean denies() {
        return "deny".equals(behavior);
    }

    public boolean asks() {
        return "ask".equals(behavior);
    }

    public ToolPermissionDecision toDecision() {
        String detail = ToolPermissionSources.displayName(source).toLowerCase(Locale.ROOT);
        if (allows()) {
            return ToolPermissionDecision.allow("Allowed by " + detail + ".");
        }
        if (asks()) {
            return ToolPermissionDecision.ask("Approval required by " + detail + ".");
        }
        return ToolPermissionDecision.deny("Denied by " + detail + ".");
    }

    public String displayTarget() {
        return ruleContent().isBlank() ? toolName : ruleContent();
    }

    public String ruleContent() {
        if (!command.isBlank()) {
            return command;
        }
        return reason;
    }

    public String toRuleString() {
        return PermissionRuleStringCodec.format(toolName, ruleContent());
    }

    public ToolPermissionRule withSource(String nextSource) {
        return new ToolPermissionRule(signature, toolName, interactionType, command, reason, behavior, createdAt, nextSource);
    }

    public static String signatureFor(ToolPermissionRequest request) {
        String normalizedCommand = normalizeField(request.command());
        String normalizedInputJson = normalizeJson(request.inputJson());
        String normalizedReason = normalizedCommand.isBlank() && normalizedInputJson.isBlank()
                ? normalizeField(request.reason())
                : "";
        return String.join("|",
                normalizeField(request.toolName()).toLowerCase(Locale.ROOT),
                normalizeField(request.interactionType()).toLowerCase(Locale.ROOT),
                normalizedCommand,
                normalizedInputJson,
                normalizedReason
        );
    }

    private static String normalizeBehavior(String value) {
        String normalized = normalizeField(value).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "deny" -> "deny";
            case "ask" -> "ask";
            default -> "allow";
        };
    }

    private static String normalizeJson(String value) {
        String trimmed = normalizeField(value);
        if (trimmed.isBlank() || "{}".equals(trimmed)) {
            return "";
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(trimmed);
            if (node == null || node.isNull() || (node.isObject() && node.isEmpty())) {
                return "";
            }
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    private static String normalizeField(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean matchesRuleValue(String ruleValue, String requestValue) {
        String normalizedRule = normalizeField(ruleValue);
        String normalizedRequest = normalizeField(requestValue);
        if (normalizedRule.isBlank()) {
            return true;
        }
        if (!normalizedRule.contains("*")) {
            return normalizedRule.equals(normalizedRequest);
        }
        StringBuilder pattern = new StringBuilder();
        for (int index = 0; index < normalizedRule.length(); index += 1) {
            char character = normalizedRule.charAt(index);
            if (character == '*') {
                pattern.append(".*");
                continue;
            }
            if ("\\.[]{}()+-^$|".indexOf(character) >= 0) {
                pattern.append('\\');
            }
            pattern.append(character);
        }
        return normalizedRequest.matches(pattern.toString());
    }
}
