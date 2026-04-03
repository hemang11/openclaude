package com.openclaude.core.tools;

import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.FileReadState;
import com.openclaude.core.session.TodoItem;
import java.util.List;
import java.util.Map;

public record ToolSessionEffect(
        Boolean planMode,
        List<TodoItem> todos,
        Map<String, FileReadState> readFileState
) {
    public ToolSessionEffect {
        todos = todos == null ? null : List.copyOf(todos);
        readFileState = readFileState == null ? null : Map.copyOf(readFileState);
    }

    public static ToolSessionEffect none() {
        return new ToolSessionEffect(null, null, null);
    }

    public ConversationSession apply(ConversationSession session) {
        ConversationSession next = session;
        if (planMode != null) {
            next = next.withPlanMode(planMode);
        }
        if (todos != null) {
            next = next.withTodos(todos);
        }
        if (readFileState != null) {
            next = next.withReadFileState(readFileState);
        }
        return next;
    }
}
