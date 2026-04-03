package com.openclaude.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BashToolRuntimeSmokeTest {
    private final BashToolRuntime runtime = new BashToolRuntime();

    @TempDir
    Path tempDir;

    @Test
    void executesReadOnlyPwdCommand() {
        List<ToolExecutionUpdate> updates = new ArrayList<>();

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "bash", "{\"command\":\"pwd\"}"),
                request -> {
                    throw new AssertionError("Read-only bash commands should not request permission.");
                },
                updates::add
        );

        assertFalse(result.error());
        assertTrue(result.text().contains("Command: pwd"));
        assertFalse(updates.stream().anyMatch(update -> "permission_requested".equals(update.phase())));
        assertTrue(updates.stream().anyMatch(update -> "completed".equals(update.phase())));
    }

    @Test
    void executesPlainEchoCommand() {
        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "bash", "{\"command\":\"echo hello\"}"),
                ToolPermissionGateway.allowAll(),
                null
        );

        assertFalse(result.error());
        assertTrue(result.text().contains("Command: echo hello"));
        assertTrue(result.text().contains("hello"));
    }

    @Test
    void executesCompoundReadOnlyCommandWithoutPermission() {
        List<ToolExecutionUpdate> updates = new ArrayList<>();

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "bash", "{\"command\":\"cd . && pwd | wc -c\"}"),
                request -> {
                    throw new AssertionError("Compound read-only bash commands should not request permission.");
                },
                updates::add
        );

        assertFalse(result.error());
        assertTrue(result.text().contains("Command: cd . && pwd | wc -c"));
        assertFalse(updates.stream().anyMatch(update -> "permission_requested".equals(update.phase())));
        assertTrue(updates.stream().anyMatch(update -> "completed".equals(update.phase())));
    }

    @Test
    void executesReadOnlyCommandWithDevNullRedirectWithoutPermission() {
        List<ToolExecutionUpdate> updates = new ArrayList<>();

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "bash", "{\"command\":\"cd . 2>/dev/null && find . -type f | wc -l\"}"),
                request -> {
                    throw new AssertionError("Read-only bash commands with /dev/null redirect should not request permission.");
                },
                updates::add
        );

        assertFalse(result.error());
        assertTrue(result.text().contains("Command: cd . 2>/dev/null && find . -type f | wc -l"));
        assertFalse(updates.stream().anyMatch(update -> "permission_requested".equals(update.phase())));
        assertTrue(updates.stream().anyMatch(update -> "completed".equals(update.phase())));
    }

    @Test
    void executesTildeScopedReadOnlyCommandWithoutPermission() {
        List<ToolExecutionUpdate> updates = new ArrayList<>();

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "bash", "{\"command\":\"cd ~ 2>/dev/null && pwd | wc -c\"}"),
                request -> {
                    throw new AssertionError("Read-only bash commands with a tilde path should not request permission.");
                },
                updates::add
        );

        assertFalse(result.error());
        assertTrue(result.text().contains("Command: cd ~ 2>/dev/null && pwd | wc -c"));
        assertFalse(updates.stream().anyMatch(update -> "permission_requested".equals(update.phase())));
        assertTrue(updates.stream().anyMatch(update -> "completed".equals(update.phase())));
    }

    @Test
    void executesSedBasedDirectoryCountWithoutPermission() {
        List<ToolExecutionUpdate> updates = new ArrayList<>();

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "bash", "{\"command\":\"cd ~ && find . -maxdepth 1 -type d | sed '1d' | wc -l\"}"),
                request -> {
                    throw new AssertionError("Read-only bash commands using sed should not request permission.");
                },
                updates::add
        );

        assertFalse(result.error());
        assertTrue(result.text().contains("Command: cd ~ && find . -maxdepth 1 -type d | sed '1d' | wc -l"));
        assertFalse(updates.stream().anyMatch(update -> "permission_requested".equals(update.phase())));
        assertTrue(updates.stream().anyMatch(update -> "completed".equals(update.phase())));
    }

    @Test
    void requestsPermissionForReadOnlyCommandWhenPersistedRuleRequiresAsk() {
        List<ToolExecutionUpdate> updates = new ArrayList<>();

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "bash", "{\"command\":\"pwd\"}"),
                new ToolPermissionGateway() {
                    @Override
                    public ToolPermissionDecision lookupPersistedDecision(ToolPermissionRequest request) {
                        return ToolPermissionDecision.ask("Approval required by persisted rule.");
                    }

                    @Override
                    public ToolPermissionDecision requestPermission(ToolPermissionRequest request) {
                        return ToolPermissionDecision.allow("approved after ask");
                    }
                },
                updates::add
        );

        assertFalse(result.error());
        assertTrue(updates.stream().anyMatch(update -> "permission_requested".equals(update.phase())));
        assertTrue(updates.stream().anyMatch(update ->
                "permission_requested".equals(update.phase())
                        && "bash".equals(update.interactionType())
        ));
        assertTrue(result.text().contains("Command: pwd"));
    }

    @Test
    void requestsPermissionForMutatingCommand() {
        List<ToolExecutionUpdate> updates = new ArrayList<>();

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "bash", "{\"command\":\"rm -rf /tmp/demo\"}"),
                request -> ToolPermissionDecision.deny("rejected"),
                updates::add
        );

        assertTrue(result.error());
        assertEquals("Permission denied: rejected", result.text());
        assertTrue(updates.stream().anyMatch(update -> "permission_requested".equals(update.phase())));
        assertTrue(updates.stream().anyMatch(update ->
                "permission_requested".equals(update.phase())
                        && "bash".equals(update.interactionType())
        ));
    }

    @Test
    void executesMutatingCommandWhenPermissionIsGranted() throws Exception {
        Path file = tempDir.resolve("mutated.txt");

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "bash", "{\"command\":\"echo hello > " + escapeForJson(file) + "\"}"),
                ToolPermissionGateway.allowAll(),
                null
        );

        assertFalse(result.error());
        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file).contains("hello"));
        assertTrue(result.text().contains("Command: echo hello > " + file));
    }

    @Test
    void executesUpdatedInputReturnedFromPermissionApproval() throws Exception {
        Path originalFile = tempDir.resolve("original.txt");
        Path approvedFile = tempDir.resolve("approved.txt");

        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "bash", "{\"command\":\"echo hello > " + escapeForJson(originalFile) + "\"}"),
                request -> ToolPermissionDecision.allow(
                        "approved with rewritten command",
                        "",
                        "{\"command\":\"echo approved > " + escapeForJson(approvedFile) + "\"}",
                        true
                ),
                null
        );

        assertFalse(result.error());
        assertFalse(Files.exists(originalFile));
        assertTrue(Files.exists(approvedFile));
        assertTrue(Files.readString(approvedFile).contains("approved"));
        assertTrue(result.text().contains("Command: echo approved > " + approvedFile));
    }

    @Test
    void treatsRipgrepNoMatchesAsInformational() {
        ToolExecutionResult result = runtime.execute(
                new ToolExecutionRequest("tool-1", "bash", "{\"command\":\"rg definitely_not_present_" + System.nanoTime() + " .\"}"),
                ToolPermissionGateway.allowAll(),
                null
        );

        assertFalse(result.error());
        assertTrue(result.text().contains("Exit code: 1"));
    }

    private static String escapeForJson(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
