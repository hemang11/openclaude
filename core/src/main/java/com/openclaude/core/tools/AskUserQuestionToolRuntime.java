package com.openclaude.core.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaude.provider.spi.ProviderToolDefinition;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class AskUserQuestionToolRuntime extends AbstractSingleToolRuntime {
    private static final String TOOL_NAME = "AskUserQuestion";
    private static final String TOOL_DESCRIPTION = """
            Asks the user multiple choice questions to gather information, clarify ambiguity, understand preferences, make decisions or offer them choices.

            Use this tool when you need to ask the user questions during execution. This allows you to:
            1. Gather user preferences or requirements
            2. Clarify ambiguous instructions
            3. Get decisions on implementation choices as you work
            4. Offer choices to the user about what direction to take.

            Usage notes:
            - Users will always be able to select "Other" to provide custom text input
            - Use multiSelect: true to allow multiple answers to be selected for a question
            - If you recommend a specific option, make that the first option in the list and add "(Recommended)" at the end of the label

            Plan mode note: In plan mode, use this tool to clarify requirements or choose between approaches BEFORE finalizing your plan. Do NOT use this tool to ask "Is my plan ready?" or "Should I proceed?" - use ExitPlanMode instead for plan approval. IMPORTANT: Do not reference "the plan" in your questions (e.g., "Do you have feedback about the plan?", "Does the plan look good?") because the user cannot see the plan in the UI until you call ExitPlanMode. If you need plan approval, use ExitPlanMode instead.

            Preview feature:
            Use the optional `preview` field on options when presenting concrete artifacts that users need to visually compare:
            - ASCII mockups of UI layouts or components
            - Code snippets showing different implementations
            - Diagram variations
            - Configuration examples

            Preview content is rendered as markdown in a monospace box. Multi-line text with newlines is supported. When any option has a preview, the UI switches to a side-by-side layout with a vertical option list on the left and preview on the right. Do not use previews for simple preference questions where labels and descriptions suffice. Note: previews are only supported for single-select questions (not multiSelect).
            """;
    private static final ProviderToolDefinition DEFINITION = new ProviderToolDefinition(
            TOOL_NAME,
            TOOL_DESCRIPTION,
            """
            {
              "type": "object",
              "properties": {
                "questions": {
                  "type": "array",
                  "minItems": 1,
                  "maxItems": 4,
                  "items": {
                    "type": "object",
                    "properties": {
                      "question": { "type": "string" },
                      "header": { "type": "string" },
                      "multiSelect": { "type": "boolean" },
                      "options": {
                        "type": "array",
                        "minItems": 2,
                        "maxItems": 4,
                        "items": {
                          "type": "object",
                          "properties": {
                            "label": { "type": "string" },
                            "description": { "type": "string" },
                            "preview": { "type": "string" }
                          },
                          "required": ["label", "description"],
                          "additionalProperties": false
                        }
                      }
                    },
                    "required": ["question", "header", "options"],
                    "additionalProperties": false
                  }
                },
                "answers": {
                  "type": "object",
                  "description": "User answers collected by the permission component.",
                  "additionalProperties": { "type": "string" }
                },
                "annotations": {
                  "type": "object",
                  "description": "Optional per-question annotations from the user (e.g., notes on preview selections). Keyed by question text.",
                  "additionalProperties": {
                    "type": "object",
                    "properties": {
                      "preview": { "type": "string" },
                      "notes": { "type": "string" }
                    },
                    "additionalProperties": false
                  }
                },
                "metadata": {
                  "type": "object",
                  "description": "Optional metadata for tracking and analytics purposes. Not displayed to user.",
                  "properties": {
                    "source": { "type": "string" }
                  },
                  "additionalProperties": false
                }
              },
              "required": ["questions"],
              "additionalProperties": false
            }
            """
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AskUserQuestionToolRuntime() {
        super(DEFINITION);
    }

    @Override
    protected ToolExecutionResult executeSingle(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer
    ) {
        AskUserQuestionInput input;
        try {
            input = objectMapper.readValue(request.inputJson(), AskUserQuestionInput.class);
        } catch (IOException exception) {
            emit(updateConsumer, request, "failed", "Invalid AskUserQuestion input: " + exception.getMessage(), "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Invalid AskUserQuestion input: " + exception.getMessage(), true);
        }

        List<Question> questions = sanitizeQuestions(input.questions());
        if (questions.isEmpty()) {
            emit(updateConsumer, request, "failed", "AskUserQuestion requires between 1 and 4 valid questions.", "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "AskUserQuestion requires between 1 and 4 valid questions.", true);
        }

        emit(updateConsumer, request, "started", questions.size() == 1
                ? "Queued question for the user."
                : "Queued " + questions.size() + " questions for the user.", "", false);

        String interactionJson;
        try {
            interactionJson = objectMapper.writeValueAsString(new InteractionPayload(questions));
        } catch (IOException exception) {
            emit(updateConsumer, request, "failed", "Failed to prepare AskUserQuestion payload: " + exception.getMessage(), "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Failed to prepare AskUserQuestion payload: " + exception.getMessage(), true);
        }

        String label = questions.size() == 1 ? questions.getFirst().question() : questions.size() + " questions";
        ToolPermissionDecision decision = requestPermission(
                request,
                permissionGateway,
                updateConsumer,
                label,
                "Answer questions?",
                "ask_user_question",
                interactionJson
        );

        if (!decision.allowed()) {
            String deniedFeedback;
            try {
                deniedFeedback = parseDeniedFeedback(decision.responseJson());
            } catch (IOException exception) {
                emit(updateConsumer, request, "failed", "Invalid AskUserQuestion response: " + exception.getMessage(), "", true);
                return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Invalid AskUserQuestion response: " + exception.getMessage(), true);
            }
            String text = deniedFeedback == null ? "User declined to answer questions." : deniedFeedback;
            emit(updateConsumer, request, "completed", text, "", false);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), text, false);
        }

        AskUserQuestionInput answeredInput;
        try {
            answeredInput = decision.updatedInputJson().isBlank()
                    ? null
                    : objectMapper.readValue(decision.updatedInputJson(), AskUserQuestionInput.class);
        } catch (IOException exception) {
            emit(updateConsumer, request, "failed", "Invalid AskUserQuestion response: " + exception.getMessage(), "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Invalid AskUserQuestion response: " + exception.getMessage(), true);
        }
        AskUserQuestionResponse response;
        try {
            response = answeredInput != null
                    ? new AskUserQuestionResponse(answeredInput.answers(), answeredInput.annotations())
                    : decision.responseJson().isBlank()
                    ? new AskUserQuestionResponse(Map.of(), Map.of())
                    : objectMapper.readValue(decision.responseJson(), AskUserQuestionResponse.class);
        } catch (IOException exception) {
            emit(updateConsumer, request, "failed", "Invalid AskUserQuestion response: " + exception.getMessage(), "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Invalid AskUserQuestion response: " + exception.getMessage(), true);
        }

        Map<String, String> answers = response.answers() == null ? Map.of() : response.answers();
        Map<String, QuestionAnnotation> annotations = response.annotations() == null ? Map.of() : response.annotations();
        String text = formatResultText(questions, answers, annotations);
        emit(updateConsumer, request, "completed", text, "", false);
        return new ToolExecutionResult(request.toolUseId(), request.toolName(), text, false);
    }

    private static List<Question> sanitizeQuestions(List<Question> rawQuestions) {
        if (rawQuestions == null) {
            return List.of();
        }

        List<Question> questions = new ArrayList<>();
        Set<String> seenQuestions = new LinkedHashSet<>();
        for (Question rawQuestion : rawQuestions) {
            if (rawQuestion == null) {
                continue;
            }
            String questionText = normalize(rawQuestion.question());
            String header = normalize(rawQuestion.header());
            if (questionText.isBlank() || header.isBlank() || !seenQuestions.add(questionText)) {
                continue;
            }

            List<QuestionOption> options = new ArrayList<>();
            Set<String> seenLabels = new LinkedHashSet<>();
            for (QuestionOption rawOption : rawQuestion.options() == null ? List.<QuestionOption>of() : rawQuestion.options()) {
                if (rawOption == null) {
                    continue;
                }
                String label = normalize(rawOption.label());
                String description = normalize(rawOption.description());
                if (label.isBlank() || description.isBlank() || !seenLabels.add(label)) {
                    continue;
                }
                options.add(new QuestionOption(label, description, normalize(rawOption.preview())));
            }
            if (options.size() < 2 || options.size() > 4) {
                continue;
            }

            questions.add(new Question(questionText, header, options, rawQuestion.multiSelect()));
            if (questions.size() == 4) {
                break;
            }
        }
        return List.copyOf(questions);
    }

    private static String formatResultText(
            List<Question> questions,
            Map<String, String> answers,
            Map<String, QuestionAnnotation> annotations
    ) {
        if (answers.isEmpty()) {
            return "User declined to answer questions.";
        }

        Map<String, String> ordered = new LinkedHashMap<>();
        for (Question question : questions) {
            String answer = answers.get(question.question());
            if (answer != null && !answer.isBlank()) {
                ordered.put(question.question(), answer);
            }
        }
        for (Map.Entry<String, String> entry : answers.entrySet()) {
            ordered.putIfAbsent(entry.getKey(), entry.getValue());
        }

        if (ordered.isEmpty()) {
            return "User declined to answer questions.";
        }

        String answersText = ordered.entrySet().stream()
                .map(entry -> {
                    QuestionAnnotation annotation = annotations.get(entry.getKey());
                    StringBuilder builder = new StringBuilder("\"%s\"=\"%s\"".formatted(entry.getKey(), entry.getValue()));
                    if (annotation != null && annotation.preview() != null && !annotation.preview().isBlank()) {
                        builder.append(" selected preview:\n").append(annotation.preview().trim());
                    }
                    if (annotation != null && annotation.notes() != null && !annotation.notes().isBlank()) {
                        builder.append(" user notes: ").append(annotation.notes().trim());
                    }
                    return builder.toString();
                })
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return "User has answered your questions: " + answersText + ". You can now continue with the user's answers in mind.";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String parseDeniedFeedback(String responseJson) throws IOException {
        if (responseJson == null || responseJson.isBlank()) {
            return null;
        }
        AskUserQuestionDeniedResponse response = objectMapper.readValue(responseJson, AskUserQuestionDeniedResponse.class);
        String feedback = normalize(response.feedback());
        return feedback.isBlank() ? null : feedback;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AskUserQuestionInput(
            List<Question> questions,
            Map<String, String> answers,
            Map<String, QuestionAnnotation> annotations,
            Metadata metadata
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Question(
            String question,
            String header,
            List<QuestionOption> options,
            boolean multiSelect
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QuestionOption(
            String label,
            String description,
            String preview
    ) {
    }

    private record InteractionPayload(
            List<Question> questions
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AskUserQuestionResponse(
            Map<String, String> answers,
            Map<String, QuestionAnnotation> annotations
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AskUserQuestionDeniedResponse(
            String feedback
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QuestionAnnotation(
            String preview,
            String notes
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Metadata(
            String source
    ) {
    }
}
