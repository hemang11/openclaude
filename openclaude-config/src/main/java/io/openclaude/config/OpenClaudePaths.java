package io.openclaude.config;

import java.nio.file.Path;
import java.nio.file.Paths;

public record OpenClaudePaths(
        Path homeDirectory,
        Path configFile,
        Path sessionsDirectory,
        Path providersDirectory) {
    public static OpenClaudePaths defaultPaths() {
        Path home = Paths.get(System.getProperty("user.home"), ".openclaude");
        return new OpenClaudePaths(
                home,
                home.resolve("settings.json"),
                home.resolve("sessions"),
                home.resolve("providers"));
    }

    public Path providerConnectionsFile() {
        return providersDirectory.resolve("connections.json");
    }
}
