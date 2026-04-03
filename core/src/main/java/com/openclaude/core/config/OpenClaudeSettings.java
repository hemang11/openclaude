package com.openclaude.core.config;

import com.openclaude.core.tools.ToolPermissionRule;
import java.util.List;

public record OpenClaudeSettings(
        boolean fastMode,
        boolean verboseOutput,
        boolean reasoningVisible,
        boolean alwaysCopyFullResponse,
        String effortLevel,
        List<ToolPermissionRule> permissionRules
) {
    public OpenClaudeSettings {
        effortLevel = OpenClaudeEffort.normalizeConfiguredValue(effortLevel);
        permissionRules = permissionRules == null ? List.of() : List.copyOf(permissionRules);
    }

    public static OpenClaudeSettings defaults() {
        return new OpenClaudeSettings(false, false, true, false, null, List.of());
    }

    public OpenClaudeSettings withFastMode(boolean enabled) {
        return new OpenClaudeSettings(enabled, verboseOutput, reasoningVisible, alwaysCopyFullResponse, effortLevel, permissionRules);
    }

    public OpenClaudeSettings withVerboseOutput(boolean enabled) {
        return new OpenClaudeSettings(fastMode, enabled, reasoningVisible, alwaysCopyFullResponse, effortLevel, permissionRules);
    }

    public OpenClaudeSettings withReasoningVisible(boolean enabled) {
        return new OpenClaudeSettings(fastMode, verboseOutput, enabled, alwaysCopyFullResponse, effortLevel, permissionRules);
    }

    public OpenClaudeSettings withAlwaysCopyFullResponse(boolean enabled) {
        return new OpenClaudeSettings(fastMode, verboseOutput, reasoningVisible, enabled, effortLevel, permissionRules);
    }

    public OpenClaudeSettings withEffortLevel(String value) {
        return new OpenClaudeSettings(fastMode, verboseOutput, reasoningVisible, alwaysCopyFullResponse, value, permissionRules);
    }

    public OpenClaudeSettings withPermissionRules(List<ToolPermissionRule> rules) {
        return new OpenClaudeSettings(fastMode, verboseOutput, reasoningVisible, alwaysCopyFullResponse, effortLevel, rules);
    }
}
