package com.openclaude.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.openclaude.core.session.TodoItem;
import com.openclaude.provider.spi.ProviderToolDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class TodoWriteToolRuntime extends AbstractSingleToolRuntime {
    private static final ProviderToolDefinition DEFINITION = new ProviderToolDefinition(
            "TodoWrite",
            "Update the todo list for the current session. Use it proactively to track progress and pending tasks.",
            """
            {
              "type": "object",
              "properties": {
                "todos": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "content": {"type": "string"},
                      "status": {"type": "string", "enum": ["pending", "in_progress", "completed"]},
                      "activeForm": {"type": "string"}
                    },
                    "required": ["content", "status", "activeForm"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["todos"],
              "additionalProperties": false
            }
            """
    );
    private static final String RESULT_TEXT =
            "Todos have been modified successfully. Ensure that you continue to use the todo list to track your progress. "
                    + "Please proceed with the current tasks if applicable";

    public TodoWriteToolRuntime() {
        super(DEFINITION);
    }

    @Override
    protected ToolExecutionResult executeSingle(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer
    ) {
        JsonNode input = ToolJson.parse(request.inputJson());
        JsonNode todosNode = input.path("todos");
        if (!todosNode.isArray()) {
            emit(updateConsumer, request, "failed", "Missing required TodoWrite.todos.", "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Missing required TodoWrite.todos.", true);
        }

        List<TodoItem> todos = new ArrayList<>();
        for (JsonNode todoNode : todosNode) {
            String content = todoNode.path("content").asText("");
            String status = todoNode.path("status").asText("");
            String activeForm = todoNode.path("activeForm").asText("");
            if (content.isBlank() || activeForm.isBlank() || status.isBlank()) {
                emit(updateConsumer, request, "failed", "Each todo requires content, status, and activeForm.", "", true);
                return new ToolExecutionResult(
                        request.toolUseId(),
                        request.toolName(),
                        "Each todo requires content, status, and activeForm.",
                        true
                );
            }
            todos.add(new TodoItem(content, status, activeForm));
        }

        List<TodoItem> persistedTodos = todos.stream().allMatch(todo -> "completed".equals(todo.status())) ? List.of() : todos;
        emit(updateConsumer, request, "completed", RESULT_TEXT, "", false);
        return new ToolExecutionResult(
                request.toolUseId(),
                request.toolName(),
                RESULT_TEXT,
                false,
                new ToolSessionEffect(null, persistedTodos, null)
        );
    }
}
