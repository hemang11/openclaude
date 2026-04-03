package com.openclaude.core.tools;

@FunctionalInterface
public interface ToolPermissionGateway {
    ToolPermissionDecision requestPermission(ToolPermissionRequest request);

    default ToolPermissionDecision lookupPersistedDecision(ToolPermissionRequest request) {
        return null;
    }

    static ToolPermissionGateway allowAll() {
        return request -> ToolPermissionDecision.allow("auto-approved");
    }
}
