package com.openclaude.core.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openclaude.core.session.ConversationSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileEditToolRuntimeTest {
    private final FileReadToolRuntime readRuntime = new FileReadToolRuntime();
    private final FileEditToolRuntime runtime = new FileEditToolRuntime();

    @TempDir
    Path tempDir;

    @Test
    void editsUniqueMatchWhenPermissionIsGranted() throws IOException {
        Path file = tempDir.resolve("edit.txt");
        Files.writeString(file, "alpha\nbeta\n", StandardCharsets.UTF_8);
        ConversationSession session = readFullFile(file);

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-1",
                        "Edit",
                        "{\"file_path\":\"" + escape(file) + "\",\"old_string\":\"beta\",\"new_string\":\"gamma\"}",
                        session
                ),
                request -> ToolPermissionDecision.allow("approved"),
                update -> {}
        );

        assertFalse(result.error());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("gamma"));
        assertTrue(result.text().contains("Edited " + file));
        assertTrue(result.displayText().contains("--- " + file));
        assertTrue(result.displayText().contains("-beta"));
        assertTrue(result.displayText().contains("+gamma"));
    }

    @Test
    void rejectsNonUniqueEditWithoutReplaceAll() throws IOException {
        Path file = tempDir.resolve("edit.txt");
        Files.writeString(file, "hello\nhello\n", StandardCharsets.UTF_8);
        ConversationSession session = readFullFile(file);

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-1",
                        "Edit",
                        "{\"file_path\":\"" + escape(file) + "\",\"old_string\":\"hello\",\"new_string\":\"world\"}",
                        session
                ),
                request -> ToolPermissionDecision.allow("approved"),
                update -> {}
        );

        assertTrue(result.error());
        assertTrue(result.text().contains("not unique"));
    }

    @Test
    void rejectsEditWhenFileHasNotBeenReadYet() throws IOException {
        Path file = tempDir.resolve("edit.txt");
        Files.writeString(file, "alpha\nbeta\n", StandardCharsets.UTF_8);
        ConversationSession session = ConversationSession.create("session", tempDir.toString(), tempDir.toString());

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-1",
                        "Edit",
                        "{\"file_path\":\"" + escape(file) + "\",\"old_string\":\"beta\",\"new_string\":\"gamma\"}",
                        session
                ),
                request -> ToolPermissionDecision.allow("approved"),
                update -> {}
        );

        assertTrue(result.error());
        assertTrue(result.text().contains("File has not been read yet"));
    }

    @Test
    void rejectsEditWhenFileChangedAfterRead() throws IOException {
        Path file = tempDir.resolve("edit.txt");
        Files.writeString(file, "alpha\nbeta\n", StandardCharsets.UTF_8);
        ConversationSession session = readFullFile(file);
        long originalTimestamp = session.readFileState().get(file.toAbsolutePath().normalize().toString()).timestamp();

        Files.writeString(file, "alpha\nbeta\n# comment\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(originalTimestamp + 2_000L));

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-1",
                        "Edit",
                        "{\"file_path\":\"" + escape(file) + "\",\"old_string\":\"beta\",\"new_string\":\"gamma\"}",
                        session
                ),
                request -> ToolPermissionDecision.allow("approved"),
                update -> {}
        );

        assertTrue(result.error());
        assertTrue(result.text().contains("File has been modified since read"));
    }

    @Test
    void editsUsingUpdatedInputReturnedFromPermissionApproval() throws IOException {
        Path originalFile = tempDir.resolve("original-edit.txt");
        Path approvedFile = tempDir.resolve("approved-edit.txt");
        Files.writeString(originalFile, "alpha\nbeta\n", StandardCharsets.UTF_8);
        Files.writeString(approvedFile, "one\ntwo\n", StandardCharsets.UTF_8);
        ConversationSession session = readFullFile(approvedFile);

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-1",
                        "Edit",
                        "{\"file_path\":\"" + escape(originalFile) + "\",\"old_string\":\"beta\",\"new_string\":\"gamma\"}",
                        session
                ),
                request -> ToolPermissionDecision.allow(
                        "approved with updated input",
                        "",
                        "{\"file_path\":\"" + escape(approvedFile) + "\",\"old_string\":\"two\",\"new_string\":\"three\"}",
                        true
                ),
                update -> {}
        );

        assertFalse(result.error());
        assertTrue(Files.readString(originalFile, StandardCharsets.UTF_8).contains("beta"));
        assertTrue(Files.readString(approvedFile, StandardCharsets.UTF_8).contains("three"));
        assertTrue(result.text().contains(approvedFile.toString()));
    }

    private ConversationSession readFullFile(Path file) {
        ConversationSession session = ConversationSession.create("session", tempDir.toString(), tempDir.toString());
        ToolExecutionResult readResult = readRuntime.execute(new ToolExecutionRequest(
                "read-1",
                "Read",
                "{\"file_path\":\"" + escape(file) + "\"}",
                session
        ));
        return readResult.sessionEffect().apply(session);
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
