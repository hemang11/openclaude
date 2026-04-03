package com.openclaude.cli.service;

import com.openclaude.core.config.ApiKeyStore;
import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.provider.OpenClaudeState;
import com.openclaude.core.provider.ProviderConnectionState;
import com.openclaude.core.provider.ProviderRegistry;
import com.openclaude.provider.openai.OpenAiBrowserAuthService;
import com.openclaude.provider.openai.OpenAiOAuthStore;
import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderPlugin;
import java.util.EnumMap;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class ProviderService {
    private final ProviderRegistry providerRegistry;
    private final OpenClaudeStateStore stateStore;
    private final OpenAiBrowserAuthService openAiBrowserAuthService;
    private final ApiKeyStore apiKeyStore;
    private final OpenAiOAuthStore openAiOAuthStore;

    public ProviderService(
            ProviderRegistry providerRegistry,
            OpenClaudeStateStore stateStore,
            OpenAiBrowserAuthService openAiBrowserAuthService
    ) {
        this(providerRegistry, stateStore, openAiBrowserAuthService, new ApiKeyStore(), new OpenAiOAuthStore());
    }

    ProviderService(
            ProviderRegistry providerRegistry,
            OpenClaudeStateStore stateStore,
            OpenAiBrowserAuthService openAiBrowserAuthService,
            ApiKeyStore apiKeyStore,
            OpenAiOAuthStore openAiOAuthStore
    ) {
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.openAiBrowserAuthService = Objects.requireNonNull(openAiBrowserAuthService, "openAiBrowserAuthService");
        this.apiKeyStore = Objects.requireNonNull(apiKeyStore, "apiKeyStore");
        this.openAiOAuthStore = Objects.requireNonNull(openAiOAuthStore, "openAiOAuthStore");
    }

    public List<ProviderView> listProviders() {
        OpenClaudeState state = state();
        return providerRegistry.listExecutable().stream()
                .sorted(Comparator.comparing(ProviderPlugin::displayName))
                .map(provider -> new ProviderView(
                        provider.id(),
                        provider.displayName(),
                        provider.supportedAuthMethods(),
                        state.get(provider.id()),
                        state.activeProvider() == provider.id()
                ))
                .toList();
    }

    public ProviderView providerView(ProviderId providerId) {
        return listProviders().stream()
                .filter(candidate -> candidate.providerId() == providerId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported provider: " + providerId.cliValue()));
    }

    public OpenClaudeState state() {
        OpenClaudeState state = stateStore.load();
        ProviderId nextActiveProvider = state.activeProvider();
        String nextActiveModelId = state.activeModelId();

        if (nextActiveProvider != null) {
            boolean connected = state.connections().containsKey(nextActiveProvider);
            boolean executable = providerRegistry.isExecutable(nextActiveProvider);
            if (!connected || !executable) {
                nextActiveProvider = state.connections().keySet().stream()
                        .filter(providerRegistry::isExecutable)
                        .sorted()
                        .findFirst()
                        .orElse(null);
                nextActiveModelId = null;
            }
        }

        if (nextActiveProvider != null && nextActiveModelId != null && !nextActiveModelId.isBlank()) {
            ProviderPlugin providerPlugin = providerRegistry.findExecutable(nextActiveProvider).orElse(null);
            String requestedModelId = nextActiveModelId;
            boolean knownModel = providerPlugin != null
                    && providerPlugin.supportedModels().stream().anyMatch(model -> model.id().equals(requestedModelId));
            if (!knownModel) {
                nextActiveModelId = null;
            }
        }

        OpenClaudeState sanitized = new OpenClaudeState(
                nextActiveProvider,
                nextActiveModelId,
                state.activeSessionId(),
                state.connections(),
                state.settings()
        );
        if (!sanitized.equals(state)) {
            stateStore.save(sanitized);
        }
        return sanitized;
    }

    public OpenClaudeState visibleState() {
        OpenClaudeState state = state();
        EnumMap<ProviderId, ProviderConnectionState> visibleConnections = new EnumMap<>(ProviderId.class);
        state.connections().forEach((providerId, connectionState) -> {
            if (providerRegistry.isExecutable(providerId)) {
                visibleConnections.put(providerId, connectionState);
            }
        });
        return new OpenClaudeState(
                state.activeProvider(),
                state.activeModelId(),
                state.activeSessionId(),
                visibleConnections,
                state.settings()
        );
    }

    public String connectApiKeyEnv(ProviderId providerId, String apiKeyEnv) {
        ProviderPlugin plugin = requireExecutablePlugin(providerId);
        String credentialReference = apiKeyStore.toCredentialReference(
                providerId,
                requireNonBlank(apiKeyEnv, "API key or environment variable name")
        );
        requireAuth(plugin, AuthMethod.API_KEY);

        stateStore.saveConnection(new ProviderConnectionState(
                providerId,
                AuthMethod.API_KEY,
                credentialReference,
                Instant.now()
        ));
        stateStore.setActiveProvider(providerId);
        return "Connected " + plugin.displayName() + " using " + apiKeyStore.describeCredentialReference(credentialReference);
    }

    public String connectAwsProfile(ProviderId providerId, String awsProfile) {
        ProviderPlugin plugin = requireExecutablePlugin(providerId);
        String normalizedProfile = requireNonBlank(awsProfile, "AWS profile name");
        requireAuth(plugin, AuthMethod.AWS_CREDENTIALS);

        stateStore.saveConnection(new ProviderConnectionState(
                providerId,
                AuthMethod.AWS_CREDENTIALS,
                "aws-profile:" + normalizedProfile,
                Instant.now()
        ));
        stateStore.setActiveProvider(providerId);
        return "Connected " + plugin.displayName() + " using AWS profile " + normalizedProfile;
    }

    public String connectBrowser(ProviderId providerId, Consumer<String> statusConsumer) {
        ProviderPlugin plugin = requireExecutablePlugin(providerId);
        requireAuth(plugin, AuthMethod.BROWSER_SSO);
        if (providerId != ProviderId.OPENAI) {
            throw new IllegalStateException("Browser auth is currently implemented only for the OpenAI family.");
        }

        var session = openAiBrowserAuthService.authorize(statusConsumer);

        stateStore.saveConnection(new ProviderConnectionState(
                providerId,
                AuthMethod.BROWSER_SSO,
                OpenAiOAuthStore.DEFAULT_CREDENTIAL_REFERENCE,
                Instant.now()
        ));
        stateStore.setActiveProvider(providerId);

        String accountSuffix = session.accountId() == null ? "" : " account=" + session.accountId();
        return "Connected " + plugin.displayName() + " using OpenAI browser auth" + accountSuffix;
    }

    public String useProvider(ProviderId providerId) {
        requireExecutablePlugin(providerId);
        ProviderConnectionState connectionState = state().get(providerId);
        if (connectionState == null) {
            throw new IllegalStateException("Provider is not connected: " + providerId.cliValue());
        }
        stateStore.setActiveProvider(providerId);
        return "Active provider set to " + providerId.cliValue();
    }

    public String disconnect(ProviderId providerId) {
        ProviderConnectionState connectionState = state().get(providerId);
        if (connectionState == null) {
            throw new IllegalStateException("Provider is not connected: " + providerId.cliValue());
        }
        clearStoredCredentials(connectionState);
        stateStore.removeConnection(providerId);
        return "Disconnected " + providerId.cliValue();
    }

    private void clearStoredCredentials(ProviderConnectionState connectionState) {
        if (connectionState.authMethod() == AuthMethod.API_KEY) {
            apiKeyStore.deleteCredentialReference(connectionState.credentialReference());
            return;
        }
        if (connectionState.authMethod() == AuthMethod.BROWSER_SSO
                && connectionState.providerId() == ProviderId.OPENAI) {
            openAiOAuthStore.delete(connectionState.credentialReference());
        }
    }

    private ProviderPlugin requireExecutablePlugin(ProviderId providerId) {
        return providerRegistry.findExecutable(providerId)
                .orElseGet(() -> {
                    requirePlugin(providerId);
                    throw new IllegalStateException(
                            "Prompt execution is not implemented for provider " + providerId.cliValue()
                    );
                });
    }

    private ProviderPlugin requirePlugin(ProviderId providerId) {
        return providerRegistry.find(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported provider: " + providerId.cliValue()));
    }

    private static void requireAuth(ProviderPlugin plugin, AuthMethod authMethod) {
        if (!plugin.supports(authMethod)) {
            throw new IllegalStateException(
                    "Provider does not support " + authMethod.cliValue() + ": " + plugin.id().cliValue()
            );
        }
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return value.trim();
    }

    public record ProviderView(
            ProviderId providerId,
            String displayName,
            Set<AuthMethod> supportedAuthMethods,
            ProviderConnectionState connectionState,
            boolean active
    ) {
        public boolean connected() {
            return connectionState != null;
        }
    }
}
