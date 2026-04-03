package com.openclaude.core.query;

public record QueryTurnResult(
        String sessionId,
        String modelId,
        String text
) {
}
