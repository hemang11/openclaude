package com.openclaude.core.tools;

public record ToolPermissionDecision(
        Behavior behavior,
        String reason,
        String responseJson,
        String updatedInputJson,
        boolean interrupt,
        boolean userModified,
        boolean acceptFeedback
) {
    public enum Behavior {
        ALLOW,
        ASK,
        DENY,
        PASSTHROUGH
    }

    public ToolPermissionDecision {
        behavior = behavior == null ? Behavior.DENY : behavior;
        reason = reason == null ? "" : reason;
        responseJson = responseJson == null ? "" : responseJson;
        updatedInputJson = updatedInputJson == null ? "" : updatedInputJson;
    }

    public boolean allowed() {
        return behavior == Behavior.ALLOW || behavior == Behavior.PASSTHROUGH;
    }

    public boolean denied() {
        return behavior == Behavior.DENY;
    }

    public boolean asks() {
        return behavior == Behavior.ASK;
    }

    public boolean passthrough() {
        return behavior == Behavior.PASSTHROUGH;
    }

    public static ToolPermissionDecision allow(String reason) {
        return new ToolPermissionDecision(Behavior.ALLOW, reason, "", "", false, false, false);
    }

    public static ToolPermissionDecision allow(String reason, String responseJson) {
        return new ToolPermissionDecision(Behavior.ALLOW, reason, responseJson, "", false, false, false);
    }

    public static ToolPermissionDecision allow(
            String reason,
            String responseJson,
            String updatedInputJson,
            boolean userModified
    ) {
        return new ToolPermissionDecision(
                Behavior.ALLOW,
                reason,
                responseJson,
                updatedInputJson,
                false,
                userModified,
                false
        );
    }

    public static ToolPermissionDecision ask(String reason) {
        return new ToolPermissionDecision(Behavior.ASK, reason, "", "", false, false, false);
    }

    public static ToolPermissionDecision deny(String reason) {
        return new ToolPermissionDecision(Behavior.DENY, reason, "", "", false, false, false);
    }

    public static ToolPermissionDecision deny(String reason, String responseJson) {
        return new ToolPermissionDecision(Behavior.DENY, reason, responseJson, "", false, false, false);
    }

    public static ToolPermissionDecision denyInterrupt(String reason) {
        return new ToolPermissionDecision(Behavior.DENY, reason, "", "", true, false, false);
    }

    public static ToolPermissionDecision denyInterrupt(String reason, String responseJson) {
        return new ToolPermissionDecision(Behavior.DENY, reason, responseJson, "", true, false, false);
    }

    public static ToolPermissionDecision passthrough(String reason) {
        return new ToolPermissionDecision(Behavior.PASSTHROUGH, reason, "", "", false, false, false);
    }
}
