package com.openclaude.core.sessionmemory;

import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.ConversationSessionStore;
import com.openclaude.core.session.SessionCompaction;
import com.openclaude.core.session.SessionMemoryState;
import com.openclaude.core.session.SessionMessage;
import com.openclaude.provider.spi.PromptExecutionContext;
import com.openclaude.provider.spi.PromptMessage;
import com.openclaude.provider.spi.PromptMessageRole;
import com.openclaude.provider.spi.PromptRequest;
import com.openclaude.provider.spi.PromptResult;
import com.openclaude.provider.spi.ProviderPlugin;
import com.openclaude.provider.spi.TextDeltaEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class SessionMemoryService {
    private static final int MINIMUM_MESSAGE_TOKENS_TO_INIT = 10_000;
    private static final int MINIMUM_TOKENS_BETWEEN_UPDATE = 5_000;
    private static final int TOOL_CALLS_BETWEEN_UPDATES = 3;
    private static final int MIN_KEEP_TOKENS = 10_000;
    private static final int MIN_KEEP_TEXT_MESSAGES = 5;
    private static final int MAX_KEEP_TOKENS = 40_000;
    private static final Duration EXTRACTION_WAIT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration EXTRACTION_STALE_THRESHOLD = Duration.ofMinutes(1);
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final ConcurrentHashMap<String, CompletableFuture<Void>> IN_FLIGHT = new ConcurrentHashMap<>();

    private final ConversationSessionStore sessionStore;
    private final Path sessionMemoryDirectory;

    public SessionMemoryService(ConversationSessionStore sessionStore) {
        this(sessionStore, com.openclaude.core.config.OpenClaudePaths.sessionMemoryDirectory());
    }

    public SessionMemoryService(ConversationSessionStore sessionStore, Path sessionMemoryDirectory) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.sessionMemoryDirectory = Objects.requireNonNull(sessionMemoryDirectory, "sessionMemoryDirectory");
    }

    public void maybeExtractAsync(
            ConversationSession session,
            ProviderPlugin providerPlugin,
            PromptExecutionContext context,
            String effortLevel
    ) {
        Objects.requireNonNull(session, "session");
        if (!providerPlugin.supportsPromptExecution()) {
            return;
        }

        ConversationSession latest = sessionStore.loadOrCreate(session.sessionId());
        if (!shouldExtract(latest)) {
            return;
        }
        if (extractionAlreadyRunning(latest)) {
            return;
        }

        ConversationSession marked = latest.withSessionMemoryState(latest.sessionMemoryState().markExtractionStarted());
        sessionStore.save(marked);

        CompletableFuture<Void> future = CompletableFuture.runAsync(
                () -> extract(marked.sessionId(), providerPlugin, context, effortLevel),
                EXECUTOR
        ).whenComplete((ignored, throwable) -> IN_FLIGHT.remove(marked.sessionId()));
        IN_FLIGHT.put(marked.sessionId(), future);
    }

    public void waitForPendingExtraction(String sessionId) {
        CompletableFuture<Void> future = IN_FLIGHT.get(sessionId);
        if (future == null) {
            return;
        }
        try {
            future.get(EXTRACTION_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // Compaction can safely continue with the last completed notes snapshot.
        }
    }

    public ConversationSession awaitExtraction(ConversationSessionStore ignoredSessionStore, String sessionId) {
        waitForPendingExtraction(sessionId);
        return sessionStore.loadOrCreate(sessionId);
    }

    public Optional<CompactionCandidate> tryBuildCompactionCandidate(ConversationSession session) {
        Objects.requireNonNull(session, "session");
        return buildCompactCandidateInternal(session).map(candidate -> {
            TruncationResult truncation = truncateForCompact(candidate.memoryContent());
            String summaryText = buildCompactSummaryMessage(truncation.content());
            if (truncation.truncated()) {
                summaryText += System.lineSeparator() + System.lineSeparator()
                        + "Some session memory sections were truncated for length. The full session memory can be viewed at: "
                        + memoryFile(session.sessionId());
            }
            int preCompactTokenCount = estimateMessageTokens(SessionCompaction.messagesAfterCompactBoundary(session.messages()));
            return new CompactionCandidate(summaryText, candidate.messagesToKeep(), preCompactTokenCount);
        });
    }

    public Optional<CompactCandidate> tryBuildCompactCandidate(
            ConversationSessionStore ignoredSessionStore,
            ConversationSession session
    ) {
        Objects.requireNonNull(session, "session");
        return buildCompactCandidateInternal(session);
    }

    public TruncationResult truncateForCompact(String content) {
        SessionMemoryPrompts.TruncationResult truncation = SessionMemoryPrompts.truncateForCompact(content);
        return new TruncationResult(truncation.content(), truncation.truncated());
    }

    public Path memoryFile(String sessionId) {
        return sessionMemoryDirectory.resolve("sessions").resolve(sessionId + ".md");
    }

    private Optional<CompactCandidate> buildCompactCandidateInternal(ConversationSession session) {
        String sessionMemory = readMemoryFile(session.sessionId());
        if (sessionMemory == null || SessionMemoryPrompts.isTemplateOnly(sessionMemoryDirectory, sessionMemory)) {
            return Optional.empty();
        }

        List<SessionMessage> activeMessages = SessionCompaction.messagesAfterCompactBoundary(session.messages());
        if (activeMessages.isEmpty()) {
            return Optional.empty();
        }

        int lastSummarizedIndex = resolveLastSummarizedIndex(activeMessages, session.sessionMemoryState().lastSummarizedMessageId());
        if (lastSummarizedIndex == Integer.MIN_VALUE) {
            return Optional.empty();
        }

        int startIndex = calculateMessagesToKeepIndex(activeMessages, lastSummarizedIndex);
        List<SessionMessage> messagesToKeep = activeMessages.subList(startIndex, activeMessages.size()).stream()
                .filter(message -> !(message instanceof SessionMessage.CompactBoundaryMessage))
                .toList();
        String anchorId = session.sessionMemoryState().lastSummarizedMessageId();
        boolean usePreservedSegment = anchorId != null && !anchorId.isBlank() && !messagesToKeep.isEmpty();

        return Optional.of(new CompactCandidate(sessionMemory, anchorId, messagesToKeep, usePreservedSegment));
    }

    private void extract(
            String sessionId,
            ProviderPlugin providerPlugin,
            PromptExecutionContext context,
            String effortLevel
    ) {
        ConversationSession latest = sessionStore.loadOrCreate(sessionId);
        try {
            if (!shouldExtract(latest)) {
                sessionStore.save(latest.withSessionMemoryState(latest.sessionMemoryState().markExtractionFailed()));
                return;
            }

            Path memoryFile = memoryFile(sessionId);
            String currentNotes = ensureMemoryFile(memoryFile);
            List<SessionMessage> activeMessages = SessionCompaction.messagesAfterCompactBoundary(latest.messages());
            List<SessionMessage> unsummarizedMessages = unsummarizedMessages(activeMessages, latest.sessionMemoryState().lastSummarizedMessageId());
            if (unsummarizedMessages.isEmpty()) {
                sessionStore.save(latest.withSessionMemoryState(
                        latest.sessionMemoryState().markExtractionCompleted(
                                estimateMessageTokens(activeMessages),
                                activeMessages.getLast().id()
                        )
                ));
                return;
            }

            List<PromptMessage> promptMessages = new ArrayList<>();
            promptMessages.addAll(toPromptMessages(unsummarizedMessages));
            promptMessages.add(new PromptMessage(
                    PromptMessageRole.USER,
                    SessionMemoryPrompts.buildUpdatePrompt(sessionMemoryDirectory, currentNotes, memoryFile.toString())
            ));

            StringBuilder streamedText = new StringBuilder();
            PromptRequest promptRequest = new PromptRequest(
                    context,
                    promptMessages,
                    List.of(),
                    providerPlugin.supportsStreaming(),
                    null,
                    effortLevel
            );
            PromptResult promptResult;
            if (providerPlugin.supportsStreaming()) {
                promptResult = providerPlugin.executePromptStream(promptRequest, event -> {
                    if (event instanceof TextDeltaEvent textDeltaEvent && !textDeltaEvent.text().isEmpty()) {
                        streamedText.append(textDeltaEvent.text());
                    }
                });
            } else {
                promptResult = providerPlugin.executePrompt(new PromptRequest(
                        context,
                        promptMessages,
                        List.of(),
                        false,
                        null,
                        effortLevel
                ));
            }

            String updatedNotes = normalizeNotes(streamedText.length() == 0 ? promptResult.text() : streamedText.toString(), currentNotes);
            Files.createDirectories(memoryFile.getParent());
            Files.writeString(memoryFile, updatedNotes);

            ConversationSession refreshed = sessionStore.loadOrCreate(sessionId);
            List<SessionMessage> refreshedActiveMessages = SessionCompaction.messagesAfterCompactBoundary(refreshed.messages());
            String lastMessageId = refreshedActiveMessages.isEmpty() ? refreshed.sessionMemoryState().lastSummarizedMessageId() : refreshedActiveMessages.getLast().id();
            sessionStore.save(refreshed.withSessionMemoryState(
                    refreshed.sessionMemoryState().markExtractionCompleted(
                            estimateMessageTokens(refreshedActiveMessages),
                            lastMessageId
                    )
            ));
        } catch (Exception ignored) {
            ConversationSession failedSession = sessionStore.loadOrCreate(sessionId);
            sessionStore.save(failedSession.withSessionMemoryState(failedSession.sessionMemoryState().markExtractionFailed()));
        }
    }

    private boolean shouldExtract(ConversationSession session) {
        List<SessionMessage> activeMessages = SessionCompaction.messagesAfterCompactBoundary(session.messages());
        if (activeMessages.isEmpty()) {
            return false;
        }

        int currentTokenCount = estimateMessageTokens(activeMessages);
        SessionMemoryState memoryState = session.sessionMemoryState();
        SessionMemoryState effectiveState = memoryState;
        if (!memoryState.initialized()) {
            if (currentTokenCount < MINIMUM_MESSAGE_TOKENS_TO_INIT) {
                return false;
            }
            effectiveState = memoryState.markInitialized();
            sessionStore.save(session.withSessionMemoryState(effectiveState));
        }

        boolean hasMetTokenThreshold = currentTokenCount - effectiveState.tokensAtLastExtraction() >= MINIMUM_TOKENS_BETWEEN_UPDATE;
        if (!hasMetTokenThreshold) {
            return false;
        }

        int toolCallsSinceLastUpdate = countToolCallsSince(activeMessages, effectiveState.lastSummarizedMessageId());
        boolean hasMetToolThreshold = toolCallsSinceLastUpdate >= TOOL_CALLS_BETWEEN_UPDATES;
        boolean hasToolCallsInLastTurn = hasToolCallsInLastAssistantTurn(activeMessages);
        return hasMetToolThreshold || !hasToolCallsInLastTurn;
    }

    private boolean extractionAlreadyRunning(ConversationSession session) {
        CompletableFuture<Void> future = IN_FLIGHT.get(session.sessionId());
        if (future != null && !future.isDone()) {
            return true;
        }
        Instant startedAt = session.sessionMemoryState().extractionStartedAt();
        return startedAt != null && startedAt.isAfter(Instant.now().minus(EXTRACTION_STALE_THRESHOLD));
    }

    private String ensureMemoryFile(Path memoryFile) throws IOException {
        Files.createDirectories(memoryFile.getParent());
        if (!Files.exists(memoryFile)) {
            Files.writeString(memoryFile, SessionMemoryPrompts.loadTemplate(sessionMemoryDirectory));
        }
        return Files.readString(memoryFile);
    }

    private String readMemoryFile(String sessionId) {
        Path memoryFile = memoryFile(sessionId);
        try {
            if (!Files.exists(memoryFile)) {
                return null;
            }
            return Files.readString(memoryFile);
        } catch (IOException exception) {
            return null;
        }
    }

    private static List<PromptMessage> toPromptMessages(List<SessionMessage> messages) {
        List<PromptMessage> promptMessages = new ArrayList<>();
        for (SessionMessage message : messages) {
            if (message instanceof SessionMessage.ThinkingMessage
                    || message instanceof SessionMessage.ToolInvocationMessage
                    || message instanceof SessionMessage.ProgressMessage
                    || message instanceof SessionMessage.TombstoneMessage
                    || message instanceof SessionMessage.CompactBoundaryMessage) {
                continue;
            }
            promptMessages.addAll(message.toPromptMessages());
        }
        return List.copyOf(promptMessages);
    }

    private static List<SessionMessage> unsummarizedMessages(List<SessionMessage> activeMessages, String lastSummarizedMessageId) {
        if (lastSummarizedMessageId == null || lastSummarizedMessageId.isBlank()) {
            return activeMessages;
        }
        for (int index = 0; index < activeMessages.size(); index += 1) {
            if (lastSummarizedMessageId.equals(activeMessages.get(index).id())) {
                return activeMessages.subList(index + 1, activeMessages.size());
            }
        }
        return activeMessages;
    }

    private static String normalizeNotes(String responseText, String currentNotes) {
        String normalized = responseText == null ? "" : responseText.trim();
        if (normalized.isBlank()) {
            return currentNotes;
        }
        return normalized;
    }

    private static int resolveLastSummarizedIndex(List<SessionMessage> messages, String lastSummarizedMessageId) {
        if (lastSummarizedMessageId == null || lastSummarizedMessageId.isBlank()) {
            return messages.size() - 1;
        }
        for (int index = 0; index < messages.size(); index += 1) {
            if (lastSummarizedMessageId.equals(messages.get(index).id())) {
                return index;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static int calculateMessagesToKeepIndex(List<SessionMessage> messages, int lastSummarizedIndex) {
        if (messages.isEmpty()) {
            return 0;
        }

        int startIndex = lastSummarizedIndex >= 0 ? lastSummarizedIndex + 1 : messages.size();
        int totalTokens = 0;
        int textMessageCount = 0;
        for (int index = startIndex; index < messages.size(); index += 1) {
            SessionMessage message = messages.get(index);
            totalTokens += estimateMessageTokens(List.of(message));
            if (hasTextContent(message)) {
                textMessageCount += 1;
            }
        }

        if (totalTokens >= MAX_KEEP_TOKENS) {
            return adjustIndexToPreserveInvariants(messages, startIndex);
        }
        if (totalTokens >= MIN_KEEP_TOKENS && textMessageCount >= MIN_KEEP_TEXT_MESSAGES) {
            return adjustIndexToPreserveInvariants(messages, startIndex);
        }

        int floor = 0;
        for (int index = messages.size() - 1; index >= 0; index -= 1) {
            if (messages.get(index) instanceof SessionMessage.CompactBoundaryMessage) {
                floor = index + 1;
                break;
            }
        }

        for (int index = startIndex - 1; index >= floor; index -= 1) {
            SessionMessage message = messages.get(index);
            totalTokens += estimateMessageTokens(List.of(message));
            if (hasTextContent(message)) {
                textMessageCount += 1;
            }
            startIndex = index;
            if (totalTokens >= MAX_KEEP_TOKENS) {
                break;
            }
            if (totalTokens >= MIN_KEEP_TOKENS && textMessageCount >= MIN_KEEP_TEXT_MESSAGES) {
                break;
            }
        }

        return adjustIndexToPreserveInvariants(messages, startIndex);
    }

    private static int adjustIndexToPreserveInvariants(List<SessionMessage> messages, int startIndex) {
        if (startIndex <= 0 || startIndex >= messages.size()) {
            return Math.max(0, Math.min(startIndex, messages.size()));
        }

        int adjustedIndex = startIndex;
        java.util.LinkedHashSet<String> missingToolUseIds = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> presentToolUseIds = new java.util.LinkedHashSet<>();

        for (int index = startIndex; index < messages.size(); index += 1) {
            SessionMessage message = messages.get(index);
            if (message instanceof SessionMessage.ToolResultMessage toolResultMessage) {
                missingToolUseIds.add(toolResultMessage.toolUseId());
            }
            if (message instanceof SessionMessage.AssistantMessage assistantMessage) {
                assistantMessage.toolUses().stream()
                        .map(com.openclaude.provider.spi.ToolUseContentBlock::toolUseId)
                        .forEach(presentToolUseIds::add);
            }
        }
        missingToolUseIds.removeAll(presentToolUseIds);

        if (!missingToolUseIds.isEmpty()) {
            for (int index = adjustedIndex - 1; index >= 0 && !missingToolUseIds.isEmpty(); index -= 1) {
                SessionMessage message = messages.get(index);
                if (message instanceof SessionMessage.AssistantMessage assistantMessage) {
                    boolean found = false;
                    for (var toolUse : assistantMessage.toolUses()) {
                        if (missingToolUseIds.remove(toolUse.toolUseId())) {
                            found = true;
                        }
                    }
                    if (found) {
                        adjustedIndex = includeLeadingThinking(messages, index);
                    }
                }
            }
        }

        return adjustedIndex;
    }

    private static int includeLeadingThinking(List<SessionMessage> messages, int assistantIndex) {
        int adjustedIndex = assistantIndex;
        while (adjustedIndex > 0 && messages.get(adjustedIndex - 1) instanceof SessionMessage.ThinkingMessage) {
            adjustedIndex -= 1;
        }
        return adjustedIndex;
    }

    private static boolean hasTextContent(SessionMessage message) {
        if (message instanceof SessionMessage.UserMessage userMessage) {
            return !userMessage.text().isBlank();
        }
        if (message instanceof SessionMessage.AssistantMessage assistantMessage) {
            return !assistantMessage.text().isBlank();
        }
        if (message instanceof SessionMessage.ToolResultMessage toolResultMessage) {
            return !toolResultMessage.text().isBlank();
        }
        return false;
    }

    private static int countToolCallsSince(List<SessionMessage> messages, String lastSummarizedMessageId) {
        boolean started = lastSummarizedMessageId == null || lastSummarizedMessageId.isBlank();
        int toolCalls = 0;
        for (SessionMessage message : messages) {
            if (!started) {
                if (lastSummarizedMessageId.equals(message.id())) {
                    started = true;
                }
                continue;
            }
            if (message instanceof SessionMessage.AssistantMessage assistantMessage) {
                toolCalls += assistantMessage.toolUses().size();
            }
        }
        return toolCalls;
    }

    private static boolean hasToolCallsInLastAssistantTurn(List<SessionMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index -= 1) {
            SessionMessage message = messages.get(index);
            if (message instanceof SessionMessage.AssistantMessage assistantMessage) {
                return !assistantMessage.toolUses().isEmpty();
            }
        }
        return false;
    }

    private static int estimateMessageTokens(List<SessionMessage> messages) {
        int characters = 0;
        for (SessionMessage message : messages) {
            if (message == null) {
                continue;
            }
            characters += message.text() == null ? 0 : message.text().length();
            if (message instanceof SessionMessage.AssistantMessage assistantMessage) {
                for (var toolUse : assistantMessage.toolUses()) {
                    characters += (toolUse.toolName() == null ? 0 : toolUse.toolName().length());
                    characters += (toolUse.inputJson() == null ? 0 : toolUse.inputJson().length());
                }
            }
        }
        return Math.max(1, (int) Math.ceil(characters / 3.0));
    }

    private static String buildCompactSummaryMessage(String summary) {
        return """
                This session is being continued from a previous conversation that ran out of context. The summary below covers the earlier portion of the conversation.

                %s
                """.formatted(summary == null ? "" : summary.trim()).trim();
    }

    public record CompactionCandidate(
            String summaryText,
            List<SessionMessage> messagesToKeep,
            int preCompactTokenCount
    ) {
    }

    public record CompactCandidate(
            String memoryContent,
            String anchorId,
            List<SessionMessage> messagesToKeep,
            boolean usePreservedSegment
    ) {
    }

    public record TruncationResult(
            String content,
            boolean truncated
    ) {
    }
}
