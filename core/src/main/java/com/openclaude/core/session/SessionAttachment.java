package com.openclaude.core.session;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.openclaude.provider.spi.PromptMessage;
import com.openclaude.provider.spi.PromptMessageRole;
import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "attachmentKind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SessionAttachment.RestoredFileAttachment.class, name = "restored_file"),
        @JsonSubTypes.Type(value = SessionAttachment.CompactFileReferenceAttachment.class, name = "compact_file_reference"),
        @JsonSubTypes.Type(value = SessionAttachment.PlanModeAttachment.class, name = "plan_mode"),
        @JsonSubTypes.Type(value = SessionAttachment.HookAdditionalContextAttachment.class, name = "hook_additional_context")
})
public sealed interface SessionAttachment permits
        SessionAttachment.RestoredFileAttachment,
        SessionAttachment.CompactFileReferenceAttachment,
        SessionAttachment.PlanModeAttachment,
        SessionAttachment.HookAdditionalContextAttachment {
    @JsonIgnore
    String attachmentKind();

    @JsonIgnore
    String source();

    @JsonIgnore
    String summaryText();

    @JsonIgnore
    default String hookEvent() {
        return "";
    }

    @JsonIgnore
    default String toolUseId() {
        return "";
    }

    default List<PromptMessage> toPromptMessages() {
        return List.of(new PromptMessage(PromptMessageRole.USER, promptText()));
    }

    @JsonIgnore
    String promptText();

    record RestoredFileAttachment(
            String filePath,
            String content,
            Integer offset,
            Integer limit,
            boolean truncated
    ) implements SessionAttachment {
        public RestoredFileAttachment {
            filePath = filePath == null ? "" : filePath;
            content = content == null ? "" : content;
        }

        @Override
        public String source() {
            return "post_compact_file_restore";
        }

        @Override
        public String attachmentKind() {
            return "restored_file";
        }

        @Override
        public String summaryText() {
            return "Restored file context for " + filePath;
        }

        @Override
        public String promptText() {
            StringBuilder builder = new StringBuilder();
            builder.append("Note: ")
                    .append(filePath)
                    .append(" was read before the last conversation was summarized. ");
            if (offset != null || limit != null) {
                builder.append("The restored excerpt below mirrors the previously read range.");
            } else {
                builder.append("The restored contents below were part of the earlier working context.");
            }
            builder.append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append(content);
            if (truncated) {
                builder.append(System.lineSeparator())
                        .append(System.lineSeparator())
                        .append("Note: this restored file context was truncated during compaction. Use the Read tool if you need more of the file.");
            }
            return builder.toString();
        }
    }

    record CompactFileReferenceAttachment(
            String filePath
    ) implements SessionAttachment {
        public CompactFileReferenceAttachment {
            filePath = filePath == null ? "" : filePath;
        }

        @Override
        public String source() {
            return "compact_file_reference";
        }

        @Override
        public String attachmentKind() {
            return "compact_file_reference";
        }

        @Override
        public String summaryText() {
            return "Preserved file reference for " + filePath;
        }

        @Override
        public String promptText() {
            return "Note: " + filePath + " was read before the last conversation was summarized, but the contents are too large to include. Use the Read tool if you need to access it again.";
        }
    }

    record PlanModeAttachment() implements SessionAttachment {
        @Override
        public String source() {
            return "plan_mode";
        }

        @Override
        public String attachmentKind() {
            return "plan_mode";
        }

        @Override
        public String summaryText() {
            return "Plan mode is still active after compaction";
        }

        @Override
        public String promptText() {
            return """
                    Plan mode is still active after compaction. Continue exploring, designing, and asking clarifying questions.
                    You must not make edits or run non-read-only tools until you explicitly exit plan mode.
                    Use AskUserQuestion to clarify requirements and ExitPlanMode to request approval when the plan is ready.
                    """.strip();
        }
    }

    record HookAdditionalContextAttachment(
            @JsonProperty("hookEvent")
            String hookEvent,
            String hookName,
            String command,
            String content,
            @JsonProperty("toolUseId")
            String toolUseId
    ) implements SessionAttachment {
        public HookAdditionalContextAttachment {
            hookEvent = hookEvent == null || hookEvent.isBlank() ? hookName : hookEvent;
            hookName = hookName == null || hookName.isBlank() ? "Hook" : hookName;
            command = command == null ? "" : command;
            content = content == null ? "" : content;
            toolUseId = toolUseId == null ? "" : toolUseId;
        }

        public HookAdditionalContextAttachment(String hookName, String command, String content) {
            this(hookName, hookName, command, content, "");
        }

        @Override
        public String source() {
            return "hook_additional_context";
        }

        @Override
        public String attachmentKind() {
            return "hook_additional_context";
        }

        @Override
        public String hookEvent() {
            return hookEvent;
        }

        @Override
        public String toolUseId() {
            return toolUseId;
        }

        @Override
        public String summaryText() {
            if (command.isBlank()) {
                return "Additional context from " + hookName;
            }
            return "Additional context from " + hookName + " (" + command + ")";
        }

        @Override
        public String promptText() {
            if (command.isBlank()) {
                return content;
            }
            return "Additional context from " + hookName + " hook [" + command + "]:" + System.lineSeparator()
                    + System.lineSeparator()
                    + content;
        }
    }
}
