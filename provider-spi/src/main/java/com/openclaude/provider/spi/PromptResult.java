package com.openclaude.provider.spi;

import java.util.List;

public record PromptResult(
        String text,
        List<PromptContentBlock> content
) {
    public PromptResult {
        text = text == null ? "" : text;
        content = content == null ? List.of() : List.copyOf(content);
    }

    public PromptResult(String text) {
        this(text, text == null || text.isBlank() ? List.of() : List.of(new TextContentBlock(text)));
    }
}
