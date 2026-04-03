package io.openclaude.provider.spi;

import java.util.List;

public interface ModelCatalog {
    List<ModelDescriptor> modelsFor(ProviderId providerId);
}

