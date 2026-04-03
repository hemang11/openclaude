package com.openclaude.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultToolRuntimeTest {
    @Test
    void exposesClaudeBackedDefaultToolSet() {
        DefaultToolRuntime runtime = new DefaultToolRuntime();

        assertEquals(
                List.of("bash", "Glob", "Grep", "ExitPlanMode", "Read", "Edit", "Write", "WebFetch", "TodoWrite", "WebSearch", "AskUserQuestion", "EnterPlanMode"),
                runtime.toolDefinitions().stream().map(definition -> definition.name()).toList()
        );
    }
}
