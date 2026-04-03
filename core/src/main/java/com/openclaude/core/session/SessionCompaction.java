package com.openclaude.core.session;

import java.util.List;
import java.util.OptionalInt;

public final class SessionCompaction {
    private SessionCompaction() {
    }

    public static int findLastCompactBoundaryIndex(List<SessionMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index -= 1) {
            if (messages.get(index) instanceof SessionMessage.CompactBoundaryMessage) {
                return index;
            }
        }
        return -1;
    }

    public static List<SessionMessage> messagesAfterCompactBoundary(List<SessionMessage> messages) {
        int boundaryIndex = findLastCompactBoundaryIndex(messages);
        if (boundaryIndex < 0) {
            return List.copyOf(messages);
        }
        SessionMessage.CompactBoundaryMessage boundaryMessage =
                (SessionMessage.CompactBoundaryMessage) messages.get(boundaryIndex);
        SessionMessage.PreservedSegment preservedSegment = boundaryMessage.preservedSegment();
        if (preservedSegment == null) {
            return List.copyOf(messages.subList(boundaryIndex, messages.size()));
        }

        OptionalInt headIndex = findMessageIndex(messages, preservedSegment.headId(), boundaryIndex);
        OptionalInt tailIndex = findMessageIndex(messages, preservedSegment.tailId(), boundaryIndex);
        if (headIndex.isEmpty() || tailIndex.isEmpty() || headIndex.getAsInt() > tailIndex.getAsInt()) {
            return List.copyOf(messages.subList(boundaryIndex, messages.size()));
        }

        int firstNonSummaryIndex = firstNonSummaryIndex(messages, boundaryIndex + 1);
        List<SessionMessage> projected = new java.util.ArrayList<>();
        projected.add(messages.get(boundaryIndex));
        projected.addAll(messages.subList(boundaryIndex + 1, firstNonSummaryIndex));
        projected.addAll(messages.subList(headIndex.getAsInt(), tailIndex.getAsInt() + 1));
        projected.addAll(messages.subList(firstNonSummaryIndex, messages.size()));
        return List.copyOf(projected);
    }

    public static SegmentSlice sliceForCompaction(List<SessionMessage> messages) {
        List<SessionMessage> activeMessages = messagesAfterCompactBoundary(messages);
        if (activeMessages.isEmpty()) {
            return new SegmentSlice(List.of(), List.of());
        }

        int start = 0;
        if (!activeMessages.isEmpty() && activeMessages.getFirst() instanceof SessionMessage.CompactBoundaryMessage) {
            start = 1;
        }
        while (start < activeMessages.size()) {
            SessionMessage message = activeMessages.get(start);
            if (message instanceof SessionMessage.UserMessage userMessage && userMessage.compactSummary()) {
                start += 1;
                continue;
            }
            break;
        }

        return new SegmentSlice(
                List.copyOf(activeMessages.subList(0, start)),
                List.copyOf(activeMessages.subList(start, activeMessages.size()))
        );
    }

    public static SessionMessage.CompactBoundaryMessage annotateBoundaryWithPreservedSegment(
            SessionMessage.CompactBoundaryMessage boundary,
            String anchorId,
            List<SessionMessage> messagesToKeep
    ) {
        if (boundary == null || messagesToKeep == null || messagesToKeep.isEmpty()) {
            return boundary;
        }
        String normalizedAnchor = anchorId == null ? "" : anchorId;
        if (normalizedAnchor.isBlank()) {
            return boundary;
        }
        return new SessionMessage.CompactBoundaryMessage(
                boundary.id(),
                boundary.createdAt(),
                boundary.text(),
                boundary.trigger(),
                boundary.preTokens(),
                boundary.messagesSummarized(),
                new SessionMessage.PreservedSegment(
                        messagesToKeep.getFirst().id(),
                        normalizedAnchor,
                        messagesToKeep.getLast().id()
                )
        );
    }

    public static List<SessionMessage> buildPostCompactMessages(
            SessionMessage.CompactBoundaryMessage boundary,
            List<SessionMessage> summaryMessages,
            List<SessionMessage> messagesToKeep,
            List<SessionMessage> attachments,
            List<SessionMessage> hookResults
    ) {
        List<SessionMessage> messages = new java.util.ArrayList<>();
        if (boundary != null) {
            messages.add(boundary);
        }
        if (summaryMessages != null) {
            messages.addAll(summaryMessages);
        }
        if (messagesToKeep != null) {
            messages.addAll(messagesToKeep);
        }
        if (attachments != null) {
            messages.addAll(attachments);
        }
        if (hookResults != null) {
            messages.addAll(hookResults);
        }
        return List.copyOf(messages);
    }

    private static OptionalInt findMessageIndex(List<SessionMessage> messages, String messageId, int endExclusive) {
        if (messageId == null || messageId.isBlank()) {
            return OptionalInt.empty();
        }
        for (int index = 0; index < Math.min(endExclusive, messages.size()); index += 1) {
            if (messageId.equals(messages.get(index).id())) {
                return OptionalInt.of(index);
            }
        }
        return OptionalInt.empty();
    }

    private static int firstNonSummaryIndex(List<SessionMessage> messages, int startIndex) {
        int index = Math.max(0, startIndex);
        while (index < messages.size()) {
            SessionMessage message = messages.get(index);
            if (message instanceof SessionMessage.UserMessage userMessage && userMessage.compactSummary()) {
                index += 1;
                continue;
            }
            break;
        }
        return index;
    }

    public record SegmentSlice(
            List<SessionMessage> prefix,
            List<SessionMessage> segment
    ) {
    }
}
