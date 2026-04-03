package com.openclaude.core.session;

public record TodoItem(
        String content,
        String status,
        String activeForm
) {
    public TodoItem {
        content = content == null ? "" : content;
        status = status == null ? "pending" : status;
        activeForm = activeForm == null ? "" : activeForm;
    }
}
