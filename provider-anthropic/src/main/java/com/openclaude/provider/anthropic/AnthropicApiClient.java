package com.openclaude.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.PromptContentBlock;
import com.openclaude.provider.spi.PromptEvent;
import com.openclaude.provider.spi.PromptExecutionContext;
import com.openclaude.provider.spi.PromptMessage;
import com.openclaude.provider.spi.PromptMessageRole;
import com.openclaude.provider.spi.PromptRequest;
import com.openclaude.provider.spi.PromptResult;
import com.openclaude.provider.spi.ProviderHttpException;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderToolDefinition;
import com.openclaude.provider.spi.ReasoningDeltaEvent;
import com.openclaude.provider.spi.TextContentBlock;
import com.openclaude.provider.spi.TextDeltaEvent;
import com.openclaude.provider.spi.ToolCallEvent;
import com.openclaude.provider.spi.ToolResultContentBlock;
import com.openclaude.provider.spi.ToolUseDiscoveredEvent;
import com.openclaude.provider.spi.ToolUseContentBlock;
import com.openclaude.provider.spi.WebSearchResultContentBlock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

final class AnthropicApiClient {
    static final URI MESSAGES_URI = URI.create("https://api.anthropic.com/v1/messages");
    private static final String API_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Function<String, String> environmentReader;

