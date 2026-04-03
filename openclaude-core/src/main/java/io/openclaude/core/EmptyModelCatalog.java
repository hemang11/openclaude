package io.openclaude.core;

import io.openclaude.provider.spi.ModelCatalog;
import io.openclaude.provider.spi.ModelDescriptor;
import io.openclaude.provider.spi.ProviderId;
import java.util.List;

public final class EmptyModelCatalog implements ModelCatalog {
    @Override
    public List<ModelDescriptor> modelsFor(ProviderId providerId) {
        return List.of();
    }
}

