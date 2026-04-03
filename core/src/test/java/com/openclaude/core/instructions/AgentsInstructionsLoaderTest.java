package com.openclaude.core.instructions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openclaude.core.session.ConversationSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentsInstructionsLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsUserProjectLocalAndNestedInstructionsInClaudeOrder() throws IOException {
        Path managedRoot = tempDir.resolve("managed");
        Path userRoot = tempDir.resolve("user");
        Path workspace = tempDir.resolve("workspace");
        Path nested = workspace.resolve("src").resolve("feature");

        Files.createDirectories(managedRoot.resolve("rules"));
        Files.createDirectories(userRoot.resolve("rules"));
        Files.createDirectories(workspace.resolve(".openclaude").resolve("rules"));
        Files.createDirectories(nested);

        Files.writeString(managedRoot.resolve("AGENTS.md"), "managed instructions");
        Files.writeString(managedRoot.resolve("rules").resolve("managed-rule.md"), "managed rule");
        Files.writeString(userRoot.resolve("AGENTS.md"), "user instructions");
        Files.writeString(userRoot.resolve("rules").resolve("user-rule.md"), "user rule");
        Files.writeString(workspace.resolve("AGENTS.md"), "project root instructions");
        Files.writeString(workspace.resolve(".openclaude").resolve("AGENTS.md"), "project dot instructions");
        Files.writeString(workspace.resolve(".openclaude").resolve("rules").resolve("workspace-rule.md"), "workspace rule");
        Files.writeString(workspace.resolve("AGENTS.local.md"), "project local instructions");
        Files.writeString(nested.resolve("AGENTS.md"), "nested project instructions");
        Files.writeString(nested.resolve("AGENTS.local.md"), "nested local instructions");

        AgentsInstructionsLoader loader = new AgentsInstructionsLoader(managedRoot, userRoot);
        ConversationSession session = ConversationSession.create("session-1", nested.toString(), workspace.toString());

        List<InstructionFile> instructions = loader.load(session);

        assertEquals(
                List.of(
                        managedRoot.resolve("AGENTS.md").toAbsolutePath().normalize(),
                        managedRoot.resolve("rules").resolve("managed-rule.md").toAbsolutePath().normalize(),
                        userRoot.resolve("AGENTS.md").toAbsolutePath().normalize(),
                        userRoot.resolve("rules").resolve("user-rule.md").toAbsolutePath().normalize(),
                        workspace.resolve("AGENTS.md").toAbsolutePath().normalize(),
                        workspace.resolve(".openclaude").resolve("AGENTS.md").toAbsolutePath().normalize(),
                        workspace.resolve(".openclaude").resolve("rules").resolve("workspace-rule.md").toAbsolutePath().normalize(),
                        workspace.resolve("AGENTS.local.md").toAbsolutePath().normalize(),
                        nested.resolve("AGENTS.md").toAbsolutePath().normalize(),
                        nested.resolve("AGENTS.local.md").toAbsolutePath().normalize()
                ),
                instructions.stream().map(InstructionFile::path).toList()
        );
    }

    @Test
    void followsAtIncludesAndAvoidsCycles() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        Path includeA = workspace.resolve("shared.md");
        Path includeB = workspace.resolve("details.md");

        Files.writeString(workspace.resolve("AGENTS.md"), """
                root instructions
                @./shared.md
                """);
        Files.writeString(includeA, """
                shared instructions
                @./details.md
                """);
        Files.writeString(includeB, """
                detail instructions
                @./shared.md
                """);

        AgentsInstructionsLoader loader = new AgentsInstructionsLoader(null, null);
        ConversationSession session = ConversationSession.create("session-1", workspace.toString(), workspace.toString());

        List<InstructionFile> instructions = loader.load(session);

        assertEquals(
                List.of(
                        workspace.resolve("AGENTS.md").toAbsolutePath().normalize(),
                        includeA.toAbsolutePath().normalize(),
                        includeB.toAbsolutePath().normalize()
                ),
                instructions.stream().map(InstructionFile::path).toList()
        );
        assertEquals(workspace.resolve("AGENTS.md").toAbsolutePath().normalize(), instructions.get(1).parent());
        assertEquals(includeA.toAbsolutePath().normalize(), instructions.get(2).parent());
    }

    @Test
    void rendersSystemPromptWithAgentsContent() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "follow repo rules");

        AgentsInstructionsLoader loader = new AgentsInstructionsLoader(null, null);
        ConversationSession session = ConversationSession.create("session-1", workspace.toString(), workspace.toString());

        String prompt = loader.renderSystemPrompt(session);

        assertTrue(prompt.contains("Codebase and user instructions are shown below."));
        assertTrue(prompt.contains("Contents of " + workspace.resolve("AGENTS.md").toAbsolutePath().normalize()));
        assertTrue(prompt.contains("follow repo rules"));
    }
}