    AnthropicApiClient() {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                new ObjectMapper(),
                System::getenv
        );
    }

    AnthropicApiClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Function<String, String> environmentReader
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.environmentReader = Objects.requireNonNull(environmentReader, "environmentReader");
    }

    PromptResult execute(PromptRequest request) {
        return executeInternal(request, null);
    }

    PromptResult executeStreaming(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
        String apiKey = resolveApiKey(request.context());
        String payload = createPayload(request, true);
        HttpRequest httpRequest = HttpRequest.newBuilder(MESSAGES_URI)
                .timeout(Duration.ofMinutes(5))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("accept", "text/event-stream")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = readBody(response.body());
                throw new ProviderHttpException(
                        ProviderId.ANTHROPIC,
                        response.statusCode(),
                        body,
                        response.headers().map(),
                        "Anthropic streaming request failed: HTTP " + response.statusCode() + " body=" + body
                );
            }
            try (InputStream responseBody = response.body()) {
                return readStream(responseBody, eventConsumer);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Anthropic streaming request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to stream Anthropic Messages API", exception);
        }
    }

    private PromptResult executeInternal(PromptRequest request, Consumer<PromptEvent> eventConsumer) {
        String apiKey = resolveApiKey(request.context());
        String responseBody = sendRequest(apiKey, createPayload(request, false));
        return parseTurnResponse(responseBody, eventConsumer);
    }

    String createPayload(PromptRequest request) {
        return createPayload(request, false);
    }

    String createPayload(PromptRequest request, boolean stream) {
        return createPayload(
                request.context().modelId(),
                buildSystemPrompt(request.messages()),
                buildMessageArray(request.messages()),
                request.tools(),
                request.requiredToolName(),
                stream
        );
    }

    String createPayload(
            String modelId,
            String systemPrompt,
            ArrayNode messages,
            List<ProviderToolDefinition> tools,
            String requiredToolName,
            boolean stream
    ) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", resolveApiModel(modelId));
            payload.put("max_tokens", DEFAULT_MAX_TOKENS);
            if (stream) {
                payload.put("stream", true);
            }

            if (!systemPrompt.isBlank()) {
                payload.put("system", systemPrompt);
            }

            payload.set("messages", messages.deepCopy());
            if (!tools.isEmpty()) {
                payload.set("tools", createTools(tools));
                applyToolChoice(payload, tools, requiredToolName);
            }
            return payload.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create Anthropic request payload", exception);
        }
    }

    String extractText(String responseBody) throws IOException {
        PromptResult result = parseTurnResponse(responseBody, null);
        if (result.text().isBlank()) {
            throw new IllegalStateException("Anthropic response contained no text content");
        }
        return result.text();
    }

    PromptResult readStream(InputStream stream, Consumer<PromptEvent> eventConsumer) throws IOException {
        StreamingAccumulator accumulator = new StreamingAccumulator();
        StringBuilder rawBody = new StringBuilder();
        StringBuilder pendingData = new StringBuilder();
        boolean sawSseEvent = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rawBody.append(line).append('\n');
                if (line.isEmpty()) {
                    if (flushStreamEvent(pendingData, accumulator, eventConsumer)) {
                        sawSseEvent = true;
                    }
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

        if (flushStreamEvent(pendingData, accumulator, eventConsumer)) {
            sawSseEvent = true;
        }

        if (!sawSseEvent) {
            String fallbackBody = rawBody.toString().trim();
            if (fallbackBody.isBlank()) {
                throw new IllegalStateException("Anthropic streaming response was empty");
            }
            return parseTurnResponse(fallbackBody, eventConsumer);
        }

        return new PromptResult(accumulator.text().toString(), accumulator.buildPromptContent());
    }

    String resolveApiKey(PromptExecutionContext context) {
        if (context.authMethod() != AuthMethod.API_KEY) {
            throw new IllegalStateException("Anthropic API client supports only API-key auth.");
        }
        String credentialReference = context.credentialReference();
        if (credentialReference == null || credentialReference.isBlank()) {
            throw new IllegalStateException("Anthropic API key reference is required.");
        }

        if (credentialReference.startsWith("file:")) {
            return readStoredKey(Path.of(credentialReference.substring("file:".length())));
        }

        if (!credentialReference.startsWith("env:")) {
            throw new IllegalStateException("Anthropic API key reference must use env:NAME or file:/path");
        }

        String envVar = credentialReference.substring("env:".length());
        String apiKey = environmentReader.apply(envVar);
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        if (!isEnvironmentVariableName(envVar)) {
            return envVar;
        }
        throw new IllegalStateException("Environment variable is not set or empty: " + envVar);
    }

    String resolveApiModel(String modelId) {
        return switch (modelId) {
            case "anthropic/opus" -> "claude-opus-4-1-20250805";
            case "anthropic/sonnet" -> "claude-sonnet-4-20250514";
            case "anthropic/haiku" -> "claude-3-5-haiku-20241022";
            default -> modelId;
        };
    }

    private ArrayNode buildMessageArray(List<PromptMessage> messages) {
        ArrayNode result = objectMapper.createArrayNode();
        for (PromptMessage message : messages) {
            if (message.role() == PromptMessageRole.SYSTEM) {
                continue;
            }

            ArrayNode content = objectMapper.createArrayNode();
            for (PromptContentBlock block : message.content()) {
                if (block instanceof TextContentBlock textBlock) {
                    if (!textBlock.text().isBlank()) {
                        content.add(objectMapper.createObjectNode()
                                .put("type", "text")
                                .put("text", textBlock.text()));
                    }
                } else if (block instanceof ToolUseContentBlock toolUseBlock) {
                    content.add(objectMapper.createObjectNode()
                            .put("type", "tool_use")
                            .put("id", toolUseBlock.toolUseId())
                            .put("name", toolUseBlock.toolName())
                            .set("input", parseJson(toolUseBlock.inputJson())));
                } else if (block instanceof ToolResultContentBlock toolResultBlock) {
                    ObjectNode toolResult = objectMapper.createObjectNode()
                            .put("type", "tool_result")
                            .put("tool_use_id", toolResultBlock.toolUseId())
                            .put("content", toolResultBlock.text());
                    if (toolResultBlock.isError()) {
                        toolResult.put("is_error", true);
                    }
                    content.add(toolResult);
                }
            }

            if (content.isEmpty()) {
                continue;
            }

            ObjectNode apiMessage = objectMapper.createObjectNode()
                    .put("role", message.role().apiValue());
            apiMessage.set("content", content);
            result.add(apiMessage);
        }
        return result;
    }

    private String sendRequest(String apiKey, String payload) {
        HttpRequest httpRequest = HttpRequest.newBuilder(MESSAGES_URI)
                .timeout(Duration.ofMinutes(2))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderHttpException(
                        ProviderId.ANTHROPIC,
                        response.statusCode(),
                        response.body(),
                        response.headers().map(),
                        "Anthropic API request failed: HTTP " + response.statusCode() + " body=" + response.body()
                );
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Anthropic request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to call Anthropic Messages API", exception);
        }
    }

    private String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private PromptResult parseTurnResponse(String responseBody, Consumer<PromptEvent> eventConsumer) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("content");
            if (!content.isArray()) {
                throw new IllegalStateException("Anthropic response did not include a content array");
            }

            List<PromptContentBlock> blocks = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();

            for (JsonNode part : content) {
                switch (part.path("type").asText("")) {
                    case "text" -> {
                        String value = part.path("text").asText("");
                        if (!value.isBlank()) {
                            if (text.length() > 0) {
                                text.append(System.lineSeparator());
                            }
                            text.append(value);
                            blocks.add(new TextContentBlock(value));
                        }
                    }
                    case "thinking" -> {
                        String value = part.path("thinking").asText("");
                        if (!value.isBlank()) {
                            if (reasoning.length() > 0) {
                                reasoning.append(System.lineSeparator());
                            }
                            reasoning.append(value);
                        }
                    }
                    case "server_tool_use" -> {
                        String toolUseId = part.path("id").asText("web_search");
                        String toolName = part.path("name").asText("");
                        JsonNode input = part.path("input");
                        if (eventConsumer != null && "web_search".equals(toolName)) {
                            String query = input.path("query").asText("");
                            eventConsumer.accept(new ToolCallEvent(
                                    toolUseId,
                                    toolName,
                                    "started",
                                    query,
                                    query
                            ));
                        }
                    }
                    case "tool_use" -> {
                        JsonNode input = part.path("input");
                        blocks.add(new ToolUseContentBlock(
                                part.path("id").asText("tool-" + blocks.size()),
                                part.path("name").asText(""),
                                input.isMissingNode() ? "{}" : input.toString()
                        ));
                    }
                    case "web_search_tool_result" -> {
                        JsonNode contentNode = part.path("content");
                        List<WebSearchResultContentBlock.SearchHit> hits = new ArrayList<>();
                        if (contentNode.isArray()) {
                            for (JsonNode hit : contentNode) {
                                hits.add(new WebSearchResultContentBlock.SearchHit(
                                        hit.path("title").asText(""),
                                        hit.path("url").asText("")
                                ));
                            }
                        }
                        blocks.add(new WebSearchResultContentBlock(
                                part.path("tool_use_id").asText(""),
                                hits
                        ));
                        if (eventConsumer != null) {
                            eventConsumer.accept(new ToolCallEvent(
                                    part.path("tool_use_id").asText("web_search"),
                                    "web_search",
                                    "completed",
                                    "Received " + hits.size() + " search result" + (hits.size() == 1 ? "" : "s") + ".",
                                    ""
                            ));
                        }
                    }
                    default -> {
                    }
                }
            }

            if (eventConsumer != null) {
                if (reasoning.length() > 0) {
                    eventConsumer.accept(new ReasoningDeltaEvent(reasoning.toString(), false));
                }
                if (text.length() > 0) {
                    eventConsumer.accept(new TextDeltaEvent(text.toString()));
                }
            }

            return new PromptResult(text.toString(), blocks);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse Anthropic response", exception);
        }
    }

    private boolean flushStreamEvent(
            StringBuilder pendingData,
            StreamingAccumulator accumulator,
            Consumer<PromptEvent> eventConsumer
    ) throws IOException {
        if (pendingData.length() == 0) {
            return false;
        }

        String eventPayload = pendingData.toString().trim();
        pendingData.setLength(0);
        if (eventPayload.isEmpty()) {
            return false;
        }

        JsonNode event = objectMapper.readTree(eventPayload);
        String eventType = event.path("type").asText("");
        switch (eventType) {
            case "ping", "message_start", "message_delta", "message_stop" -> {
                return true;
            }
            case "content_block_start" -> {
                handleContentBlockStart(event, accumulator, eventConsumer);
                return true;
            }
            case "content_block_delta" -> {
                handleContentBlockDelta(event, accumulator, eventConsumer);
                return true;
            }
            case "content_block_stop" -> {
                handleContentBlockStop(event, accumulator, eventConsumer);
                return true;
            }
            case "error" -> throw new IllegalStateException("Anthropic streaming event error: " + eventPayload);
            default -> {
                return true;
            }
        }
    }

    private void handleContentBlockStart(
            JsonNode event,
            StreamingAccumulator accumulator,
            Consumer<PromptEvent> eventConsumer
    ) {
        int index = event.path("index").asInt(-1);
        JsonNode contentBlock = event.path("content_block");
        if (index < 0 || contentBlock.isMissingNode()) {
            return;
        }

        String type = contentBlock.path("type").asText("");
        switch (type) {
            case "text" -> accumulator.blocks().put(index, StreamingContentBlock.text());
            case "thinking" -> accumulator.blocks().put(index, StreamingContentBlock.thinking());
            case "tool_use" -> accumulator.blocks().put(index, StreamingContentBlock.toolUse(
                    contentBlock.path("id").asText("tool-" + index),
                    contentBlock.path("name").asText("")
            ));
            case "server_tool_use" -> {
                StreamingContentBlock block = StreamingContentBlock.serverToolUse(
                        contentBlock.path("id").asText("tool-" + index),
                        contentBlock.path("name").asText("")
                );
                JsonNode input = contentBlock.path("input");
                if (!input.isMissingNode() && !input.isNull()) {
                    block.inputJson().append(input.toString());
                    maybeEmitWebSearchProgress(block, eventConsumer);
                }
                accumulator.blocks().put(index, block);
            }
            case "web_search_tool_result" -> {
                accumulator.blocks().put(index, StreamingContentBlock.webSearchResult(
                        contentBlock.path("tool_use_id").asText(""),
                        parseWebSearchHits(contentBlock.path("content"))
                ));
                emitWebSearchCompleted(contentBlock.path("tool_use_id").asText(""), contentBlock.path("content"), eventConsumer);
            }
            default -> {
            }
        }
    }

    private void handleContentBlockDelta(
            JsonNode event,
            StreamingAccumulator accumulator,
            Consumer<PromptEvent> eventConsumer
    ) {
        int index = event.path("index").asInt(-1);
        StreamingContentBlock block = accumulator.blocks().get(index);
        if (block == null) {
            return;
        }

        JsonNode delta = event.path("delta");
        String deltaType = delta.path("type").asText("");
        switch (deltaType) {
            case "text_delta" -> {
                String textDelta = delta.path("text").asText("");
                if (!textDelta.isEmpty()) {
                    block.textBuffer().append(textDelta);
                    accumulator.text().append(textDelta);
                    if (eventConsumer != null) {
                        eventConsumer.accept(new TextDeltaEvent(textDelta));
                    }
                }
            }
            case "thinking_delta" -> {
                String thinkingDelta = delta.path("thinking").asText("");
                if (!thinkingDelta.isEmpty()) {
                    block.textBuffer().append(thinkingDelta);
                    if (eventConsumer != null) {
                        eventConsumer.accept(new ReasoningDeltaEvent(thinkingDelta, false));
                    }
                }
            }
            case "input_json_delta" -> {
                String partialJson = delta.path("partial_json").asText("");
                if (!partialJson.isEmpty()) {
                    block.inputJson().append(partialJson);
                    maybeEmitWebSearchProgress(block, eventConsumer);
                }
            }
            case "signature_delta" -> {
            }
            default -> {
            }
        }
    }

    private void handleContentBlockStop(
            JsonNode event,
            StreamingAccumulator accumulator,
            Consumer<PromptEvent> eventConsumer
    ) {
        int index = event.path("index").asInt(-1);
        StreamingContentBlock block = accumulator.blocks().remove(index);
        if (block == null) {
            return;
        }

        switch (block.type()) {
            case "text" -> {
                String value = block.textBuffer().toString();
                if (!value.isBlank()) {
                    accumulator.content().add(new TextContentBlock(value));
                }
            }
            case "tool_use" -> {
                String inputJson = normalizeJsonObject(block.inputJson().toString());
                ToolUseContentBlock contentBlock = new ToolUseContentBlock(
                        block.id(),
                        block.name(),
                        inputJson
                );
                accumulator.content().add(contentBlock);
                if (eventConsumer != null) {
                    eventConsumer.accept(new ToolUseDiscoveredEvent(
                            contentBlock.toolUseId(),
                            contentBlock.toolName(),
                            contentBlock.inputJson()
                    ));
                }
            }
            case "server_tool_use" -> {
                maybeEmitWebSearchProgress(block, eventConsumer);
            }
            case "web_search_tool_result" -> accumulator.content().add(new WebSearchResultContentBlock(
                    block.id(),
                    block.hits()
            ));
            default -> {
            }
        }
    }

    private void maybeEmitWebSearchProgress(
            StreamingContentBlock block,
            Consumer<PromptEvent> eventConsumer
    ) {
        if (eventConsumer == null || !"server_tool_use".equals(block.type()) || !"web_search".equals(block.name())) {
            return;
        }

        String query = extractQuery(block.inputJson().toString());
        if (query.isBlank() || query.equals(block.lastQuery())) {
            return;
        }
        block.lastQuery(query);
        eventConsumer.accept(new ToolCallEvent(
                block.id(),
                "web_search",
                "started",
                query,
                query
        ));
    }

    private void emitWebSearchCompleted(
            String toolUseId,
            JsonNode contentNode,
            Consumer<PromptEvent> eventConsumer
    ) {
        if (eventConsumer == null) {
            return;
        }
        int hitCount = contentNode.isArray() ? contentNode.size() : 0;
        eventConsumer.accept(new ToolCallEvent(
                toolUseId == null || toolUseId.isBlank() ? "web_search" : toolUseId,
                "web_search",
                "completed",
                "Received " + hitCount + " search result" + (hitCount == 1 ? "" : "s") + ".",
                ""
        ));
    }

    private List<WebSearchResultContentBlock.SearchHit> parseWebSearchHits(JsonNode contentNode) {
        List<WebSearchResultContentBlock.SearchHit> hits = new ArrayList<>();
        if (!contentNode.isArray()) {
            return hits;
        }
        for (JsonNode hit : contentNode) {
            hits.add(new WebSearchResultContentBlock.SearchHit(
                    hit.path("title").asText(""),
                    hit.path("url").asText("")
            ));
        }
        return hits;
    }

    private String normalizeJsonObject(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return "{}";
        }
        try {
            JsonNode parsed = objectMapper.readTree(inputJson);
            return parsed.toString();
        } catch (IOException exception) {
            return inputJson;
        }
    }

    private String extractQuery(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return "";
        }
        try {
            JsonNode parsed = objectMapper.readTree(inputJson);
            return parsed.path("query").asText("");
        } catch (IOException exception) {
            return "";
        }
    }

    private ArrayNode createTools(List<ProviderToolDefinition> tools) {
        ArrayNode result = objectMapper.createArrayNode();
        for (ProviderToolDefinition tool : tools) {
            if (tool.isNativeProviderTool()) {
                ObjectNode node = parseJsonObject(tool.providerConfigJson());
                node.put("type", tool.providerType());
                if (!tool.name().isBlank()) {
                    node.put("name", tool.name());
                }
                result.add(node);
                continue;
            }
            result.add(objectMapper.createObjectNode()
                    .put("name", tool.name())
                    .put("description", tool.description())
                    .set("input_schema", parseJson(tool.inputSchemaJson())));
        }
        return result;
    }

    private void applyToolChoice(
            ObjectNode payload,
            List<ProviderToolDefinition> tools,
            String requiredToolName
    ) {
        if (requiredToolName == null || requiredToolName.isBlank()) {
            return;
        }
        boolean knownTool = tools.stream().anyMatch(tool -> requiredToolName.equals(tool.name()));
        if (!knownTool) {
            return;
        }
        payload.set("tool_choice", objectMapper.createObjectNode()
                .put("type", "tool")
                .put("name", requiredToolName));
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse JSON payload: " + json, exception);
        }
    }

    private ObjectNode parseJsonObject(String json) {
        JsonNode node = parseJson(json);
        if (node instanceof ObjectNode objectNode) {
            return objectNode.deepCopy();
        }
        throw new IllegalStateException("Expected JSON object payload but got: " + json);
    }

    private static String buildSystemPrompt(List<PromptMessage> messages) {
        List<String> systemMessages = new ArrayList<>();
        for (PromptMessage message : messages) {
            if (message.role() == PromptMessageRole.SYSTEM && !message.text().isBlank()) {
                systemMessages.add(message.text().strip());
            }
        }
        return String.join("\n\n", systemMessages);
    }

    private static boolean isEnvironmentVariableName(String value) {
        return value != null && value.matches("[A-Z_][A-Z0-9_]*");
    }

    private static String readStoredKey(Path path) {
        try {
            String apiKey = Files.readString(path).trim();
            if (apiKey.isBlank()) {
                throw new IllegalStateException("Stored API key file is empty: " + path);
            }
            return apiKey;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read stored API key: " + path, exception);
        }
    }

    private record StreamingAccumulator(
            StringBuilder text,
            List<PromptContentBlock> content,
            Map<Integer, StreamingContentBlock> blocks
    ) {
        private StreamingAccumulator() {
            this(new StringBuilder(), new ArrayList<>(), new LinkedHashMap<>());
        }

        private List<PromptContentBlock> buildPromptContent() {
            return List.copyOf(content);
        }
    }

    private static final class StreamingContentBlock {
        private final String type;
        private final String id;
        private final String name;
        private final StringBuilder text;
        private final StringBuilder inputJson;
        private final List<WebSearchResultContentBlock.SearchHit> hits;
        private String lastQuery = "";

        private StreamingContentBlock(
                String type,
                String id,
                String name,
                StringBuilder text,
                StringBuilder inputJson,
                List<WebSearchResultContentBlock.SearchHit> hits
        ) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.text = text;
            this.inputJson = inputJson;
            this.hits = hits;
        }

        private static StreamingContentBlock text() {
            return new StreamingContentBlock("text", "", "", new StringBuilder(), new StringBuilder(), List.of());
        }

        private static StreamingContentBlock thinking() {
            return new StreamingContentBlock("thinking", "", "", new StringBuilder(), new StringBuilder(), List.of());
        }

        private static StreamingContentBlock toolUse(String id, String name) {
            return new StreamingContentBlock("tool_use", id, name, new StringBuilder(), new StringBuilder(), List.of());
        }

        private static StreamingContentBlock serverToolUse(String id, String name) {
            return new StreamingContentBlock("server_tool_use", id, name, new StringBuilder(), new StringBuilder(), List.of());
        }

        private static StreamingContentBlock webSearchResult(
                String toolUseId,
                List<WebSearchResultContentBlock.SearchHit> hits
        ) {
            return new StreamingContentBlock(
                    "web_search_tool_result",
                    toolUseId == null ? "" : toolUseId,
                    "web_search",
                    new StringBuilder(),
                    new StringBuilder(),
                    List.copyOf(hits)
            );
        }

        private String type() {
            return type;
        }

        private String id() {
            return id;
        }

        private String name() {
            return name;
        }

        private StringBuilder textBuffer() {
            return text;
        }

        private StringBuilder inputJson() {
            return inputJson;
        }

        private List<WebSearchResultContentBlock.SearchHit> hits() {
            return hits;
        }

        private String lastQuery() {
            return lastQuery;
        }

        private void lastQuery(String value) {
            this.lastQuery = value == null ? "" : value;
        }
    }
}
