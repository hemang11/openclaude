package com.openclaude.core.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobToolRuntimeTest {
    private final GlobToolRuntime runtime = new GlobToolRuntime();

    @TempDir
    Path tempDir;

    @Test
    void findsMatchingFiles() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(src.resolve("App.java"), "class App {}", StandardCharsets.UTF_8);
        Files.writeString(src.resolve("AppTest.java"), "class AppTest {}", StandardCharsets.UTF_8);

        ToolExecutionResult result = runtime.execute(new ToolExecutionRequest(
                "tool-1",
                "Glob",
                "{\"pattern\":\"**/*.java\",\"path\":\"" + escape(tempDir) + "\"}"
        ));

        assertFalse(result.error());
        assertTrue(result.text().contains("App.java"));
        assertTrue(result.text().contains("AppTest.java"));
    }

    @Test
    void returnsNoFilesFoundWhenEmpty() {
        ToolExecutionResult result = runtime.execute(new ToolExecutionRequest(
                "tool-1",
                "Glob",
                "{\"pattern\":\"**/*.java\",\"path\":\"" + escape(tempDir) + "\"}"
        ));

        assertFalse(result.error());
        assertTrue(result.text().contains("No files found"));
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
