package com.openclaude.core.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrepToolRuntimeTest {
    private final GrepToolRuntime runtime = new GrepToolRuntime();

    @TempDir
    Path tempDir;

    @Test
    void returnsMatchingFilesByDefault() throws IOException {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "hello\nworld\n", StandardCharsets.UTF_8);

        ToolExecutionResult result = runtime.execute(new ToolExecutionRequest(
                "tool-1",
                "Grep",
                "{\"pattern\":\"hello\",\"path\":\"" + escape(tempDir) + "\"}"
        ));

        assertFalse(result.error());
        assertTrue(result.text().startsWith("Found 1 file"));
        assertTrue(result.text().contains("sample.txt"));
    }

    @Test
    void returnsContentModeResults() throws IOException {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "alpha\nhello beta\nhello gamma\n", StandardCharsets.UTF_8);

        ToolExecutionResult result = runtime.execute(new ToolExecutionRequest(
                "tool-1",
                "Grep",
                "{\"pattern\":\"hello\",\"path\":\"" + escape(tempDir) + "\",\"output_mode\":\"content\",\"head_limit\":1}"
        ));

        assertFalse(result.error());
        assertTrue(result.text().contains("sample.txt:2:hello beta"));
        assertTrue(result.text().contains("pagination = limit: 1"));
    }

    @Test
    void returnsCountModeSummary() throws IOException {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "hello\nhello\n", StandardCharsets.UTF_8);

        ToolExecutionResult result = runtime.execute(new ToolExecutionRequest(
                "tool-1",
                "Grep",
                "{\"pattern\":\"hello\",\"path\":\"" + escape(tempDir) + "\",\"output_mode\":\"count\"}"
        ));

        assertFalse(result.error());
        assertTrue(result.text().contains("sample.txt:2"));
        assertTrue(result.text().contains("Found 2 total occurrences across 1 file."));
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
