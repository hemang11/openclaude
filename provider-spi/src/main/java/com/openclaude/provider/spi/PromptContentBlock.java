package com.openclaude.provider.spi;

public sealed interface PromptContentBlock permits
        TextContentBlock,
        ToolUseContentBlock,
        ToolResultContentBlock,
        WebSearchResultContentBlock {
}
