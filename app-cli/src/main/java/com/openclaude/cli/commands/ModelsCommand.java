package com.openclaude.cli.commands;

import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.provider.OpenClaudeState;
import com.openclaude.core.provider.ProviderRegistry;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderPlugin;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;

@Command(
        name = "models",
        mixinStandardHelpOptions = true,
        description = "List and select models for the active provider",
        subcommands = {
                ModelsCommand.Current.class,
                ModelsCommand.Use.class
        }
)
public final class ModelsCommand implements Runnable {
    private final ProviderRegistry providerRegistry;
    private final OpenClaudeStateStore stateStore;

    public ModelsCommand(ProviderRegistry providerRegistry, OpenClaudeStateStore stateStore) {
        this.providerRegistry = providerRegistry;
        this.stateStore = stateStore;
    }

    @Override
    public void run() {
        OpenClaudeState state = stateStore.load();
        boolean hasExecutableConnections = state.connections().keySet().stream()
                .anyMatch(providerRegistry::isExecutable);
        if (!hasExecutableConnections) {
            System.out.println("No v0-ready connected providers. Use `openclaude provider connect ...` first.");
            return;
        }

        if (state.activeProvider() == null || !providerRegistry.isExecutable(state.activeProvider())) {
            System.out.println("No active provider. Use `openclaude provider use <provider>` first.");
            return;
        }

        ProviderPlugin providerPlugin = providerRegistry.findExecutable(state.activeProvider())
                .orElseThrow(() -> new IllegalStateException("Active provider is not registered: " + state.activeProvider()));
        printProviderModels(providerPlugin);
    }

    private void printProviderModels(ProviderPlugin providerPlugin) {
        List<ModelDescriptor> models = providerPlugin.supportedModels();
        System.out.printf("[%s]%n", providerPlugin.displayName());
        if (models.isEmpty()) {
            System.out.println("  No models registered yet.");
            return;
        }

        models.forEach(model -> System.out.printf("  %s  %s%n", model.id(), model.displayName()));
    }

    @Command(
            name = "current",
            mixinStandardHelpOptions = true,
            description = "Show the current active provider and model"
    )
    static final class Current implements Runnable {
        @picocli.CommandLine.ParentCommand
        private ModelsCommand parent;

        @Override
        public void run() {
            OpenClaudeState state = parent.stateStore.load();
            if (state.activeProvider() == null || !parent.providerRegistry.isExecutable(state.activeProvider())) {
                System.out.println("No active provider.");
                return;
            }

            System.out.printf(
                    "provider=%s model=%s%n",
                    state.activeProvider().cliValue(),
                    state.activeModelId() == null ? "<default>" : state.activeModelId()
            );
        }
    }

    @Command(
            name = "use",
            mixinStandardHelpOptions = true,
            description = "Set the active model for the active provider"
    )
    static final class Use implements Runnable {
        @picocli.CommandLine.ParentCommand
        private ModelsCommand parent;

        @Parameters(index = "0", description = "Model id")
        private String modelId;

        @Override
        public void run() {
            OpenClaudeState state = parent.stateStore.load();
            if (state.activeProvider() == null || !parent.providerRegistry.isExecutable(state.activeProvider())) {
                throw new ParameterException(new picocli.CommandLine(this), "No active provider. Use `openclaude provider use <provider>` first.");
            }

            ProviderId providerId = state.activeProvider();
            ProviderPlugin providerPlugin = parent.providerRegistry.findExecutable(providerId)
                    .orElseThrow(() -> new IllegalStateException("Prompt execution is not implemented for provider: " + providerId));

            Optional<ModelDescriptor> model = providerPlugin.supportedModels().stream()
                    .filter(candidate -> candidate.id().equals(modelId))
                    .findFirst();

            if (model.isEmpty()) {
                throw new ParameterException(
                        new picocli.CommandLine(this),
                        "Unknown model for provider " + providerId.cliValue() + ": " + modelId
                );
            }

            parent.stateStore.setActiveModel(modelId);
            System.out.printf("Active model set to %s%n", modelId);
        }
    }
}
