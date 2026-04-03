package com.openclaude.provider.spi;

public sealed interface PromptEvent permits
        PromptStatusEvent,
        ReasoningDeltaEvent,
        TextDeltaEvent,
        ToolCallEvent,
        ToolPermissionEvent,
        ToolUseDiscoveredEvent {
}
