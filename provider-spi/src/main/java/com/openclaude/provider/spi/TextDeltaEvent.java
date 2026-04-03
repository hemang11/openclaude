package com.openclaude.provider.spi;

public record TextDeltaEvent(
        String text
) implements PromptEvent {
    public TextDeltaEvent {
        text = text == null ? "" : text;
    }
}
