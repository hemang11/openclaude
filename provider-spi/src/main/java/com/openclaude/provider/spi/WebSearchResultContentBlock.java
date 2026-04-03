package com.openclaude.provider.spi;

import java.util.List;

public record WebSearchResultContentBlock(
        String toolUseId,
        List<SearchHit> hits
) implements PromptContentBlock {
    public WebSearchResultContentBlock {
        toolUseId = toolUseId == null ? "" : toolUseId;
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public record SearchHit(
            String title,
            String url
    ) {
        public SearchHit {
            title = title == null ? "" : title;
            url = url == null ? "" : url;
        }
    }
}
