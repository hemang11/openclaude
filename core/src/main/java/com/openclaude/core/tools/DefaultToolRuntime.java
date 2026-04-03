package com.openclaude.core.tools;

import java.util.List;

public final class DefaultToolRuntime extends CompositeToolRuntime {
    public DefaultToolRuntime() {
        super(List.of(
                new BashToolRuntime(),
                new GlobToolRuntime(),
                new GrepToolRuntime(),
                new ExitPlanModeToolRuntime(),
                new FileReadToolRuntime(),
                new FileEditToolRuntime(),
                new FileWriteToolRuntime(),
                new WebFetchToolRuntime(),
                new TodoWriteToolRuntime(),
                new WebSearchToolRuntime(),
                new AskUserQuestionToolRuntime(),
                new EnterPlanModeToolRuntime()
        ));
    }
}
