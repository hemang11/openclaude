package com.openclaude.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShellPermissionPolicyTest {
    private final ShellPermissionPolicy policy = new ShellPermissionPolicy();

    @Test
    void allowsPlainEchoWithoutRedirection() {
        ShellPermissionPolicy.PermissionDecision decision = policy.evaluate("echo hello");

        assertTrue(decision.allowsExecutionWithoutPrompt());
        assertEquals("allowed", decision.reason());
    }

    @Test
    void asksForApprovalWhenEchoRedirectsToAFile() {
        ShellPermissionPolicy.PermissionDecision decision = policy.evaluate("echo hello > /tmp/file.txt");

        assertTrue(decision.requiresApproval());
        assertEquals("Shell command uses redirection and requires approval.", decision.reason());
    }

    @Test
    void allowsCompoundReadOnlyCommands() {
        ShellPermissionPolicy.PermissionDecision decision = policy.evaluate("cd . && ls | wc -l");

        assertTrue(decision.allowsExecutionWithoutPrompt());
        assertEquals("allowed", decision.reason());
    }

    @Test
    void allowsMultilineReadOnlyCommands() {
        ShellPermissionPolicy.PermissionDecision decision = policy.evaluate("pwd\nls");

        assertTrue(decision.allowsExecutionWithoutPrompt());
        assertEquals("allowed", decision.reason());
    }

    @Test
    void allowsSafeDevNullStderrRedirection() {
        ShellPermissionPolicy.PermissionDecision decision = policy.evaluate("cd . 2>/dev/null && find . -type f | wc -l");

        assertTrue(decision.allowsExecutionWithoutPrompt());
        assertEquals("allowed", decision.reason());
    }

    @Test
    void allowsDesktopStyleReadOnlyCommandGeneratedByTheModel() {
        ShellPermissionPolicy.PermissionDecision decision = policy.evaluate("cd ~/desktop 2>/dev/null && find . -type f | wc -l");

        assertTrue(decision.allowsExecutionWithoutPrompt());
        assertEquals("allowed", decision.reason());
    }

    @Test
    void allowsDesktopDirectoryCountCommandGeneratedByTheModel() {
        ShellPermissionPolicy.PermissionDecision decision = policy.evaluate("cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l");

        assertTrue(decision.allowsExecutionWithoutPrompt());
        assertEquals("allowed", decision.reason());
    }

    @Test
    void asksForApprovalForMutatingCommands() {
        ShellPermissionPolicy.PermissionDecision decision = policy.evaluate("rm -rf /tmp/demo");

        assertTrue(decision.requiresApproval());
        assertEquals("Shell command requires approval: rm", decision.reason());
    }

    @Test
    void asksForApprovalForMutatingGitBranchFlags() {
        ShellPermissionPolicy.PermissionDecision decision = policy.evaluate("git branch -D old-feature");

        assertTrue(decision.requiresApproval());
        assertEquals("Shell command requires approval: git branch", decision.reason());
    }

    @Test
    void asksForApprovalForSedWriteCommands() {
        ShellPermissionPolicy.PermissionDecision decision = policy.evaluate("sed 'w /tmp/out.txt' README.md");

        assertTrue(decision.requiresApproval());
        assertEquals("Shell command requires approval: sed script", decision.reason());
    }

    @Test
    void asksForApprovalForExternalCdThenGitStatus() {
        ShellPermissionPolicy.PermissionDecision decision = policy.evaluate("cd /tmp && git status");

        assertTrue(decision.requiresApproval());
        assertEquals("Shell command requires approval: cd && git", decision.reason());
    }

    @Test
    void stillBlocksBackgroundOperators() {
        ShellPermissionPolicy.PermissionDecision decision = policy.evaluate("pwd &");

        assertTrue(decision.denied());
        assertEquals("Shell redirection and background operators are not allowed.", decision.reason());
    }
}
