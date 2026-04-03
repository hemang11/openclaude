package com.openclaude.provider.spi;

import java.util.List;
import java.util.Objects;

public record PromptMessage(
        PromptMessageRole role,
        List<PromptContentBlock> content
) {
    public PromptMessage {
        role = Objects.requireNonNull(role, "role");
        content = content == null ? List.of() : List.copyOf(content);
    }

    public PromptMessage(PromptMessageRole role, String text) {
        this(role, List.of(new TextContentBlock(text)));
    }

    public String text() {
        return content.stream()
                .filter(TextContentBlock.class::isInstance)
                .map(TextContentBlock.class::cast)
                .map(TextContentBlock::text)
                .filter(text -> !text.isBlank())
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }
}
