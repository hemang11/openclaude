package com.openclaude.provider.spi;

import java.util.Objects;

public record ProviderToolDefinition(
        String name,
        String description,
        String inputSchemaJson,
        String providerType,
        String providerConfigJson
) {
    public ProviderToolDefinition {
        name = Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        inputSchemaJson = inputSchemaJson == null || inputSchemaJson.isBlank() ? "{}" : inputSchemaJson;
        providerType = providerType == null || providerType.isBlank() ? null : providerType;
        providerConfigJson = providerConfigJson == null || providerConfigJson.isBlank() ? "{}" : providerConfigJson;
    }

    public ProviderToolDefinition(
            String name,
            String description,
            String inputSchemaJson
    ) {
        this(name, description, inputSchemaJson, null, "{}");
    }

    public static ProviderToolDefinition nativeTool(
            String name,
            String providerType,
            String providerConfigJson
    ) {
        return new ProviderToolDefinition(name, "", "{}", providerType, providerConfigJson);
    }

    public boolean isNativeProviderTool() {
        return providerType != null && !providerType.isBlank();
    }
}
