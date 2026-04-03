package com.openclaude.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openclaude.core.session.ConversationSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileReadToolRuntimeTest {
    private final FileReadToolRuntime runtime = new FileReadToolRuntime();

    @TempDir
    Path tempDir;

    @Test
    void readsFileInCatNStyleOutput() throws IOException {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "alpha\nbeta\ngamma\n", StandardCharsets.UTF_8);

        ToolExecutionResult result = runtime.execute(new ToolExecutionRequest(
                "tool-1",
                "Read",
                "{\"file_path\":\"" + escape(file) + "\"}"
        ));

        assertFalse(result.error());
        assertEquals("1\talpha\n2\tbeta\n3\tgamma", result.text());
    }

    @Test
    void returnsOffsetWarningWhenPastEndOfFile() throws IOException {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "alpha\nbeta\n", StandardCharsets.UTF_8);

        ToolExecutionResult result = runtime.execute(new ToolExecutionRequest(
                "tool-1",
                "Read",
                "{\"file_path\":\"" + escape(file) + "\",\"offset\":99}"
        ));

        assertFalse(result.error());
        assertTrue(result.text().contains("shorter than the provided offset"));
        assertTrue(result.text().contains("2 lines"));
    }

    @Test
    void recordsReadStateInTheSessionEffect() throws IOException {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "alpha\nbeta\n", StandardCharsets.UTF_8);
        ConversationSession session = ConversationSession.create("session", tempDir.toString(), tempDir.toString());

        ToolExecutionResult result = runtime.execute(new ToolExecutionRequest(
                "tool-1",
                "Read",
                "{\"file_path\":\"" + escape(file) + "\"}",
                session
        ));

        ConversationSession updatedSession = result.sessionEffect().apply(session);
        assertTrue(updatedSession.readFileState().containsKey(file.toAbsolutePath().normalize().toString()));
        assertEquals("alpha\nbeta\n", updatedSession.readFileState().get(file.toAbsolutePath().normalize().toString()).content());
        assertTrue(updatedSession.readFileState().get(file.toAbsolutePath().normalize().toString()).isFullView());
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
