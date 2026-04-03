package com.openclaude.core.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openclaude.core.session.ConversationSession;
import java.io.IOException;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileWriteToolRuntimeTest {
    private final FileReadToolRuntime readRuntime = new FileReadToolRuntime();
    private final FileWriteToolRuntime runtime = new FileWriteToolRuntime();

    @TempDir
    Path tempDir;

    @Test
    void writesFileWhenPermissionIsGranted() throws IOException {
        Path file = tempDir.resolve("write.txt");

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "Write", "{\"file_path\":\"" + escape(file) + "\",\"content\":\"hello\"}"),
                request -> ToolPermissionDecision.allow("approved"),
                update -> {}
        );

        assertFalse(result.error());
        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).equals("hello"));
        assertTrue(result.text().contains("Wrote " + file));
        assertTrue(result.displayText().contains("--- " + file));
        assertTrue(result.displayText().contains("+hello"));
    }

    @Test
    void deniesWriteWhenPermissionIsRejected() {
        Path file = tempDir.resolve("write.txt");

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "Write", "{\"file_path\":\"" + escape(file) + "\",\"content\":\"hello\"}"),
                request -> ToolPermissionDecision.deny("rejected"),
                update -> {}
        );

        assertTrue(result.error());
        assertTrue(result.text().contains("Permission denied"));
    }

    @Test
    void writesFileWhenPersistedPermissionRuleMatchesWithoutPrompting() throws IOException {
        Path file = tempDir.resolve("write-persisted.txt");
        ArrayList<ToolExecutionUpdate> updates = new ArrayList<>();

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "Write", "{\"file_path\":\"" + escape(file) + "\",\"content\":\"hello\"}"),
                new ToolPermissionGateway() {
                    @Override
                    public ToolPermissionDecision lookupPersistedDecision(ToolPermissionRequest request) {
                        return ToolPermissionRule.allow(request).toDecision();
                    }

                    @Override
                    public ToolPermissionDecision requestPermission(ToolPermissionRequest request) {
                        fail("requestPermission should not be called when a persisted rule matches");
                        return ToolPermissionDecision.deny("unexpected");
                    }
                },
                updates::add
        );

        assertFalse(result.error());
        assertTrue(Files.exists(file));
        assertTrue(updates.stream().noneMatch(update -> "permission_requested".equals(update.phase())));
    }

    @Test
    void writesFileUsingUpdatedInputReturnedFromPermissionApproval() throws IOException {
        Path originalFile = tempDir.resolve("original-write.txt");
        Path approvedFile = tempDir.resolve("approved-write.txt");

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest(
                        "tool-1",
                        "Write",
                        "{\"file_path\":\"" + escape(originalFile) + "\",\"content\":\"original\"}"
                ),
                request -> ToolPermissionDecision.allow(
                        "approved with updated input",
                        "",
                        "{\"file_path\":\"" + escape(approvedFile) + "\",\"content\":\"approved\"}",
                        true
                ),
                update -> {}
        );

        assertFalse(result.error());
        assertFalse(Files.exists(originalFile));
        assertEquals("approved", Files.readString(approvedFile, StandardCharsets.UTF_8));
        assertTrue(result.text().contains(approvedFile.toString()));
    }

    @Test
    void rejectsOverwriteWhenFileHasNotBeenReadYet() throws IOException {
        Path file = tempDir.resolve("write.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        ConversationSession session = ConversationSession.create("session", tempDir.toString(), tempDir.toString());

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "Write", "{\"file_path\":\"" + escape(file) + "\",\"content\":\"updated\"}", session),
                request -> ToolPermissionDecision.allow("approved"),
                update -> {}
        );

        assertTrue(result.error());
        assertTrue(result.text().contains("File has not been read yet"));
    }

    @Test
    void overwritesExistingFileAfterRead() throws IOException {
        Path file = tempDir.resolve("write.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        ConversationSession session = readFullFile(file);

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "Write", "{\"file_path\":\"" + escape(file) + "\",\"content\":\"updated\"}", session),
                request -> ToolPermissionDecision.allow("approved"),
                update -> {}
        );

        assertFalse(result.error());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).equals("updated"));
        assertTrue(result.text().contains("Updated " + file));
        assertTrue(result.displayText().contains("--- " + file));
        assertTrue(result.displayText().contains("-hello"));
        assertTrue(result.displayText().contains("+updated"));
    }

    @Test
    void rejectsOverwriteWhenFileChangedAfterRead() throws IOException {
        Path file = tempDir.resolve("write.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        ConversationSession session = readFullFile(file);
        long originalTimestamp = session.readFileState().get(file.toAbsolutePath().normalize().toString()).timestamp();

        Files.writeString(file, "hello there", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(originalTimestamp + 2_000L));

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "Write", "{\"file_path\":\"" + escape(file) + "\",\"content\":\"updated\"}", session),
                request -> ToolPermissionDecision.allow("approved"),
                update -> {}
        );

        assertTrue(result.error());
        assertTrue(result.text().contains("File has been modified since read"));
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
