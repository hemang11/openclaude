package com.openclaude.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openclaude.provider.spi.ProviderId;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionCompactionTest {
    @Test
    void annotateBoundaryWithPreservedSegmentCapturesHeadAnchorAndTail() {
        SessionMessage.UserMessage keptUser = SessionMessage.user("kept user");
        SessionMessage.AssistantMessage keptAssistant = SessionMessage.assistant("kept assistant", ProviderId.OPENAI, "gpt-5.4");
        SessionMessage.CompactBoundaryMessage boundary = SessionMessage.compactBoundary("manual", 42, 4);

        SessionMessage.CompactBoundaryMessage annotated = SessionCompaction.annotateBoundaryWithPreservedSegment(
                boundary,
                "summary-anchor",
                List.of(keptUser, keptAssistant)
        );

        assertNotNull(annotated.preservedSegment());
        assertEquals(keptUser.id(), annotated.preservedSegment().headId());
        assertEquals("summary-anchor", annotated.preservedSegment().anchorId());
        assertEquals(keptAssistant.id(), annotated.preservedSegment().tailId());
        assertTrue(!annotated.preservedSegment().isBlank());
    }

    @Test
    void buildPostCompactMessagesMatchesClaudeOrdering() {
        SessionMessage.CompactBoundaryMessage boundary = SessionMessage.compactBoundary("manual", 42, 4);
        SessionMessage.UserMessage summary = SessionMessage.compactSummary("Summary");
        SessionMessage.UserMessage keptUser = SessionMessage.user("kept user");
        SessionMessage.AttachmentMessage attachment = SessionMessage.attachment("attachment", "post_compact");
        SessionMessage.AttachmentMessage hookResult = SessionMessage.attachment(
                new SessionAttachment.HookAdditionalContextAttachment("SessionStart", "echo hi", "hook context")
        );

        List<SessionMessage> ordered = SessionCompaction.buildPostCompactMessages(
                boundary,
                List.of(summary),
                List.of(keptUser),
                List.of(attachment),
                List.of(hookResult)
        );

        assertEquals(List.of(boundary, summary, keptUser, attachment, hookResult), ordered);
    }
}
