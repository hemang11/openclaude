package com.openclaude.provider.spi.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openclaude.provider.spi.PromptEvent;
import com.openclaude.provider.spi.PromptResult;
import com.openclaude.provider.spi.TextContentBlock;
import com.openclaude.provider.spi.ToolCallEvent;
import com.openclaude.provider.spi.ToolUseContentBlock;
import java.util.List;

public final class ToolSmokeAssertions {
    private ToolSmokeAssertions() {
    }

    public static void assertBashToolAdvertised(String payload) {
        assertTrue(payload.contains("\"name\":\"bash\""), "Expected provider payload to advertise the bash tool.");
    }

    public static ToolUseContentBlock assertSingleBashToolUse(PromptResult result, String expectedArgumentsJson) {
        List<ToolUseContentBlock> toolUses = result.content().stream()
                .filter(ToolUseContentBlock.class::isInstance)
                .map(ToolUseContentBlock.class::cast)
                .toList();

        assertEquals(1, toolUses.size(), "Expected exactly one bash tool call in the provider result.");
        ToolUseContentBlock toolUse = toolUses.getFirst();
        assertEquals("bash", toolUse.toolName(), "Expected the tool call to use bash.");
        assertEquals(expectedArgumentsJson, toolUse.inputJson(), "Tool arguments were not preserved.");
        return toolUse;
    }

    public static void assertNoPhantomFunctionToolUses(PromptResult result) {
        List<ToolUseContentBlock> toolUses = result.content().stream()
                .filter(ToolUseContentBlock.class::isInstance)
                .map(ToolUseContentBlock.class::cast)
                .toList();

        assertFalse(
                toolUses.stream().anyMatch(toolUse -> "function".equals(toolUse.toolName()) || "function_call".equals(toolUse.toolName())),
                "Provider result still contains a phantom function tool call."
        );
    }

    public static void assertToolLifecycleEvents(List<PromptEvent> events, String toolName, String... expectedPhases) {
        List<ToolCallEvent> toolEvents = events.stream()
                .filter(ToolCallEvent.class::isInstance)
                .map(ToolCallEvent.class::cast)
                .filter(event -> toolName.equals(event.toolName()))
                .toList();

        for (String expectedPhase : expectedPhases) {
            assertTrue(
                    toolEvents.stream().anyMatch(event -> expectedPhase.equals(event.phase())),
                    "Expected a " + toolName + " tool event with phase " + expectedPhase + "."
            );
        }
    }

    public static void assertLeadingTextBlock(PromptResult result, String expectedText) {
        TextContentBlock textBlock = (TextContentBlock) result.content().stream()
                .filter(TextContentBlock.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected the provider result to include a text content block."));

        assertEquals(expectedText, textBlock.text(), "Provider text content did not match the expected tool smoke output.");
        assertEquals(expectedText, result.text(), "PromptResult.text() did not match the expected tool smoke output.");
    }
}
