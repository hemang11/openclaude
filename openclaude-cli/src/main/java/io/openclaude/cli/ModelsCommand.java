package io.openclaude.cli;

import io.openclaude.config.ProviderConnectionStore;
import io.openclaude.provider.spi.ModelCatalog;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(
        name = "models",
        mixinStandardHelpOptions = true,
        description = "List models for the active provider.")
public final class ModelsCommand implements Callable<Integer> {
    private final ModelCatalog modelCatalog;
    private final ProviderConnectionStore connectionStore;

    public ModelsCommand(ModelCatalog modelCatalog, ProviderConnectionStore connectionStore) {
        this.modelCatalog = modelCatalog;
        this.connectionStore = connectionStore;
    }

    @Override
    public Integer call() {
        var state = connectionStore.load();
        if (state.activeProvider() == null) {
            System.out.println("No active provider is configured yet.");
            System.out.println("Use the provider workflow first. REPL /provider and /models will be built on the same backing services.");
            return 0;
        }

        var models = modelCatalog.modelsFor(state.activeProvider());
        System.out.printf("Models for %s:%n", state.activeProvider().name().toLowerCase());
        if (models.isEmpty()) {
            System.out.println("No model catalog is registered yet for this provider.");
            System.out.println("Exact provider model matrices will be loaded after provider-specific integration work.");
            return 0;
        }

        models.forEach(model -> System.out.printf(
                "- %s (%s)%n",
                model.displayName(),
                model.id()));
        return 0;
    }
}
