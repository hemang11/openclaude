package com.openclaude.core.tools;

public final class PermissionRuleStringCodec {
    private PermissionRuleStringCodec() {
    }

    public static RuleValue parse(String ruleString) {
        String normalized = ruleString == null ? "" : ruleString.trim();
        int openParenIndex = findFirstUnescapedChar(normalized, '(');
        if (openParenIndex < 0) {
            return new RuleValue(normalized, "");
        }

        int closeParenIndex = findLastUnescapedChar(normalized, ')');
        if (closeParenIndex < 0 || closeParenIndex <= openParenIndex || closeParenIndex != normalized.length() - 1) {
            return new RuleValue(normalized, "");
        }

        String toolName = normalized.substring(0, openParenIndex).trim();
        if (toolName.isBlank()) {
            return new RuleValue(normalized, "");
        }

        String rawContent = normalized.substring(openParenIndex + 1, closeParenIndex);
        if (rawContent.isEmpty() || "*".equals(rawContent)) {
            return new RuleValue(toolName, "");
        }
        return new RuleValue(toolName, unescape(rawContent));
    }

    public static String format(String toolName, String ruleContent) {
        String normalizedToolName = toolName == null ? "" : toolName.trim();
        String normalizedRuleContent = ruleContent == null ? "" : ruleContent.trim();
        if (normalizedRuleContent.isBlank()) {
            return normalizedToolName;
        }
        return normalizedToolName + "(" + escape(normalizedRuleContent) + ")";
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)");
    }

    private static String unescape(String value) {
        return value
                .replace("\\(", "(")
                .replace("\\)", ")")
                .replace("\\\\", "\\");
    }

    private static int findFirstUnescapedChar(String value, char target) {
        for (int index = 0; index < value.length(); index += 1) {
            if (value.charAt(index) == target && !isEscaped(value, index)) {
                return index;
            }
        }
        return -1;
    }

    private static int findLastUnescapedChar(String value, char target) {
        for (int index = value.length() - 1; index >= 0; index -= 1) {
            if (value.charAt(index) == target && !isEscaped(value, index)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isEscaped(String value, int index) {
        int slashCount = 0;
        for (int cursor = index - 1; cursor >= 0 && value.charAt(cursor) == '\\'; cursor -= 1) {
            slashCount += 1;
        }
        return slashCount % 2 == 1;
    }

    public record RuleValue(
            String toolName,
            String ruleContent
    ) {
        public RuleValue {
            toolName = toolName == null ? "" : toolName.trim();
            ruleContent = ruleContent == null ? "" : ruleContent.trim();
        }
    }
}
