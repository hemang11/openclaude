package com.openclaude.core.config;

import java.nio.file.Path;

public final class OpenClaudePaths {
    private static final String OPENCLAUDE_HOME = "OPENCLAUDE_HOME";

    private OpenClaudePaths() {
    }

    public static Path configHome() {
        String override = System.getenv(OPENCLAUDE_HOME);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".openclaude");
    }

    public static Path homeDirectory() {
        return configHome();
    }

    public static Path stateFile() {
        return configHome().resolve("state.json");
    }

    public static Path sessionsDirectory() {
        return configHome().resolve("sessions");
    }

    public static Path sessionMemoryDirectory() {
        return configHome().resolve("session-memory");
    }

    public static Path sessionMemoryConfigDirectory() {
        return sessionMemoryDirectory().resolve("config");
    }

    public static Path sessionMemorySessionsDirectory() {
        return sessionMemoryDirectory().resolve("sessions");
    }

    public static Path sessionMemoryTemplatePath() {
        return sessionMemoryConfigDirectory().resolve("template.md");
    }

    public static Path sessionMemoryPromptPath() {
        return sessionMemoryConfigDirectory().resolve("prompt.md");
    }

    public static Path sessionMemoryPath(String sessionId) {
        return sessionMemorySessionsDirectory().resolve(sessionId + ".md");
    }
}
