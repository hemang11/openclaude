package com.openclaude.core.session;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.openclaude.provider.spi.PromptContentBlock;
import com.openclaude.provider.spi.PromptMessage;
import com.openclaude.provider.spi.PromptMessageRole;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.TextContentBlock;
import com.openclaude.provider.spi.ToolResultContentBlock;
import com.openclaude.provider.spi.ToolUseContentBlock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SessionMessage.UserMessage.class, name = "user"),
        @JsonSubTypes.Type(value = SessionMessage.AssistantMessage.class, name = "assistant"),
        @JsonSubTypes.Type(value = SessionMessage.CompactBoundaryMessage.class, name = "compact_boundary"),
        @JsonSubTypes.Type(value = SessionMessage.ThinkingMessage.class, name = "thinking"),
        @JsonSubTypes.Type(value = SessionMessage.ToolInvocationMessage.class, name = "tool"),
        @JsonSubTypes.Type(value = SessionMessage.ToolResultMessage.class, name = "tool_result"),
        @JsonSubTypes.Type(value = SessionMessage.SystemMessage.class, name = "system"),
        @JsonSubTypes.Type(value = SessionMessage.ProgressMessage.class, name = "progress"),
        @JsonSubTypes.Type(value = SessionMessage.AttachmentMessage.class, name = "attachment"),
        @JsonSubTypes.Type(value = SessionMessage.TombstoneMessage.class, name = "tombstone")
})
public sealed interface SessionMessage permits
        SessionMessage.UserMessage,
        SessionMessage.AssistantMessage,
        SessionMessage.CompactBoundaryMessage,
        SessionMessage.ThinkingMessage,
        SessionMessage.ToolInvocationMessage,
        SessionMessage.ToolResultMessage,
        SessionMessage.SystemMessage,
        SessionMessage.ProgressMessage,
        SessionMessage.AttachmentMessage,
        SessionMessage.TombstoneMessage {
    String id();

    Instant createdAt();

    String text();

    PromptMessageRole promptRole();

    default PromptMessage toPromptMessage() {
        return new PromptMessage(promptRole(), List.of(new TextContentBlock(text())));
    }

    default List<PromptMessage> toPromptMessages() {
        return List.of(toPromptMessage());
    }

    static UserMessage user(String text) {
        return new UserMessage(UUID.randomUUID().toString(), Instant.now(), text, false);
    }

    static UserMessage compactSummary(String text) {
        return new UserMessage(UUID.randomUUID().toString(), Instant.now(), text, true);
    }

    static AssistantMessage assistant(String text, ProviderId providerId, String modelId) {
        return new AssistantMessage(UUID.randomUUID().toString(), Instant.now(), text, providerId, modelId, List.of());
    }

    static AssistantMessage assistant(String text, ProviderId providerId, String modelId, List<ToolUseContentBlock> toolUses) {
        return new AssistantMessage(UUID.randomUUID().toString(), Instant.now(), text, providerId, modelId, toolUses);
    }

    static ThinkingMessage thinking(String text) {
        return new ThinkingMessage(UUID.randomUUID().toString(), Instant.now(), text);
    }

    static CompactBoundaryMessage compactBoundary(String trigger, int preTokens, int messagesSummarized) {
        return new CompactBoundaryMessage(
                UUID.randomUUID().toString(),
                Instant.now(),
                "Conversation compacted",
                trigger,
                preTokens,
                messagesSummarized,
                null
        );
    }

    static CompactBoundaryMessage compactBoundary(
            String trigger,
            int preTokens,
            int messagesSummarized,
            PreservedSegment preservedSegment
    ) {
        return new CompactBoundaryMessage(
                UUID.randomUUID().toString(),
                Instant.now(),
                "Conversation compacted",
                trigger,
                preTokens,
                messagesSummarized,
                preservedSegment
        );
    }

    static ToolInvocationMessage tool(
            String toolId,
            String toolName,
            String phase,
            String text,
            String inputJson,
            String command,
            String permissionRequestId,
            boolean isError
    ) {
        return tool(
                toolId,
                toolName,
                phase,
                text,
                inputJson,
                command,
                permissionRequestId,
                "",
                "",
                isError
        );
    }

    static ToolInvocationMessage tool(
            String toolId,
            String toolName,
            String phase,
            String text,
            String inputJson,
            String command,
            String permissionRequestId,
            String interactionType,
            String interactionJson,
            boolean isError
    ) {
        return new ToolInvocationMessage(
                UUID.randomUUID().toString(),
                Instant.now(),
                toolId,
                toolName,
                phase,
                text,
                inputJson,
                command,
                permissionRequestId,
                interactionType,
                interactionJson,
                isError
        );
    }

    static ToolResultMessage toolResult(String toolUseId, String toolName, String text, boolean isError) {
        return new ToolResultMessage(UUID.randomUUID().toString(), Instant.now(), toolUseId, toolName, text, isError, null);
    }

    static ToolResultMessage toolResult(String toolUseId, String toolName, String text, boolean isError, String displayText) {
        return new ToolResultMessage(UUID.randomUUID().toString(), Instant.now(), toolUseId, toolName, text, isError, displayText);
    }

    static SystemMessage system(String text) {
        return new SystemMessage(UUID.randomUUID().toString(), Instant.now(), text);
    }

    static ProgressMessage progress(String text) {
        return new ProgressMessage(UUID.randomUUID().toString(), Instant.now(), text, null, null, null, null, null, null);
    }

    static ProgressMessage progress(
            String text,
            String progressKind,
            String toolUseId,
            String hookEvent,
            String hookName,
            String command,
            Boolean isError
    ) {
        return new ProgressMessage(
                UUID.randomUUID().toString(),
                Instant.now(),
                text,
                progressKind,
                toolUseId,
                hookEvent,
                hookName,
                command,
                isError
        );
    }

    static AttachmentMessage attachment(String text, String source) {
        return new AttachmentMessage(UUID.randomUUID().toString(), Instant.now(), text, source, null);
    }

    static AttachmentMessage attachment(SessionAttachment attachment) {
        return new AttachmentMessage(
                UUID.randomUUID().toString(),
                Instant.now(),
                attachment == null ? "" : attachment.summaryText(),
                attachment == null ? "" : attachment.source(),
                attachment
        );
    }

    static TombstoneMessage tombstone(String text, String reason) {
        return new TombstoneMessage(UUID.randomUUID().toString(), Instant.now(), text, reason);
    }

    record UserMessage(
            String id,
            Instant createdAt,
            String text,
            boolean compactSummary
    ) implements SessionMessage {
        public UserMessage {
            id = normalizeId(id);
            createdAt = normalizeCreatedAt(createdAt);
            text = normalizeText(text);
        }

        public UserMessage(String id, Instant createdAt, String text) {
            this(id, createdAt, text, false);
        }

        @Override
        public PromptMessageRole promptRole() {
            return PromptMessageRole.USER;
        }
    }

    record AssistantMessage(
            String id,
            Instant createdAt,
            String text,
            ProviderId providerId,
            String modelId,
            List<ToolUseContentBlock> toolUses
    ) implements SessionMessage {
        public AssistantMessage {
            id = normalizeId(id);
            createdAt = normalizeCreatedAt(createdAt);
            text = normalizeText(text);
            toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
        }

        @Override
        public PromptMessageRole promptRole() {
            return PromptMessageRole.ASSISTANT;
        }

        @Override
        public PromptMessage toPromptMessage() {
            List<PromptContentBlock> content = new java.util.ArrayList<>();
            if (!text.isBlank()) {
                content.add(new TextContentBlock(text));
            }
            content.addAll(toolUses);
            return new PromptMessage(promptRole(), content);
        }
    }

    record CompactBoundaryMessage(
            String id,
            Instant createdAt,
            String text,
            String trigger,
            int preTokens,
            int messagesSummarized,
            PreservedSegment preservedSegment
    ) implements SessionMessage {
        public CompactBoundaryMessage {
            id = normalizeId(id);
            createdAt = normalizeCreatedAt(createdAt);
            text = normalizeText(text);
            trigger = trigger == null || trigger.isBlank() ? "manual" : trigger;
            preTokens = Math.max(preTokens, 0);
            messagesSummarized = Math.max(messagesSummarized, 0);
            preservedSegment = preservedSegment == null || preservedSegment.isBlank() ? null : preservedSegment;
        }

        @Override
        public PromptMessageRole promptRole() {
            throw new UnsupportedOperationException("Compact boundary messages are not sent to providers");
        }
    }

    record PreservedSegment(
            String headId,
            String anchorId,
            String tailId
    ) {
        public PreservedSegment {
            headId = normalizeText(headId);
            anchorId = normalizeText(anchorId);
            tailId = normalizeText(tailId);
        }

        @JsonIgnore
        public boolean isBlank() {
            return headId.isBlank() || anchorId.isBlank() || tailId.isBlank();
        }
    }

    record ThinkingMessage(
            String id,
            Instant createdAt,
            String text
    ) implements SessionMessage {
        public ThinkingMessage {
            id = normalizeId(id);
            createdAt = normalizeCreatedAt(createdAt);
            text = normalizeText(text);
        }

        @Override
        public PromptMessageRole promptRole() {
            throw new UnsupportedOperationException("Thinking messages are not sent to providers");
        }
    }

    record ToolInvocationMessage(
            String id,
            Instant createdAt,
            String toolId,
            String toolName,
            String phase,
            String text,
            String inputJson,
            String command,
            String permissionRequestId,
            String interactionType,
            String interactionJson,
            boolean isError
    ) implements SessionMessage {
        public ToolInvocationMessage {
            id = normalizeId(id);
            createdAt = normalizeCreatedAt(createdAt);
            toolId = toolId == null ? "" : toolId;
            toolName = toolName == null || toolName.isBlank() ? "tool" : toolName;
            phase = phase == null || phase.isBlank() ? "status" : phase;
            text = normalizeText(text);
            inputJson = inputJson == null ? "{}" : inputJson;
            command = command == null ? "" : command;
            permissionRequestId = permissionRequestId == null ? "" : permissionRequestId;
            interactionType = interactionType == null ? "" : interactionType;
            interactionJson = interactionJson == null ? "" : interactionJson;
        }

        @Override
        public PromptMessageRole promptRole() {
            throw new UnsupportedOperationException("Tool messages are not sent to providers");
        }
    }

    record ToolResultMessage(
            String id,
            Instant createdAt,
            String toolUseId,
            String toolName,
            String text,
            boolean isError,
            String displayText
    ) implements SessionMessage {
        public ToolResultMessage {
            id = normalizeId(id);
            createdAt = normalizeCreatedAt(createdAt);
            toolUseId = toolUseId == null ? "" : toolUseId;
            toolName = toolName == null || toolName.isBlank() ? "tool" : toolName;
            text = normalizeText(text);
            displayText = normalizeNullableText(displayText);
        }

        public ToolResultMessage(
                String id,
                Instant createdAt,
                String toolUseId,
                String toolName,
                String text,
                boolean isError
        ) {
            this(id, createdAt, toolUseId, toolName, text, isError, null);
        }

        @Override
        public PromptMessageRole promptRole() {
            return PromptMessageRole.USER;
        }

        @Override
        public PromptMessage toPromptMessage() {
            return new PromptMessage(
                    promptRole(),
                    List.of(new ToolResultContentBlock(toolUseId, toolName, text, isError))
            );
        }
    }

    record SystemMessage(
            String id,
            Instant createdAt,
            String text
    ) implements SessionMessage {
        public SystemMessage {
            id = normalizeId(id);
            createdAt = normalizeCreatedAt(createdAt);
            text = normalizeText(text);
        }

        @Override
        public PromptMessageRole promptRole() {
            return PromptMessageRole.SYSTEM;
        }
    }

    record ProgressMessage(
            String id,
            Instant createdAt,
            String text,
            String progressKind,
            String toolUseId,
            String hookEvent,
            String hookName,
            String command,
            Boolean isError
    ) implements SessionMessage {
        public ProgressMessage {
            id = normalizeId(id);
            createdAt = normalizeCreatedAt(createdAt);
            text = normalizeText(text);
            progressKind = normalizeNullableText(progressKind);
            toolUseId = normalizeNullableText(toolUseId);
            hookEvent = normalizeNullableText(hookEvent);
            hookName = normalizeNullableText(hookName);
            command = normalizeNullableText(command);
        }

        @Override
        public PromptMessageRole promptRole() {
            throw new UnsupportedOperationException("Progress messages are not sent to providers");
        }
    }

    record AttachmentMessage(
            String id,
            Instant createdAt,
            String text,
            String source,
            SessionAttachment attachment
    ) implements SessionMessage {
        public AttachmentMessage {
            id = normalizeId(id);
            createdAt = normalizeCreatedAt(createdAt);
            if (attachment != null) {
                text = normalizeText(text).isBlank() ? attachment.summaryText() : normalizeText(text);
                source = source == null || source.isBlank() ? attachment.source() : source;
            } else {
                text = normalizeText(text);
                source = source == null ? "" : source;
            }
        }

        @Override
        public PromptMessageRole promptRole() {
            return PromptMessageRole.USER;
        }

        @Override
        public List<PromptMessage> toPromptMessages() {
            if (attachment != null) {
                return attachment.toPromptMessages();
            }
            return List.of(new PromptMessage(PromptMessageRole.USER, text));
        }
    }

    record TombstoneMessage(
            String id,
            Instant createdAt,
            String text,
            String reason
    ) implements SessionMessage {
        public TombstoneMessage {
            id = normalizeId(id);
            createdAt = normalizeCreatedAt(createdAt);
            text = normalizeText(text);
            reason = reason == null ? "" : reason;
        }

        @Override
        public PromptMessageRole promptRole() {
            throw new UnsupportedOperationException("Tombstone messages are not sent to providers");
        }
    }

    private static String normalizeId(String id) {
        return id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
    }

    private static Instant normalizeCreatedAt(Instant createdAt) {
        return createdAt == null ? Instant.now() : createdAt;
    }

    private static String normalizeText(String text) {
        return text == null ? "" : text;
    }

    private static String normalizeNullableText(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.strip();
        return normalized.isBlank() ? null : normalized;
    }
}
