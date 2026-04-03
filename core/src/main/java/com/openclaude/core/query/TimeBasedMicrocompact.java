package com.openclaude.core.query;

import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.SessionCompaction;
import com.openclaude.core.session.SessionMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class TimeBasedMicrocompact {
    static final String CLEARED_MESSAGE = "[Old tool result content cleared]";
    private static final Duration GAP_THRESHOLD = Duration.ofMinutes(60);
    private static final int KEEP_RECENT = 5;
    private static final Set<String> COMPACTABLE_TOOLS = Set.of(
            "Read",
            "bash",
            "Grep",
            "Glob",
            "WebSearch",
            "WebFetch",
            "Edit",
            "Write"
    );

    Result maybeApply(ConversationSession session) {
        return maybeApply(session, Instant.now());
    }

    Result maybeApply(ConversationSession session, Instant now) {
        List<SessionMessage> activeMessages = SessionCompaction.messagesAfterCompactBoundary(session.messages());
        SessionMessage.AssistantMessage lastAssistant = findLastAssistant(activeMessages);
        if (lastAssistant == null) {
            return new Result(session, false, 0, List.of());
        }
        if (!lastAssistant.createdAt().isBefore(now.minus(GAP_THRESHOLD))) {
            return new Result(session, false, 0, List.of());
        }

        List<String> compactableToolIds = collectCompactableToolIds(activeMessages);
        if (compactableToolIds.size() <= KEEP_RECENT) {
            return new Result(session, false, 0, List.of());
        }

        Set<String> keepSet = new LinkedHashSet<>(compactableToolIds.subList(
                Math.max(0, compactableToolIds.size() - KEEP_RECENT),
                compactableToolIds.size()
        ));
        Set<String> clearSet = new LinkedHashSet<>();
        for (String toolId : compactableToolIds) {
            if (!keepSet.contains(toolId)) {
                clearSet.add(toolId);
            }
        }
        if (clearSet.isEmpty()) {
            return new Result(session, false, 0, List.of());
        }

        int tokensSaved = 0;
        List<SessionMessage> updatedMessages = new ArrayList<>(session.messages().size());
        boolean touched = false;
        for (SessionMessage message : session.messages()) {
            if (message instanceof SessionMessage.ToolResultMessage toolResultMessage
                    && clearSet.contains(toolResultMessage.toolUseId())
                    && !CLEARED_MESSAGE.equals(toolResultMessage.text())) {
                tokensSaved += Math.max(0, toolResultMessage.text().length() / 4);
                updatedMessages.add(new SessionMessage.ToolResultMessage(
                        toolResultMessage.id(),
                        toolResultMessage.createdAt(),
                        toolResultMessage.toolUseId(),
                        toolResultMessage.toolName(),
                        CLEARED_MESSAGE,
                        toolResultMessage.isError()
                ));
                touched = true;
                continue;
            }
            updatedMessages.add(message);
        }

        if (!touched) {
            return new Result(session, false, 0, List.copyOf(clearSet));
        }
        return new Result(session.withMessages(updatedMessages), true, tokensSaved, List.copyOf(clearSet));
    }

    private static SessionMessage.AssistantMessage findLastAssistant(List<SessionMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index -= 1) {
            if (messages.get(index) instanceof SessionMessage.AssistantMessage assistantMessage) {
                return assistantMessage;
            }
        }
        return null;
    }

    private static List<String> collectCompactableToolIds(List<SessionMessage> messages) {
        List<String> toolIds = new ArrayList<>();
        for (SessionMessage message : messages) {
            if (message instanceof SessionMessage.AssistantMessage assistantMessage) {
                assistantMessage.toolUses().stream()
                        .filter(toolUse -> COMPACTABLE_TOOLS.contains(toolUse.toolName()))
                        .map(com.openclaude.provider.spi.ToolUseContentBlock::toolUseId)
                        .forEach(toolIds::add);
            }
        }
        return List.copyOf(toolIds);
    }

    record Result(
            ConversationSession session,
            boolean applied,
            int tokensSaved,
            List<String> compactedToolIds
    ) {
    }
}
