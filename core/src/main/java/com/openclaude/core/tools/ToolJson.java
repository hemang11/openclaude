package com.openclaude.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class ToolJson {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ToolJson() {
    }

    static JsonNode parse(String inputJson) {
        try {
            return OBJECT_MAPPER.readTree(inputJson == null ? "{}" : inputJson);
        } catch (Exception exception) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    static String string(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    static Integer integer(JsonNode node, String field) {
        return node.has(field) && !node.path(field).isNull() ? node.path(field).asInt() : null;
    }

    static Boolean bool(JsonNode node, String field) {
        return node.has(field) && !node.path(field).isNull() ? node.path(field).asBoolean() : null;
    }
}
