package com.openclaude.provider.spi;

import java.util.Arrays;

public enum ProviderId {
    ANTHROPIC("anthropic", "Anthropic/Claude"),
    OPENAI("openai", "OpenAI"),
    GEMINI("gemini", "Gemini"),
    MISTRAL("mistral", "Mistral"),
    KIMI("kimi", "Kimi"),
    BEDROCK("bedrock", "Bedrock");

    private final String cliValue;
    private final String displayName;

    ProviderId(String cliValue, String displayName) {
        this.cliValue = cliValue;
        this.displayName = displayName;
    }

    public String cliValue() {
        return cliValue;
    }

    public String displayName() {
        return displayName;
    }

    public static ProviderId parse(String rawValue) {
        return Arrays.stream(values())
                .filter(providerId -> providerId.cliValue.equalsIgnoreCase(rawValue))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + rawValue));
    }
}
