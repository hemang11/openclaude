package io.openclaude.app;

import io.openclaude.cli.OpenClaudeCli;
import io.openclaude.cli.ModelsCommand;
import io.openclaude.cli.ProviderCommand;
import io.openclaude.config.OpenClaudePaths;
import io.openclaude.config.ProviderConnectionStore;
import io.openclaude.core.EmptyModelCatalog;
import io.openclaude.core.StaticProviderRegistry;
import picocli.CommandLine;

public final class OpenClaudeApplication {
    private OpenClaudeApplication() {
    }

    public static void main(String[] args) {
        var paths = OpenClaudePaths.defaultPaths();
        var connectionStore = ProviderConnectionStore.createDefault(paths);
        var providerRegistry = new StaticProviderRegistry();
        var modelCatalog = new EmptyModelCatalog();

        var commandLine = new CommandLine(new OpenClaudeCli());
        commandLine.addSubcommand("provider", new ProviderCommand(providerRegistry, connectionStore));
        commandLine.addSubcommand("models", new ModelsCommand(modelCatalog, connectionStore));

        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }
}
