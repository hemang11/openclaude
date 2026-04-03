package io.openclaude.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;

public final class ProviderConnectionStore {
    private final OpenClaudePaths paths;
    private final ObjectMapper objectMapper;

    public ProviderConnectionStore(OpenClaudePaths paths, ObjectMapper objectMapper) {
        this.paths = paths;
        this.objectMapper = objectMapper;
    }

    public static ProviderConnectionStore createDefault(OpenClaudePaths paths) {
        return new ProviderConnectionStore(paths, new ObjectMapper().findAndRegisterModules());
    }

    public ProviderConnectionState load() {
        var file = paths.providerConnectionsFile();
        if (!Files.exists(file)) {
            return ProviderConnectionState.empty();
        }
        try {
            return objectMapper.readValue(file.toFile(), ProviderConnectionState.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read provider connections from " + file, exception);
        }
    }

    public void save(ProviderConnectionState state) {
        var file = paths.providerConnectionsFile();
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), state);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write provider connections to " + file, exception);
        }
    }
}

