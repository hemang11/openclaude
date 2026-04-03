package io.openclaude.mcp;

public record McpServerDescriptor(
        String name,
        String transport,
        String target) {
}
