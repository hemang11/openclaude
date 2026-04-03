package com.openclaude.core.session;

import java.time.Instant;

public record SessionMemoryState(
        boolean initialized,
        int tokensAtLastExtraction,
        String lastSummarizedMessageId,
        Instant extractionStartedAt,
        Instant lastExtractionAt
) {
    public SessionMemoryState {
        tokensAtLastExtraction = Math.max(0, tokensAtLastExtraction);
        lastSummarizedMessageId = normalize(lastSummarizedMessageId);
    }

    public static SessionMemoryState empty() {
        return new SessionMemoryState(false, 0, null, null, null);
    }

    public SessionMemoryState markInitialized() {
        return new SessionMemoryState(true, tokensAtLastExtraction, lastSummarizedMessageId, extractionStartedAt, lastExtractionAt);
    }

    public SessionMemoryState markExtractionStarted() {
        return new SessionMemoryState(initialized, tokensAtLastExtraction, lastSummarizedMessageId, Instant.now(), lastExtractionAt);
    }

    public SessionMemoryState markExtractionCompleted(int tokenCount, String summarizedMessageId) {
        return new SessionMemoryState(true, tokenCount, summarizedMessageId, null, Instant.now());
    }

    public SessionMemoryState markExtractionFailed() {
        return new SessionMemoryState(initialized, tokensAtLastExtraction, lastSummarizedMessageId, null, lastExtractionAt);
    }

    public SessionMemoryState afterCompaction(String compactSummaryMessageId) {
        return new SessionMemoryState(true, 0, compactSummaryMessageId, null, Instant.now());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
