package com.openclaude.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AskUserQuestionToolRuntimeTest {
    private final AskUserQuestionToolRuntime runtime = new AskUserQuestionToolRuntime();

    @Test
    void emitsStructuredQuestionRequestAndReturnsSubmittedAnswers() {
        List<ToolExecutionUpdate> updates = new ArrayList<>();

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-q1",
                        "AskUserQuestion",
                        """
                        {
                          "questions": [
                            {
                              "question": "Which implementation path should OpenClaude take?",
                              "header": "Approach",
                              "options": [
                                { "label": "Safer refactor", "description": "Land the change in smaller steps." },
                                { "label": "Direct rewrite", "description": "Rewrite the flow in one pass." }
                              ]
                            }
                          ]
                        }
                        """
                ),
                request -> ToolPermissionDecision.allow(
                        "answered in test",
                        """
                        {
                          "answers": {
                            "Which implementation path should OpenClaude take?": "Safer refactor"
                          }
                        }
                        """
                ),
                updates::add
        );

        assertFalse(result.error());
        assertTrue(result.text().contains("\"Which implementation path should OpenClaude take?\"=\"Safer refactor\""));
        assertTrue(updates.stream().anyMatch(update -> "permission_requested".equals(update.phase())));
        ToolExecutionUpdate permissionUpdate = updates.stream()
                .filter(update -> "permission_requested".equals(update.phase()))
                .findFirst()
                .orElseThrow();
        assertEquals("ask_user_question", permissionUpdate.interactionType());
        assertTrue(permissionUpdate.interactionJson().contains("\"Approach\""));
        assertTrue(updates.stream().anyMatch(update -> "completed".equals(update.phase())));
    }

    @Test
    void returnsDeclinedMessageWhenUserRejectsQuestions() {
        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-q1",
                        "AskUserQuestion",
                        """
                        {
                          "questions": [
                            {
                              "question": "Which implementation path should OpenClaude take?",
                              "header": "Approach",
                              "options": [
                                { "label": "Safer refactor", "description": "Land the change in smaller steps." },
                                { "label": "Direct rewrite", "description": "Rewrite the flow in one pass." }
                              ]
                            }
                          ]
                        }
                        """
                ),
                request -> ToolPermissionDecision.deny("declined in test"),
                update -> {}
        );

        assertFalse(result.error());
        assertEquals("User declined to answer questions.", result.text());
    }

    @Test
    void includesPreviewAndNotesAnnotationsInResultText() {
        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-q2",
                        "AskUserQuestion",
                        """
                        {
                          "questions": [
                            {
                              "question": "Which architecture should OpenClaude take?",
                              "header": "Architecture",
                              "options": [
                                {
                                  "label": "Safer refactor",
                                  "description": "Land the refactor in smaller steps.",
                                  "preview": "## Safer refactor\\nIncremental rollout with checkpoints."
                                },
                                {
                                  "label": "Direct rewrite",
                                  "description": "Rewrite the flow in one pass."
                                }
                              ]
                            }
                          ]
                        }
                        """
                ),
                request -> ToolPermissionDecision.allow(
                        "answered in test",
                        """
                        {
                          "answers": {
                            "Which architecture should OpenClaude take?": "Safer refactor"
                          },
                          "annotations": {
                            "Which architecture should OpenClaude take?": {
                              "preview": "## Safer refactor\\nIncremental rollout with checkpoints.",
                              "notes": "Keep the rollout reversible."
                            }
                          }
                        }
                        """
                ),
                update -> {}
        );

        assertFalse(result.error());
        assertTrue(result.text().contains("selected preview:"));
        assertTrue(result.text().contains("Incremental rollout with checkpoints."));
        assertTrue(result.text().contains("user notes: Keep the rollout reversible."));
    }

    @Test
    void consumesUpdatedInputJsonReturnedFromPermissionApproval() {
        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-q4",
                        "AskUserQuestion",
                        """
                        {
                          "questions": [
                            {
                              "question": "Which implementation path should OpenClaude take?",
                              "header": "Approach",
                              "options": [
                                { "label": "Safer refactor", "description": "Land the change in smaller steps." },
                                { "label": "Direct rewrite", "description": "Rewrite the flow in one pass." }
                              ]
                            }
                          ]
                        }
                        """
                ),
                request -> ToolPermissionDecision.allow(
                        "answered in test",
                        "",
                        """
                        {
                          "questions": [
                            {
                              "question": "Which implementation path should OpenClaude take?",
                              "header": "Approach",
                              "options": [
                                { "label": "Safer refactor", "description": "Land the change in smaller steps." },
                                { "label": "Direct rewrite", "description": "Rewrite the flow in one pass." }
                              ]
                            }
                          ],
                          "answers": {
                            "Which implementation path should OpenClaude take?": "Direct rewrite"
                          }
                        }
                        """,
                        true
                ),
                update -> {}
        );

        assertFalse(result.error());
        assertTrue(result.text().contains("\"Which implementation path should OpenClaude take?\"=\"Direct rewrite\""));
    }

    @Test
    void returnsFollowUpFeedbackWhenUserWantsToChatAboutTheQuestions() {
        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-q3",
                        "AskUserQuestion",
                        """
                        {
                          "questions": [
                            {
                              "question": "Which implementation path should OpenClaude take?",
                              "header": "Approach",
                              "options": [
                                { "label": "Safer refactor", "description": "Land the change in smaller steps." },
                                { "label": "Direct rewrite", "description": "Rewrite the flow in one pass." }
                              ]
                            }
                          ]
                        }
                        """
                ),
                request -> ToolPermissionDecision.deny(
                        "follow up in test",
                        """
                        {
                          "feedback": "The user wants to clarify these questions. Start by asking them what they would like to clarify."
                        }
                        """
                ),
                update -> {}
        );

        assertFalse(result.error());
        assertTrue(result.text().contains("The user wants to clarify these questions."));
        assertTrue(result.text().contains("what they would like to clarify"));
    }

    @Test
    void advertisesClaudesPromptAndPreviewGuidanceInTheToolDefinition() {
        var definition = runtime.toolDefinitions().stream()
                .filter(tool -> "AskUserQuestion".equals(tool.name()))
                .findFirst()
                .orElseThrow();

        assertNotNull(definition);
        assertTrue(definition.description().contains("Use this tool when you need to ask the user questions during execution."));
        assertTrue(definition.description().contains("Users will always be able to select \"Other\""));
        assertTrue(definition.description().contains("Preview feature:"));
        assertTrue(definition.description().contains("Preview content is rendered as markdown in a monospace box."));
        assertTrue(definition.description().contains("Do NOT use this tool to ask \"Is my plan ready?\""));
    }
}
