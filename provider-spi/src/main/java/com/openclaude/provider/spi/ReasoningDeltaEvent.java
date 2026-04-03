package com.openclaude.provider.spi;

public record ReasoningDeltaEvent(
        String text,
        boolean summary
) implements PromptEvent {
}
