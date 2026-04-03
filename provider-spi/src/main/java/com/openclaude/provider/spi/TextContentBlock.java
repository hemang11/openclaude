package com.openclaude.provider.spi;

public record TextContentBlock(
        String text
) implements PromptContentBlock {
    public TextContentBlock {
        text = text == null ? "" : text;
    }
}
