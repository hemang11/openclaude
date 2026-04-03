package com.openclaude.cli.commands;

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
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

public final class ProviderCommands {
    private ProviderCommands() {
    }

    public static CommandLine create(
            ProviderRegistry providerRegistry,
            OpenClaudeStateStore stateStore,
            OpenAiBrowserAuthService openAiBrowserAuthService
    ) {
        CommandLine providerRoot = new CommandLine(new ProviderRoot());
        providerRoot.addSubcommand("list", new ProviderList(providerRegistry));
        providerRoot.addSubcommand("status", new ProviderStatus(providerRegistry, stateStore));
        providerRoot.addSubcommand("connect", new ProviderConnect(providerRegistry, stateStore, openAiBrowserAuthService));
        providerRoot.addSubcommand("use", new ProviderUse(providerRegistry, stateStore));
        providerRoot.addSubcommand("disconnect", new ProviderDisconnect(stateStore));
        return providerRoot;
    }

    @Command(
            name = "provider",
            mixinStandardHelpOptions = true,
            description = "Manage model providers"
    )
    static final class ProviderRoot implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    @Command(
            name = "list",
            mixinStandardHelpOptions = true,
            description = "List supported providers"
    )
    static final class ProviderList implements Runnable {
        private final ProviderRegistry providerRegistry;

        ProviderList(ProviderRegistry providerRegistry) {
            this.providerRegistry = providerRegistry;
        }

        @Override
        public void run() {
            providerRegistry.listExecutable().stream()
                    .sorted(Comparator.comparing(ProviderPlugin::displayName))
                    .forEach(provider -> System.out.printf(
                            "%s (%s) auth=%s%n",
                            provider.displayName(),
                            provider.id().cliValue(),
                            provider.supportedAuthMethods().stream()
                                    .map(AuthMethod::cliValue)
                                    .sorted()
                                    .collect(Collectors.joining(", "))
                    ));
        }
    }

    @Command(
            name = "status",
            mixinStandardHelpOptions = true,
            description = "Show connected providers"
    )
    static final class ProviderStatus implements Runnable {
        private final ProviderRegistry providerRegistry;
        private final OpenClaudeStateStore stateStore;

        ProviderStatus(ProviderRegistry providerRegistry, OpenClaudeStateStore stateStore) {
            this.providerRegistry = providerRegistry;
            this.stateStore = stateStore;
        }

        @Override
        public void run() {
            OpenClaudeState state = stateStore.load();
            var visibleConnections = state.connections().values().stream()
                    .filter(connection -> providerRegistry.isExecutable(connection.providerId()))
                    .sorted(Comparator.comparing(connection -> connection.providerId().cliValue()))
                    .toList();

            if (visibleConnections.isEmpty()) {
                System.out.println("No v0-ready providers connected.");
                return;
            }

            if (state.activeProvider() != null && providerRegistry.isExecutable(state.activeProvider())) {
                System.out.printf("activeProvider=%s activeModel=%s%n",
                        state.activeProvider().cliValue(),
                        state.activeModelId() == null ? "<default>" : state.activeModelId());
            }

            visibleConnections.forEach(connection -> {
                        Optional<ProviderPlugin> provider = providerRegistry.find(connection.providerId());
                        String providerName = provider.map(ProviderPlugin::displayName)
                                .orElse(connection.providerId().displayName());
                        System.out.printf(
                                "%s auth=%s source=%s connectedAt=%s%n",
                                providerName,
                                connection.authMethod().cliValue(),
                                connection.credentialReference(),
                                connection.connectedAt()
                        );
                    });
        }
    }

    @Command(
            name = "connect",
            mixinStandardHelpOptions = true,
            description = "Connect a provider"
    )
    static final class ProviderConnect implements Runnable {
        private final ProviderRegistry providerRegistry;
        private final OpenClaudeStateStore stateStore;
        private final OpenAiBrowserAuthService openAiBrowserAuthService;
        private final ApiKeyStore apiKeyStore = new ApiKeyStore();

        @Parameters(index = "0", description = "Provider id")
        private String provider;

        @Option(names = "--api-key-env", description = "Environment variable containing the provider API key")
        private String apiKeyEnv;

        @Option(names = "--api-key", description = "Raw provider API key")
        private String apiKey;

        @Option(names = "--aws-profile", description = "AWS profile name for Bedrock credentials")
        private String awsProfile;

        @Option(names = "--browser", description = "Use browser-based auth")
        private boolean browser;

        ProviderConnect(
                ProviderRegistry providerRegistry,
                OpenClaudeStateStore stateStore,
                OpenAiBrowserAuthService openAiBrowserAuthService
        ) {
            this.providerRegistry = providerRegistry;
            this.stateStore = stateStore;
            this.openAiBrowserAuthService = openAiBrowserAuthService;
        }

