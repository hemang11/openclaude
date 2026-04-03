package io.openclaude.tools;

public record ToolDescriptor(
        String name,
        boolean concurrencySafe,
        String summary) {
}

