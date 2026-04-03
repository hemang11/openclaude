package com.openclaude.provider.spi;

public enum PromptMessageRole {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    private final String apiValue;

    PromptMessageRole(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }
}
