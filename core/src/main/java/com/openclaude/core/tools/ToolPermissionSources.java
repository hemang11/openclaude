package com.openclaude.core.tools;

import java.util.List;
import java.util.Locale;

public final class ToolPermissionSources {
    public static final String USER_SETTINGS = "userSettings";
    public static final String PROJECT_SETTINGS = "projectSettings";
    public static final String LOCAL_SETTINGS = "localSettings";
    public static final String POLICY_SETTINGS = "policySettings";
    public static final String SESSION = "session";

    private static final List<String> PRECEDENCE = List.of(
            USER_SETTINGS,
            PROJECT_SETTINGS,
            LOCAL_SETTINGS,
            POLICY_SETTINGS,
            SESSION
    );

    private ToolPermissionSources() {
    }

    public static String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "user", "usersettings" -> USER_SETTINGS;
            case "project", "projectsettings" -> PROJECT_SETTINGS;
            case "local", "localsettings" -> LOCAL_SETTINGS;
            case "managed", "policy", "policysettings" -> POLICY_SETTINGS;
            case "session" -> SESSION;
            default -> SESSION;
        };
    }

    public static boolean isEditable(String source) {
        String normalized = normalize(source);
        return SESSION.equals(normalized)
                || USER_SETTINGS.equals(normalized)
                || PROJECT_SETTINGS.equals(normalized)
                || LOCAL_SETTINGS.equals(normalized);
    }

    public static List<String> precedence() {
        return PRECEDENCE;
    }

    public static String displayName(String source) {
        return switch (normalize(source)) {
            case USER_SETTINGS -> "User settings";
            case PROJECT_SETTINGS -> "Shared project settings";
            case LOCAL_SETTINGS -> "Project local settings";
            case POLICY_SETTINGS -> "Enterprise managed settings";
            case SESSION -> "Current session";
            default -> "Current session";
        };
    }
}
