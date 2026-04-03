package com.openclaude.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openclaude.provider.spi.ProviderToolDefinition;
import com.openclaude.provider.spi.PromptEvent;
import com.openclaude.provider.spi.PromptContentBlock;
import com.openclaude.provider.spi.PromptStatusEvent;
import com.openclaude.provider.spi.PromptMessage;
import com.openclaude.provider.spi.PromptRequest;
import com.openclaude.provider.spi.PromptResult;
import com.openclaude.provider.spi.ReasoningDeltaEvent;
import com.openclaude.provider.spi.TextContentBlock;
import com.openclaude.provider.spi.TextDeltaEvent;
import com.openclaude.provider.spi.ToolCallEvent;
import com.openclaude.provider.spi.ToolUseDiscoveredEvent;
import com.openclaude.provider.spi.ToolResultContentBlock;
import com.openclaude.provider.spi.ToolUseContentBlock;
import com.openclaude.provider.spi.WebSearchResultContentBlock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class OpenAiResponsesSupport {
    private static final ObjectMapper TOOL_EVENT_OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_CODEX_INSTRUCTIONS = """
            You are OpenClaude, an interactive coding assistant running in a terminal UI.
            Help with software engineering tasks, be concise, and follow the user's instructions closely.
            """.strip();

    private OpenAiResponsesSupport() {
    }

    static String createPayload(ObjectMapper objectMapper, PromptRequest request, boolean stream) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", request.context().modelId());
            if (stream) {
                payload.put("stream", true);
            }
            applyReasoning(objectMapper, payload, request);

            String instructions = buildSystemInstructions(request.messages());
            if (!instructions.isBlank()) {
                payload.put("instructions", instructions);
            }

            ArrayNode input = payload.putArray("input");
            for (PromptMessage message : request.messages()) {
                if (message.role() == com.openclaude.provider.spi.PromptMessageRole.SYSTEM) {
                    continue;
                }

                if (!message.text().isBlank()) {
                    input.add(objectMapper.createObjectNode()
                            .put("role", message.role().apiValue())
                            .put("content", message.text()));
                }

                for (PromptContentBlock block : message.content()) {
                    if (block instanceof ToolUseContentBlock toolUseBlock) {
                        input.add(objectMapper.createObjectNode()
                                .put("type", "function_call")
                                .put("call_id", toolUseBlock.toolUseId())
                                .put("name", toolUseBlock.toolName())
                                .put("arguments", toolUseBlock.inputJson()));
                    } else if (block instanceof ToolResultContentBlock toolResultBlock) {
                        input.add(objectMapper.createObjectNode()
                                .put("type", "function_call_output")
                                .put("call_id", toolResultBlock.toolUseId())
                                .put("output", toolResultBlock.text()));
                    }
                }
            }

            if (!request.tools().isEmpty()) {
                ArrayNode tools = payload.putArray("tools");
                for (ProviderToolDefinition tool : request.tools()) {
                    if (tool.isNativeProviderTool()) {
                        ObjectNode nativeTool = parseJsonObject(objectMapper, tool.providerConfigJson());
                        nativeTool.put("type", tool.providerType());
                        tools.add(nativeTool);
                        continue;
                    }
                    tools.add(objectMapper.createObjectNode()
                            .put("type", "function")
                            .put("name", tool.name())
                            .put("description", tool.description())
                            .set("parameters", parseJson(objectMapper, tool.inputSchemaJson())));
                }
                applyToolChoice(objectMapper, payload, request);
            }

            return payload.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create OpenAI request payload", exception);
        }
    }

    static String createCodexPayload(ObjectMapper objectMapper, PromptRequest request, boolean stream) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", request.context().modelId());
            payload.put("store", false);
            payload.put("stream", true);
            applyReasoning(objectMapper, payload, request);

            String instructions = buildCodexInstructions(request.messages());
            payload.put("instructions", instructions);

            ArrayNode input = payload.putArray("input");
            for (PromptMessage message : request.messages()) {
                if (message.role() == com.openclaude.provider.spi.PromptMessageRole.SYSTEM) {
                    continue;
                }

                if (!message.text().isBlank()) {
                    input.add(objectMapper.createObjectNode()
                            .put("role", message.role().apiValue())
                            .put("content", message.text()));
                }

                for (PromptContentBlock block : message.content()) {
                    if (block instanceof ToolUseContentBlock toolUseBlock) {
                        input.add(objectMapper.createObjectNode()
                                .put("type", "function_call")
                                .put("call_id", toolUseBlock.toolUseId())
                                .put("name", toolUseBlock.toolName())
                                .put("arguments", toolUseBlock.inputJson()));
                    } else if (block instanceof ToolResultContentBlock toolResultBlock) {
                        input.add(objectMapper.createObjectNode()
                                .put("type", "function_call_output")
                                .put("call_id", toolResultBlock.toolUseId())
                                .put("output", toolResultBlock.text()));
                    }
                }
            }

            if (!request.tools().isEmpty()) {
                ArrayNode tools = payload.putArray("tools");
                for (ProviderToolDefinition tool : request.tools()) {
                    if (tool.isNativeProviderTool()) {
                        ObjectNode nativeTool = parseJsonObject(objectMapper, tool.providerConfigJson());
                        nativeTool.put("type", tool.providerType());
                        tools.add(nativeTool);
                        continue;
                    }
                    tools.add(objectMapper.createObjectNode()
                            .put("type", "function")
                            .put("name", tool.name())
                            .put("description", tool.description())
                            .set("parameters", parseJson(objectMapper, tool.inputSchemaJson())));
                }
                applyToolChoice(objectMapper, payload, request);
            }

            return payload.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create OpenAI Codex request payload", exception);
        }
    }

    static PromptResult readStream(
            ObjectMapper objectMapper,
            InputStream stream,
            Consumer<PromptEvent> eventConsumer
    ) throws IOException {
        StringBuilder completeText = new StringBuilder();
        LinkedHashMap<String, FunctionCallAccumulator> functionCalls = new LinkedHashMap<>();
        List<WebSearchResultContentBlock.SearchHit> citations = new ArrayList<>();
        StringBuilder pendingData = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    flushEvent(objectMapper, pendingData, completeText, functionCalls, citations, eventConsumer);
                    continue;
                }

                if (line.startsWith("data:")) {
                    if (pendingData.length() > 0) {
                        pendingData.append('\n');
                    }
                    pendingData.append(line.substring("data:".length()).stripLeading());
                }
            }
        }

        flushEvent(objectMapper, pendingData, completeText, functionCalls, citations, eventConsumer);
        return new PromptResult(completeText.toString(), buildContentBlocks(completeText.toString(), functionCalls, citations));
    }

    static PromptResult extractResult(ObjectMapper objectMapper, String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode output = root.path("output");
        if (!output.isArray()) {
            throw new IllegalStateException("OpenAI response did not include an output array");
        }

        StringBuilder text = new StringBuilder();
        List<PromptContentBlock> blocks = new ArrayList<>();
        Iterator<JsonNode> outputIterator = output.elements();
        while (outputIterator.hasNext()) {
            JsonNode item = outputIterator.next();
            switch (item.path("type").asText()) {
                case "message" -> {
                    JsonNode content = item.path("content");
                    if (!content.isArray()) {
                        continue;
                    }

                    for (JsonNode part : content) {
                        if ("output_text".equals(part.path("type").asText())) {
                            String value = part.path("text").asText("");
                            if (value.isBlank()) {
                                continue;
                            }
                            if (text.length() > 0) {
                                text.append(System.lineSeparator());
                            }
                            text.append(value);
                            List<WebSearchResultContentBlock.SearchHit> citations = extractCitations(part);
                            if (!citations.isEmpty()) {
                                blocks.add(new WebSearchResultContentBlock("", citations));
                            }
                        }
                    }
                }
                case "function_call", "function" -> {
                    String toolName = extractToolName(item, item);
                    if (toolName.isBlank()) {
                        break;
                    }
                    blocks.add(new ToolUseContentBlock(
                            firstNonBlank(item.path("call_id").asText(""), item.path("id").asText("")),
                            toolName,
                            item.path("arguments").asText("{}")
                    ));
                }
                default -> {
                }
            }
        }

        if (text.length() > 0) {
            blocks.add(0, new TextContentBlock(text.toString()));
        }
        if (blocks.isEmpty()) {
            throw new IllegalStateException("OpenAI response contained no output_text or function_call content");
        }
        return new PromptResult(text.toString(), blocks);
    }

    private static void flushEvent(
            ObjectMapper objectMapper,
            StringBuilder pendingData,
            StringBuilder completeText,
            Map<String, FunctionCallAccumulator> functionCalls,
            List<WebSearchResultContentBlock.SearchHit> citations,
            Consumer<PromptEvent> eventConsumer
    ) throws IOException {
        if (pendingData.length() == 0) {
            return;
        }

        String eventPayload = pendingData.toString().trim();
        pendingData.setLength(0);
        if (eventPayload.isEmpty() || "[DONE]".equals(eventPayload)) {
            return;
        }

        JsonNode event = objectMapper.readTree(eventPayload);
        String eventType = event.path("type").asText("");
        if ("response.output_text.delta".equals(eventType)) {
            String delta = event.path("delta").asText("");
            if (!delta.isEmpty()) {
                completeText.append(delta);
                if (eventConsumer != null) {
                    eventConsumer.accept(new TextDeltaEvent(delta));
                }
            }
            return;
        }

        if (isReasoningDelta(eventType)) {
            String delta = firstNonBlank(event.path("delta").asText(""), event.path("text").asText(""));
            if (!delta.isEmpty() && eventConsumer != null) {
                eventConsumer.accept(new ReasoningDeltaEvent(delta, eventType.contains("summary")));
            }
            return;
        }

        if (isToolLifecycleEvent(eventType, event)) {
            accumulateFunctionCall(eventType, event, functionCalls);
            ToolUseDiscoveredEvent toolUseDiscoveredEvent = toToolUseDiscoveredEvent(eventType, event, functionCalls);
            if (toolUseDiscoveredEvent != null && eventConsumer != null) {
                eventConsumer.accept(toolUseDiscoveredEvent);
            }
            ToolCallEvent toolEvent = toToolCallEvent(eventType, event);
            if (toolEvent != null && eventConsumer != null) {
                eventConsumer.accept(toolEvent);
            }
            return;
        }

        if (isStatusEvent(eventType)) {
            if (eventConsumer != null) {
                eventConsumer.accept(new PromptStatusEvent(humanizeStatus(eventType)));
            }
            return;
        }

        if ("response.output_text.done".equals(eventType) && completeText.length() == 0) {
            String text = event.path("text").asText("");
            if (!text.isEmpty()) {
                completeText.append(text);
                if (eventConsumer != null) {
                    eventConsumer.accept(new TextDeltaEvent(text));
                }
            }
            return;
        }

        if ("response.output_item.done".equals(eventType)) {
            JsonNode item = event.path("item");
            if ("message".equals(item.path("type").asText(""))) {
                JsonNode content = item.path("content");
                if (content.isArray()) {
                    for (JsonNode part : content) {
                        if ("output_text".equals(part.path("type").asText(""))) {
                            mergeCitations(citations, extractCitations(part));
                        }
                    }
                }
            }
        }

        if ("error".equals(eventType)) {
            throw new IllegalStateException("OpenAI streaming event error: " + eventPayload);
        }
    }

    private static void applyReasoning(ObjectMapper objectMapper, ObjectNode payload, PromptRequest request) {
        if (request.effortLevel() == null || request.effortLevel().isBlank()) {
            return;
        }
        payload.set("reasoning", objectMapper.createObjectNode()
                .put("effort", request.effortLevel()));
    }

    private static void accumulateFunctionCall(
            String eventType,
            JsonNode event,
            Map<String, FunctionCallAccumulator> functionCalls
    ) {
        if (!isFunctionToolEvent(eventType, event)) {
            return;
        }

        JsonNode item = event.path("item");
        String callId = firstNonBlank(
                item.path("call_id").asText(""),
                event.path("call_id").asText(""),
                item.path("id").asText(""),
                event.path("item_id").asText("")
        );
        if (callId.isBlank()) {
            return;
        }

        String name = extractToolName(item, event);
        FunctionCallAccumulator accumulator = functionCalls.computeIfAbsent(callId, ignored -> new FunctionCallAccumulator(
                callId,
                name,
                new StringBuilder()
        ));
        if (!name.isBlank()) {
            accumulator.name = name;
        }

        String arguments = firstNonBlank(
                event.path("delta").asText(""),
                event.path("arguments").asText(""),
                item.path("arguments").asText("")
        );
        if (!arguments.isBlank()) {
            if (eventType.endsWith(".delta")) {
                accumulator.arguments.append(arguments);
            } else {
                accumulator.arguments.setLength(0);
                accumulator.arguments.append(arguments);
            }
        }
    }

    private static List<PromptContentBlock> buildContentBlocks(
            String text,
            Map<String, FunctionCallAccumulator> functionCalls,
            List<WebSearchResultContentBlock.SearchHit> citations
    ) {
        List<PromptContentBlock> blocks = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            blocks.add(new TextContentBlock(text));
        }
        if (citations != null && !citations.isEmpty()) {
            blocks.add(new WebSearchResultContentBlock("", List.copyOf(citations)));
        }
        for (FunctionCallAccumulator functionCall : functionCalls.values()) {
            if (functionCall.name == null || functionCall.name.isBlank()) {
                continue;
            }
            blocks.add(new ToolUseContentBlock(
                    functionCall.callId,
                    functionCall.name,
                    functionCall.arguments.length() == 0 ? "{}" : functionCall.arguments.toString()
            ));
        }
        return blocks;
    }

    private static JsonNode parseJson(ObjectMapper objectMapper, String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private static ObjectNode parseJsonObject(ObjectMapper objectMapper, String value) {
        JsonNode parsed = parseJson(objectMapper, value);
        if (parsed instanceof ObjectNode objectNode) {
            return objectNode.deepCopy();
        }
        return objectMapper.createObjectNode();
    }

    private static List<WebSearchResultContentBlock.SearchHit> extractCitations(JsonNode outputTextPart) {
        JsonNode annotations = outputTextPart.path("annotations");
        if (!annotations.isArray()) {
            return List.of();
        }
        List<WebSearchResultContentBlock.SearchHit> hits = new ArrayList<>();
        for (JsonNode annotation : annotations) {
            if (!"url_citation".equals(annotation.path("type").asText(""))) {
                continue;
            }
            JsonNode citation = annotation.path("url_citation").isObject() ? annotation.path("url_citation") : annotation;
            String url = citation.path("url").asText("");
            if (url.isBlank()) {
                continue;
            }
            hits.add(new WebSearchResultContentBlock.SearchHit(
                    firstNonBlank(citation.path("title").asText(""), url),
                    url
            ));
        }
        return List.copyOf(hits);
    }

    private static void mergeCitations(
            List<WebSearchResultContentBlock.SearchHit> destination,
            List<WebSearchResultContentBlock.SearchHit> additions
    ) {
        for (WebSearchResultContentBlock.SearchHit addition : additions) {
            boolean exists = destination.stream().anyMatch(existing -> existing.url().equals(addition.url()));
            if (!exists) {
                destination.add(addition);
            }
        }
    }

    private static void applyToolChoice(ObjectMapper objectMapper, ObjectNode payload, PromptRequest request) {
        String requiredToolName = request.requiredToolName();
        if (requiredToolName == null || requiredToolName.isBlank()) {
            return;
        }
        boolean requestedToolExists = request.tools().stream().anyMatch(tool -> requiredToolName.equals(tool.name()));
        if (!requestedToolExists) {
            return;
        }
        payload.set("tool_choice", objectMapper.createObjectNode()
                .put("type", "function")
                .put("name", requiredToolName));
    }

    private static String buildSystemInstructions(List<PromptMessage> messages) {
        StringBuilder instructions = new StringBuilder();
        for (PromptMessage message : messages) {
            if (message.role() != com.openclaude.provider.spi.PromptMessageRole.SYSTEM) {
                continue;
            }
            String text = message.text();
            if (text.isBlank()) {
                continue;
            }
            if (instructions.length() > 0) {
                instructions.append(System.lineSeparator()).append(System.lineSeparator());
            }
            instructions.append(text.strip());
        }
        return instructions.toString();
    }

    private static boolean isReasoningDelta(String eventType) {
        return eventType.endsWith("reasoning_summary_text.delta")
                || eventType.endsWith("reasoning_text.delta");
    }

    private static boolean isToolLifecycleEvent(String eventType, JsonNode event) {
        if (isFunctionToolEvent(eventType, event)) {
            return true;
        }

        String itemType = event.path("item").path("type").asText("");
        return eventType.contains("function_call")
                || eventType.contains("code_interpreter_call")
                || eventType.contains("file_search_call")
                || eventType.contains("web_search_call")
                || eventType.contains("mcp_call")
                || "code_interpreter_call".equals(itemType)
                || "file_search_call".equals(itemType)
                || "web_search_call".equals(itemType)
                || "mcp_call".equals(itemType);
    }

    private static boolean isStatusEvent(String eventType) {
        return "response.created".equals(eventType)
                || "response.in_progress".equals(eventType)
                || "response.completed".equals(eventType);
    }

    private static ToolCallEvent toToolCallEvent(String eventType, JsonNode event) {
        JsonNode item = event.path("item");
        String toolId = firstNonBlank(
                item.path("call_id").asText(""),
                event.path("call_id").asText(""),
                item.path("id").asText(""),
                event.path("item_id").asText(""),
                event.path("output_index").asText("")
        );
        String toolName = firstNonBlank(extractToolName(item, event), inferToolName(eventType, item));
        String text = firstNonBlank(
                event.path("delta").asText(""),
                event.path("arguments").asText(""),
                event.path("code").asText(""),
                event.path("query").asText(""),
                event.path("status").asText("")
        );
        String command = inferToolCommand(toolName, item, event, text);
        String phase = inferToolPhase(eventType, item);
        if (toolName.isBlank()) {
            return null;
        }
        return new ToolCallEvent(toolId.isBlank() ? toolName : toolId, toolName, phase, text, command);
    }

    private static ToolUseDiscoveredEvent toToolUseDiscoveredEvent(
            String eventType,
            JsonNode event,
            Map<String, FunctionCallAccumulator> functionCalls
    ) {
        if (!"response.output_item.done".equals(eventType) || !isFunctionToolEvent(eventType, event)) {
            return null;
        }

        JsonNode item = event.path("item");
        String toolName = extractToolName(item, event);
        if (toolName.isBlank()) {
            return null;
        }

        String toolUseId = firstNonBlank(
                item.path("call_id").asText(""),
                item.path("id").asText("")
        );
        if (toolUseId.isBlank()) {
            return null;
        }

        FunctionCallAccumulator accumulator = functionCalls.get(toolUseId);
        String inputJson = accumulator != null && accumulator.arguments.length() > 0
                ? accumulator.arguments.toString()
                : firstNonBlank(item.path("arguments").asText(""), "{}");
        return new ToolUseDiscoveredEvent(toolUseId, toolName, inputJson);
    }

    private static String inferToolCommand(String toolName, JsonNode item, JsonNode event, String fallbackText) {
        if ("web_search".equals(toolName)) {
            String directQuery = firstNonBlank(
                    item.path("query").asText(""),
                    item.path("action").path("query").asText(""),
                    event.path("query").asText(""),
                    event.path("action").path("query").asText("")
            );
            if (!directQuery.isBlank()) {
                return directQuery;
            }
        }
        String arguments = firstNonBlank(
                item.path("arguments").asText(""),
                event.path("arguments").asText(""),
                event.path("delta").asText("")
        );
        if (arguments.isBlank()) {
            return fallbackText == null || fallbackText.isBlank() ? null : fallbackText;
        }
        if ("web_search".equals(toolName)) {
            try {
                JsonNode root = TOOL_EVENT_OBJECT_MAPPER.readTree(arguments);
                String query = root.path("query").asText("");
                return query.isBlank() ? arguments : query;
            } catch (Exception exception) {
                return arguments;
            }
        }
        if (!"bash".equals(toolName)) {
            return arguments;
        }
        try {
            JsonNode root = TOOL_EVENT_OBJECT_MAPPER.readTree(arguments);
            String command = root.path("command").asText("");
            return command.isBlank() ? arguments : command;
        } catch (Exception exception) {
            return arguments;
        }
    }

    private static String inferToolName(String eventType, JsonNode item) {
        String itemType = item.path("type").asText("");
        if (eventType.contains("code_interpreter_call") || "code_interpreter_call".equals(itemType)) {
            return "code_interpreter";
        }
        if (eventType.contains("file_search_call") || "file_search_call".equals(itemType)) {
            return "file_search";
        }
        if (eventType.contains("web_search_call") || "web_search_call".equals(itemType)) {
            return "web_search";
        }
        if (eventType.contains("mcp_call") || "mcp_call".equals(itemType)) {
            return "mcp";
        }
        return "";
    }

    private static String inferToolPhase(String eventType, JsonNode item) {
        if ("response.output_item.added".equals(eventType)) {
            return "started";
        }
        if ("response.output_item.done".equals(eventType)) {
            return "completed";
        }
        if ("response.output_item.in_progress".equals(eventType)) {
            return "progress";
        }
        if (eventType.endsWith(".delta")) {
            return "delta";
        }
        if (eventType.endsWith(".done") || eventType.endsWith(".completed")) {
            return "completed";
        }
        if (eventType.endsWith(".in_progress") || eventType.endsWith(".searching")) {
            return "progress";
        }
        if (eventType.endsWith(".added") || eventType.endsWith(".created")) {
            return "started";
        }
        return "status";
    }

    private static boolean isFunctionToolEvent(String eventType, JsonNode event) {
        String itemType = event.path("item").path("type").asText("");
        return eventType.contains("function_call")
                || "function_call".equals(itemType)
                || "function".equals(itemType);
    }

    private static String extractToolName(JsonNode item, JsonNode event) {
        String value = firstNonBlank(
                item.path("name").asText(""),
                item.path("tool_name").asText(""),
                item.path("function").path("name").asText(""),
                item.path("call").path("name").asText(""),
                event.path("name").asText(""),
                event.path("tool_name").asText(""),
                event.path("function").path("name").asText(""),
                event.path("call").path("name").asText("")
        );
        if ("function".equals(value) || "function_call".equals(value)) {
            return "";
        }
        return value;
    }

    private static String humanizeStatus(String eventType) {
        return switch (eventType) {
            case "response.created" -> "Model response created";
            case "response.in_progress" -> "Model response in progress";
            case "response.completed" -> "Model response completed";
            default -> eventType;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String buildCodexInstructions(List<PromptMessage> messages) {
        StringBuilder instructions = new StringBuilder(DEFAULT_CODEX_INSTRUCTIONS);
        for (PromptMessage message : messages) {
            if (message.role() != com.openclaude.provider.spi.PromptMessageRole.SYSTEM) {
                continue;
            }
            if (message.text().isBlank()) {
                continue;
            }
            instructions.append(System.lineSeparator()).append(System.lineSeparator()).append(message.text().trim());
        }
        return instructions.toString();
    }

    private static final class FunctionCallAccumulator {
        private final String callId;
        private String name;
        private final StringBuilder arguments;

        private FunctionCallAccumulator(String callId, String name, StringBuilder arguments) {
            this.callId = callId;
            this.name = name;
            this.arguments = arguments;
        }
    }
}
