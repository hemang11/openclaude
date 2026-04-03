package com.openclaude.core.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TodoWriteToolRuntimeTest {
    private final TodoWriteToolRuntime runtime = new TodoWriteToolRuntime();

    @Test
    void updatesTodosForTheCurrentSession() {
        ToolExecutionResult result = runtime.execute(new ToolExecutionRequest(
                "tool-1",
                "TodoWrite",
                "{\"todos\":[{\"content\":\"Run tests\",\"status\":\"in_progress\",\"activeForm\":\"Running tests\"}]}"
        ));

        assertFalse(result.error());
        assertTrue(result.text().contains("Todos have been modified successfully"));
        assertTrue(result.sessionEffect().todos() != null);
        assertTrue(result.sessionEffect().todos().size() == 1);
        assertTrue("Run tests".equals(result.sessionEffect().todos().getFirst().content()));
    }

    @Test
    void clearsPersistedTodosWhenAllItemsAreCompleted() {
        ToolExecutionResult result = runtime.execute(new ToolExecutionRequest(
                "tool-1",
                "TodoWrite",
                "{\"todos\":[{\"content\":\"Run tests\",\"status\":\"completed\",\"activeForm\":\"Running tests\"}]}"
        ));

        assertFalse(result.error());
        assertTrue(result.sessionEffect().todos() != null);
        assertTrue(result.sessionEffect().todos().isEmpty());
    }
}
