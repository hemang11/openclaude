package com.openclaude.cli.stdio;

import com.openclaude.cli.SessionBootstrap;
import com.openclaude.cli.service.CommandService;
import com.openclaude.cli.service.ModelService;
import com.openclaude.cli.service.ProviderService;
import com.openclaude.cli.service.SessionService;
import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.provider.PromptRouter;
import com.openclaude.core.session.ConversationSessionStore;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "stdio",
        mixinStandardHelpOptions = true,
        description = "Run the OpenClaude backend over stdio for the React/Ink frontend"
)
public final class StdioServerCommand implements Runnable {
    private final PromptRouter promptRouter;
    private final ProviderService providerService;
    private final ModelService modelService;
    private final CommandService commandService;
    private final SessionService sessionService;
    private final OpenClaudeStateStore stateStore;
    private final ConversationSessionStore sessionStore;

    @Option(names = "--resume", description = "Resume the given session id for the stdio bridge")
    private String resumeSessionId;

    public StdioServerCommand(
            PromptRouter promptRouter,
            ProviderService providerService,
            ModelService modelService,
            CommandService commandService,
            SessionService sessionService,
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore
    ) {
        this.promptRouter = promptRouter;
        this.providerService = providerService;
        this.modelService = modelService;
        this.commandService = commandService;
        this.sessionService = sessionService;
        this.stateStore = stateStore;
        this.sessionStore = sessionStore;
    }

    @Override
    public void run() {
        SessionBootstrap.prepareStdioSession(
                stateStore,
                sessionStore,
                Path.of("").toAbsolutePath().normalize(),
                resumeSessionId
        );
        new OpenClaudeStdioServer(
                promptRouter,
                providerService,
                modelService,
                commandService,
                sessionService,
                stateStore,
                sessionStore
        ).run(System.in, System.out, System.err);
    }
}
