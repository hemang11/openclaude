package com.openclaude.provider.spi;

public record PromptStatusEvent(
        String message
) implements PromptEvent {
}
