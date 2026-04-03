package com.openclaude.cli;

import com.openclaude.cli.commands.ModelsCommand;
import com.openclaude.cli.commands.ProviderCommands;
import com.openclaude.cli.service.CommandService;
import com.openclaude.cli.service.ModelService;
import com.openclaude.cli.service.ProviderService;
import com.openclaude.cli.service.SessionService;
import com.openclaude.cli.stdio.StdioServerCommand;
import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.provider.PromptRouter;
import com.openclaude.core.provider.ProviderRegistry;
import com.openclaude.core.session.ConversationSessionStore;
import com.openclaude.core.tools.DefaultToolRuntime;
import com.openclaude.core.tools.ToolRuntime;
import com.openclaude.provider.openai.OpenAiBrowserAuthService;
import java.nio.file.Path;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

public final class OpenClaudeApplication {
    private OpenClaudeApplication() {
    }

    public static void main(String[] args) {
        ProviderRegistry providerRegistry = new ProviderRegistry();
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore();
        ConversationSessionStore sessionStore = new ConversationSessionStore();
        PromptRouter promptRouter = new PromptRouter(providerRegistry, stateStore, sessionStore);
        ToolRuntime toolRuntime = new DefaultToolRuntime();
        OpenAiBrowserAuthService openAiBrowserAuthService = new OpenAiBrowserAuthService();
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, openAiBrowserAuthService);
        ModelService modelService = new ModelService(providerRegistry, stateStore);
        Path currentDirectory = Path.of("").toAbsolutePath().normalize();
        SessionService sessionService = new SessionService(stateStore, sessionStore, currentDirectory);
        CommandService commandService = new CommandService(stateStore, sessionStore, providerRegistry, toolRuntime, sessionService);
        providerService.state();

        OpenClaudeCommand rootCommand = new OpenClaudeCommand();
        CommandLine commandLine = new CommandLine(rootCommand);
        commandLine.addSubcommand("provider", ProviderCommands.create(providerRegistry, stateStore, openAiBrowserAuthService));
        commandLine.addSubcommand("models", new ModelsCommand(providerRegistry, stateStore));
        commandLine.addSubcommand("stdio", new StdioServerCommand(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        ));

        ParseResult parseResult = commandLine.parseArgs(args);
        if (CommandLine.printHelpIfRequested(parseResult)) {
            return;
        }

        boolean interactiveRoot = parseResult.subcommand() == null;
        if (interactiveRoot) {
            SessionBootstrap.prepareInteractiveSession(
                    stateStore,
                    sessionStore,
                    currentDirectory,
                    rootCommand.resumeSessionId(),
                    System.in,
                    System.out
            );
        }

        int exitCode = interactiveRoot
                ? new InteractiveShell(commandLine, promptRouter).run()
                : commandLine.execute(args);
        System.exit(exitCode);
    }
}
