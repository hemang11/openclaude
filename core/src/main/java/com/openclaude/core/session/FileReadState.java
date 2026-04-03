package com.openclaude.core.session;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record FileReadState(
        String content,
        long timestamp,
        Integer offset,
        Integer limit,
        boolean partialView
) {
    public FileReadState {
        content = content == null ? "" : content;
        timestamp = Math.max(0L, timestamp);
    }

    @JsonIgnore
    public boolean isFullView() {
        return !partialView && offset == null && limit == null;
    }
}
