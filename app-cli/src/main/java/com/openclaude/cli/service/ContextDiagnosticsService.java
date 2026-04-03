package com.openclaude.cli.service;

import com.openclaude.core.instructions.AgentsInstructionsLoader;
import com.openclaude.core.provider.OpenClaudeState;
import com.openclaude.core.provider.ProviderRegistry;
import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.SessionAttachment;
import com.openclaude.core.session.SessionCompaction;
import com.openclaude.core.session.SessionMessage;
import com.openclaude.core.tools.ToolRuntime;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.PromptContentBlock;
import com.openclaude.provider.spi.PromptMessage;
import com.openclaude.provider.spi.PromptMessageRole;
import com.openclaude.provider.spi.ProviderToolDefinition;
import com.openclaude.provider.spi.TextContentBlock;
import com.openclaude.provider.spi.ToolResultContentBlock;
import com.openclaude.provider.spi.ToolUseContentBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ContextDiagnosticsService {
    private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 200_000;
    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are OpenClaude, an interactive coding assistant running in a terminal on the user's machine.
            You have access to local tools exposed in this session.
            When the user asks about local files, folders, repositories, the current workspace, or command output,
            use the available tools to inspect the environment instead of claiming you cannot access it.
            Prefer the bash tool for filesystem inspection, directory listing, search, and command output when local state matters.
            Answer from observed tool results and stay concise.
            """.strip();

    private final ProviderRegistry providerRegistry;
    private final ToolRuntime toolRuntime;
    private final AgentsInstructionsLoader instructionsLoader;

    public ContextDiagnosticsService(ProviderRegistry providerRegistry, ToolRuntime toolRuntime) {
        this(providerRegistry, toolRuntime, new AgentsInstructionsLoader());
    }

    ContextDiagnosticsService(
            ProviderRegistry providerRegistry,
            ToolRuntime toolRuntime,
            AgentsInstructionsLoader instructionsLoader
    ) {
        this.providerRegistry = providerRegistry;
        this.toolRuntime = toolRuntime;
        this.instructionsLoader = instructionsLoader;
    }

    public ContextReport analyze(OpenClaudeState state, ConversationSession session) {
        ConversationSession activeSession = session == null ? ConversationSession.create("analysis") : session;
        List<ProviderToolDefinition> availableTools = resolveAvailableTools(state);
        String instructionsPrompt = instructionsLoader.renderSystemPrompt(activeSession);
        List<PromptMessage> promptMessages = buildPromptMessages(activeSession, List.of(), availableTools, instructionsPrompt);

        int systemPromptTokens = 0;
        int userMessageTokens = 0;
        int assistantMessageTokens = 0;
        int toolCallTokens = 0;
        int toolResultTokens = 0;
        int attachmentTokens = 0;

        for (PromptMessage promptMessage : promptMessages) {
            for (PromptContentBlock block : promptMessage.content()) {
                if (block instanceof TextContentBlock textContentBlock) {
                    int tokens = estimateTokens(textContentBlock.text());
                    switch (promptMessage.role()) {
                        case SYSTEM -> systemPromptTokens += tokens;
                        case USER -> userMessageTokens += tokens;
                        case ASSISTANT -> assistantMessageTokens += tokens;
                    }
                } else if (block instanceof ToolUseContentBlock toolUseContentBlock) {
                    toolCallTokens += estimateTokens(toolUseContentBlock.toolName() + "\n" + toolUseContentBlock.inputJson());
                } else if (block instanceof ToolResultContentBlock toolResultContentBlock) {
                    toolResultTokens += estimateTokens(toolResultContentBlock.text());
                }
            }
        }

        for (SessionMessage message : SessionCompaction.messagesAfterCompactBoundary(activeSession.messages())) {
            if (message instanceof SessionMessage.AttachmentMessage attachmentMessage) {
                attachmentTokens += estimateTokens(attachmentMessage.text());
            }
        }

        int toolDefinitionTokens = availableTools.stream()
                .mapToInt(ContextDiagnosticsService::estimateToolDefinitionTokens)
                .sum();
        int totalTokens = systemPromptTokens
                + toolDefinitionTokens
                + userMessageTokens
                + assistantMessageTokens
                + toolCallTokens
                + toolResultTokens;
        int contextWindowTokens = resolveContextWindowTokens(state);

        List<Category> categories = List.of(
                new Category("System prompt", systemPromptTokens),
                new Category("Tool definitions", toolDefinitionTokens),
                new Category("User messages", userMessageTokens),
                new Category("Assistant messages", assistantMessageTokens),
                new Category("Tool calls", toolCallTokens),
                new Category("Tool results", toolResultTokens),
                new Category("Attachments", attachmentTokens)
        ).stream().filter(category -> category.tokens() > 0).toList();

        List<String> detailLines = new ArrayList<>();
        detailLines.add("Prompt messages sent to provider: " + promptMessages.size());
        detailLines.add("Post-compact session messages considered: " + SessionCompaction.messagesAfterCompactBoundary(activeSession.messages()).size());
        detailLines.add("Instruction files included: " + (instructionsPrompt.isBlank() ? 0 : 1));
        detailLines.add("Tool definitions included: " + availableTools.size());
        if (!instructionsPrompt.isBlank()) {
            detailLines.add("Instructions prompt contributes " + systemPromptTokens + " estimated tokens.");
        }
        if (activeSession.sessionMemoryState().initialized()) {
            detailLines.add("Session memory initialized at message " + nullSafe(activeSession.sessionMemoryState().lastSummarizedMessageId()) + ".");
        }

        List<String> warningLines = new ArrayList<>();
        double usagePercent = contextWindowTokens <= 0 ? 0.0 : (totalTokens * 100.0) / contextWindowTokens;
        if (usagePercent >= 90.0) {
            warningLines.add("Context is in the critical zone; the next turn is likely to compact or fail.");
        } else if (usagePercent >= 75.0) {
            warningLines.add("Context is hot; prefer compacting before another broad tool-heavy turn.");
        } else if (usagePercent >= 50.0) {
            warningLines.add("Context is warming; watch large file reads and web fetches.");
        }
        if (!availableTools.isEmpty() && toolDefinitionTokens == 0) {
            warningLines.add("Tool definition token accounting fell back to zero; check tool schema serialization.");
        }

        return new ContextReport(
                totalTokens,
                contextWindowTokens,
                classifyContextStatus(totalTokens, contextWindowTokens),
                categories,
                List.copyOf(detailLines),
                List.copyOf(warningLines)
        );
    }

    public int resolveContextWindowTokens(OpenClaudeState state) {
        if (state == null || state.activeProvider() == null) {
            return DEFAULT_CONTEXT_WINDOW_TOKENS;
        }
        return providerRegistry.find(state.activeProvider())
                .flatMap(providerPlugin -> providerPlugin.supportedModels().stream()
                        .filter(model -> state.activeModelId() != null && state.activeModelId().equals(model.id()))
                        .findFirst())
                .map(ModelDescriptor::contextWindowTokens)
                .filter(tokens -> tokens != null && tokens > 0)
                .orElse(DEFAULT_CONTEXT_WINDOW_TOKENS);
    }

    private List<ProviderToolDefinition> resolveAvailableTools(OpenClaudeState state) {
        if (state == null || state.activeProvider() == null) {
            return List.of();
        }
        return providerRegistry.find(state.activeProvider())
                .filter(providerPlugin -> providerPlugin.supportsTools())
                .map(ignored -> toolRuntime.toolDefinitions())
                .orElse(List.of());
    }

    private static int estimateToolDefinitionTokens(ProviderToolDefinition definition) {
        return estimateTokens(
                definition.name()
                        + "\n"
                        + definition.description()
                        + "\n"
                        + definition.inputSchemaJson()
                        + "\n"
                        + definition.providerType()
                        + "\n"
                        + definition.providerConfigJson()
        );
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }

    private static String classifyContextStatus(int estimatedTokens, int contextWindowTokens) {
        if (contextWindowTokens <= 0) {
            return "healthy";
        }
        double usage = estimatedTokens / (double) contextWindowTokens;
        if (usage < 0.5) {
            return "healthy";
        }
        if (usage < 0.75) {
            return "warming";
        }
        if (usage < 0.9) {
            return "hot";
        }
        return "critical";
    }

    private static List<PromptMessage> buildPromptMessages(
            ConversationSession session,
            List<String> additionalSystemPrompts,
            List<ProviderToolDefinition> availableTools,
            String instructionsPrompt
    ) {
        List<SessionMessage> messages = SessionCompaction.messagesAfterCompactBoundary(session.messages());
        List<PromptMessage> normalized = new ArrayList<>();
        normalized.add(new PromptMessage(PromptMessageRole.SYSTEM, DEFAULT_SYSTEM_PROMPT));
        if (instructionsPrompt != null && !instructionsPrompt.isBlank()) {
            normalized.add(new PromptMessage(PromptMessageRole.SYSTEM, instructionsPrompt));
        }
        for (String additionalSystemPrompt : additionalSystemPrompts) {
            if (additionalSystemPrompt != null && !additionalSystemPrompt.isBlank()) {
                normalized.add(new PromptMessage(PromptMessageRole.SYSTEM, additionalSystemPrompt));
            }
        }

        Set<String> allowedToolNames = availableTools.stream()
                .map(ProviderToolDefinition::name)
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        PendingToolTrajectory pendingTrajectory = null;

        for (SessionMessage message : messages) {
            if (message instanceof SessionMessage.AssistantMessage assistantMessage) {
                List<ToolUseContentBlock> retainedToolUses = assistantMessage.toolUses().stream()
                        .filter(toolUse -> toolUse.toolUseId() != null && !toolUse.toolUseId().isBlank())
                        .filter(toolUse -> toolUse.toolName() != null && !toolUse.toolName().isBlank())
                        .filter(toolUse -> allowedToolNames.contains(toolUse.toolName()))
                        .toList();
                String assistantText = assistantMessage.text();
                if (pendingTrajectory != null) {
                    normalized.addAll(pendingTrajectory.messages());
                    pendingTrajectory = null;
                }

                if (retainedToolUses.isEmpty()) {
                    if (!assistantText.isBlank()) {
                        normalized.add(new PromptMessage(
                                PromptMessageRole.ASSISTANT,
                                List.of(new TextContentBlock(assistantText))
                        ));
                    }
                    continue;
                }

                List<PromptContentBlock> content = new ArrayList<>();
                if (!assistantText.isBlank()) {
                    content.add(new TextContentBlock(assistantText));
                }
                content.addAll(retainedToolUses);
                pendingTrajectory = new PendingToolTrajectory(
                        new PromptMessage(PromptMessageRole.ASSISTANT, content),
                        retainedToolUses
                );
                continue;
            }

            if (message instanceof SessionMessage.ToolResultMessage toolResultMessage) {
                if (pendingTrajectory != null) {
                    pendingTrajectory.accept(toolResultMessage);
                }
                continue;
            }

            if (message instanceof SessionMessage.AttachmentMessage attachmentMessage) {
                if (pendingTrajectory != null && pendingTrajectory.accept(attachmentMessage)) {
                    continue;
                }
                if (pendingTrajectory != null) {
                    normalized.addAll(pendingTrajectory.messages());
                    pendingTrajectory = null;
                }
                normalized.addAll(message.toPromptMessages());
                continue;
            }

            if (message instanceof SessionMessage.UserMessage || message instanceof SessionMessage.SystemMessage) {
                if (pendingTrajectory != null) {
                    normalized.addAll(pendingTrajectory.messages());
                    pendingTrajectory = null;
                }
                normalized.addAll(message.toPromptMessages());
            }
        }

        if (pendingTrajectory != null) {
            normalized.addAll(pendingTrajectory.messages());
        }

        return List.copyOf(normalized);
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    public record Category(String name, int tokens) {
    }

    public record ContextReport(
            int estimatedTokens,
            int contextWindowTokens,
            String status,
            List<Category> categories,
            List<String> detailLines,
            List<String> warningLines
    ) {
    }

    private static final class PendingToolTrajectory {
        private final PromptMessage assistantMessage;
        private final LinkedHashMap<String, ToolUseContentBlock> expectedToolUses = new LinkedHashMap<>();
        private final LinkedHashMap<String, ToolResultContentBlock> deliveredToolResults = new LinkedHashMap<>();
        private final ArrayList<PromptMessage> preToolAttachments = new ArrayList<>();
        private final ArrayList<PromptMessage> postToolAttachments = new ArrayList<>();

        private PendingToolTrajectory(PromptMessage assistantMessage, List<ToolUseContentBlock> toolUses) {
            this.assistantMessage = assistantMessage;
            for (ToolUseContentBlock toolUse : toolUses) {
                expectedToolUses.put(toolUse.toolUseId(), toolUse);
            }
        }

        private void accept(SessionMessage.ToolResultMessage toolResultMessage) {
            ToolUseContentBlock toolUse = expectedToolUses.get(toolResultMessage.toolUseId());
            if (toolUse == null) {
                return;
            }
            deliveredToolResults.put(toolResultMessage.toolUseId(), new ToolResultContentBlock(
                    toolResultMessage.toolUseId(),
                    toolResultMessage.toolName(),
                    toolResultMessage.text(),
                    toolResultMessage.isError()
            ));
        }

        private boolean accept(SessionMessage.AttachmentMessage attachmentMessage) {
            SessionAttachment attachment = attachmentMessage.attachment();
            if (!(attachment instanceof SessionAttachment.HookAdditionalContextAttachment hookAttachment)) {
                return false;
            }
            String toolUseId = hookAttachment.toolUseId();
            if (toolUseId == null || toolUseId.isBlank() || !expectedToolUses.containsKey(toolUseId)) {
                return false;
            }
            PromptMessage promptMessage = new PromptMessage(PromptMessageRole.USER, attachment.summaryText());
            if (deliveredToolResults.containsKey(toolUseId)) {
                postToolAttachments.add(promptMessage);
            } else {
                preToolAttachments.add(promptMessage);
            }
            return true;
        }

        private List<PromptMessage> messages() {
            ArrayList<PromptMessage> messages = new ArrayList<>();
            messages.add(assistantMessage);
            messages.addAll(preToolAttachments);
            if (deliveredToolResults.size() == expectedToolUses.size()) {
                ArrayList<PromptContentBlock> toolResults = new ArrayList<>(deliveredToolResults.values());
                messages.add(new PromptMessage(PromptMessageRole.USER, toolResults));
            } else {
                for (ToolUseContentBlock toolUse : expectedToolUses.values()) {
                    ToolResultContentBlock toolResult = deliveredToolResults.get(toolUse.toolUseId());
                    if (toolResult == null) {
                        continue;
                    }
                    messages.add(new PromptMessage(PromptMessageRole.USER, List.of(toolResult)));
                }
            }
            messages.addAll(postToolAttachments);
            return List.copyOf(messages);
        }
    }
}
