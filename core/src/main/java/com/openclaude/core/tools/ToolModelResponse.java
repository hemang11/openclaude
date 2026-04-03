package com.openclaude.core.tools;

import com.openclaude.provider.spi.PromptContentBlock;
import java.util.List;

public record ToolModelResponse(
        String text,
        List<PromptContentBlock> contentBlocks
) {
    public ToolModelResponse {
        text = text == null ? "" : text;
        contentBlocks = contentBlocks == null ? List.of() : List.copyOf(contentBlocks);
    }
}