        @Override
        public void run() {
            int selectedModes = (apiKeyEnv != null ? 1 : 0) + (apiKey != null ? 1 : 0) + (awsProfile != null ? 1 : 0) + (browser ? 1 : 0);
            if (selectedModes != 1) {
                throw new CommandLine.ParameterException(
                        new CommandLine(this),
                        "Specify exactly one of --api-key-env, --api-key, --aws-profile, or --browser."
                );
            }

            ProviderId providerId = ProviderId.parse(provider);
            ProviderPlugin plugin = requireExecutableProvider(new CommandLine(this), providerRegistry, providerId);

            if (browser) {
                if (!plugin.supports(AuthMethod.BROWSER_SSO)) {
                    throw new CommandLine.ParameterException(
                            new CommandLine(this),
                            "Provider does not support browser auth: " + providerId.cliValue()
                    );
                }

                if (providerId != ProviderId.OPENAI) {
                    throw new CommandLine.ExecutionException(
                            new CommandLine(this),
                            "Browser auth is currently implemented only for the OpenAI family."
                    );
                }

                var session = openAiBrowserAuthService.authorize(System.out::println);

                stateStore.saveConnection(new ProviderConnectionState(
                        providerId,
                        AuthMethod.BROWSER_SSO,
                        OpenAiOAuthStore.DEFAULT_CREDENTIAL_REFERENCE,
                        Instant.now()
                ));

                String accountSuffix = session.accountId() == null ? "" : " account=" + session.accountId();
                System.out.printf(
                        "Connected %s using OpenAI browser auth%s%n",
                        plugin.displayName(),
                        accountSuffix
                );
                return;
            }

            if (apiKeyEnv != null || apiKey != null) {
                if (!plugin.supports(AuthMethod.API_KEY)) {
                    throw new CommandLine.ParameterException(
                            new CommandLine(this),
                            "Provider does not support API key auth: " + providerId.cliValue()
                    );
                }

                String credentialReference = apiKeyStore.toCredentialReference(
                        providerId,
                        apiKey != null ? apiKey : apiKeyEnv
                );

                stateStore.saveConnection(new ProviderConnectionState(
                        providerId,
                        AuthMethod.API_KEY,
                        credentialReference,
                        Instant.now()
                ));

                System.out.printf(
                        "Connected %s using %s%n",
                        plugin.displayName(),
                        apiKeyStore.describeCredentialReference(credentialReference)
                );
                return;
            }

            if (!plugin.supports(AuthMethod.AWS_CREDENTIALS)) {
                throw new CommandLine.ParameterException(
                        new CommandLine(this),
                        "Provider does not support AWS credentials: " + providerId.cliValue()
                );
            }

            stateStore.saveConnection(new ProviderConnectionState(
                    providerId,
                    AuthMethod.AWS_CREDENTIALS,
                    "aws-profile:" + awsProfile,
                    Instant.now()
            ));

            System.out.printf(
                    "Connected %s using AWS profile %s%n",
                    plugin.displayName(),
                    awsProfile
            );
        }
    }

    @Command(
            name = "use",
            mixinStandardHelpOptions = true,
            description = "Set the active provider"
    )
    static final class ProviderUse implements Runnable {
        private final ProviderRegistry providerRegistry;
        private final OpenClaudeStateStore stateStore;

        @Parameters(index = "0", description = "Provider id")
        private String provider;

        ProviderUse(ProviderRegistry providerRegistry, OpenClaudeStateStore stateStore) {
            this.providerRegistry = providerRegistry;
            this.stateStore = stateStore;
        }

        @Override
        public void run() {
            ProviderId providerId = ProviderId.parse(provider);
            requireExecutableProvider(new CommandLine(this), providerRegistry, providerId);
            OpenClaudeState state = stateStore.load();
            if (!state.connections().containsKey(providerId)) {
                throw new CommandLine.ParameterException(
                        new CommandLine(this),
                        "Provider is not connected: " + providerId.cliValue()
                );
            }

            stateStore.setActiveProvider(providerId);
            System.out.printf("Active provider set to %s%n", providerId.cliValue());
        }
    }

    @Command(
            name = "disconnect",
            mixinStandardHelpOptions = true,
            description = "Disconnect a provider"
    )
    static final class ProviderDisconnect implements Runnable {
        private final OpenClaudeStateStore stateStore;

        @Parameters(index = "0", description = "Provider id")
        private String provider;

        ProviderDisconnect(OpenClaudeStateStore stateStore) {
            this.stateStore = stateStore;
        }

        @Override
        public void run() {
            ProviderId providerId = ProviderId.parse(provider);
            OpenClaudeState state = stateStore.load();
            if (!state.connections().containsKey(providerId)) {
                throw new CommandLine.ParameterException(
                        new CommandLine(this),
                        "Provider is not connected: " + providerId.cliValue()
                );
            }

            stateStore.removeConnection(providerId);
            System.out.printf("Disconnected %s%n", providerId.cliValue());
        }
    }

    private static ProviderPlugin requireExecutableProvider(
            CommandLine commandLine,
            ProviderRegistry providerRegistry,
            ProviderId providerId
    ) {
        return providerRegistry.findExecutable(providerId)
                .orElseGet(() -> {
                    if (providerRegistry.find(providerId).isPresent()) {
                        throw new CommandLine.ParameterException(
                                commandLine,
                                "Prompt execution is not implemented for provider " + providerId.cliValue()
                        );
                    }
                    throw new CommandLine.ParameterException(
                            commandLine,
                            "Unsupported provider: " + providerId.cliValue()
                    );
                });
    }
}
