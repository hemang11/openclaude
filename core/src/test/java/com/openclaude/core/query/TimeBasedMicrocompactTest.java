package com.openclaude.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.SessionMessage;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ToolUseContentBlock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimeBasedMicrocompactTest {
    @Test
    void maybeApplyClearsOldCompactableToolResultsWhileKeepingFiveMostRecent() {
        Instant oldTime = Instant.now().minusSeconds(61 * 60L);
        ConversationSession session = ConversationSession.create("session-1", "/tmp", "/tmp");
        List<SessionMessage> messages = new ArrayList<>();
        for (int index = 0; index < 7; index += 1) {
            String toolUseId = "tool-" + index;
            messages.add(new SessionMessage.UserMessage("user-" + index, oldTime, "prompt " + index, false));
            messages.add(new SessionMessage.AssistantMessage(
                    "assistant-tool-" + index,
                    oldTime,
                    "",
                    ProviderId.OPENAI,
                    "gpt-5.4",
                    List.of(new ToolUseContentBlock(toolUseId, "Read", "{\"file\":\"File" + index + "\"}"))
            ));
            messages.add(new SessionMessage.ToolResultMessage(
                    "tool-result-" + index,
                    oldTime,
                    toolUseId,
                    "Read",
                    "contents " + "x".repeat(2_000),
                    false
            ));
            messages.add(new SessionMessage.AssistantMessage(
                    "assistant-final-" + index,
                    oldTime,
                    "done " + index,
                    ProviderId.OPENAI,
                    "gpt-5.4",
                    List.of()
            ));
        }
        session = session.withMessages(messages);

        TimeBasedMicrocompact.Result result = new TimeBasedMicrocompact().maybeApply(session, Instant.now());

        assertTrue(result.applied());
        assertEquals(2, result.compactedToolIds().size());

        List<SessionMessage.ToolResultMessage> toolResults = result.session().messages().stream()
                .filter(SessionMessage.ToolResultMessage.class::isInstance)
                .map(SessionMessage.ToolResultMessage.class::cast)
                .toList();
        assertEquals(TimeBasedMicrocompact.CLEARED_MESSAGE, toolResults.get(0).text());
        assertEquals(TimeBasedMicrocompact.CLEARED_MESSAGE, toolResults.get(1).text());
        assertTrue(toolResults.subList(2, toolResults.size()).stream()
                .noneMatch(toolResult -> TimeBasedMicrocompact.CLEARED_MESSAGE.equals(toolResult.text())));
    }

    @Test
    void maybeApplyDoesNothingWhenLatestAssistantIsRecent() {
        Instant recentTime = Instant.now().minusSeconds(5 * 60L);
        ConversationSession session = ConversationSession.create("session-2", "/tmp", "/tmp")
                .withMessages(List.of(
                        new SessionMessage.UserMessage("user", recentTime, "prompt", false),
                        new SessionMessage.AssistantMessage(
                                "assistant",
                                recentTime,
                                "recent answer",
                                ProviderId.OPENAI,
                                "gpt-5.4",
                                List.of()
                        )
                ));

        TimeBasedMicrocompact.Result result = new TimeBasedMicrocompact().maybeApply(session, Instant.now());

        assertFalse(result.applied());
        assertEquals(session.messages(), result.session().messages());
    }
}
