package com.openclaude.cli.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openclaude.core.config.ApiKeyStore;
import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.provider.OpenClaudeState;
import com.openclaude.core.provider.ProviderConnectionState;
import com.openclaude.core.provider.ProviderRegistry;
import com.openclaude.provider.openai.OpenAiBrowserAuthService;
import com.openclaude.provider.openai.OpenAiOAuthSession;
import com.openclaude.provider.openai.OpenAiOAuthStore;
import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ModelDescriptor;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void listProvidersHidesProvidersWithoutPromptExecution() {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new UnreadyGeminiProvider(), new ReadyOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state.json"));
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());

        List<ProviderService.ProviderView> providers = providerService.listProviders();

        assertEquals(1, providers.size());
        assertEquals(ProviderId.OPENAI, providers.getFirst().providerId());
    }

    @Test
    void stateSanitizesLegacyActiveProviderToExecutableConnectedProvider() {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new UnreadyGeminiProvider(), new ReadyOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state-sanitize.json"));
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());

        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.GEMINI,
                AuthMethod.API_KEY,
                "env:GEMINI_API_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.API_KEY,
                "env:OPENAI_API_KEY",
                Instant.parse("2026-04-02T00:00:00Z")
        ));
        stateStore.save(new OpenClaudeState(
                ProviderId.GEMINI,
                "gemini/gemini-2.5-pro",
                null,
                stateStore.load().connections(),
                stateStore.load().settings()
        ));

        OpenClaudeState sanitized = providerService.state();
        OpenClaudeState visible = providerService.visibleState();

        assertEquals(ProviderId.OPENAI, sanitized.activeProvider());
        assertNull(sanitized.activeModelId());
        assertEquals(Set.of(ProviderId.OPENAI), visible.connections().keySet());
        assertEquals(ProviderId.OPENAI, stateStore.load().activeProvider());
    }

    @Test
    void connectApiKeyEnvRejectsProviderWithoutPromptExecution() {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new UnreadyGeminiProvider(), new ReadyOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state-connect.json"));
        ProviderService providerService = new ProviderService(providerRegistry, stateStore, new OpenAiBrowserAuthService());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> providerService.connectApiKeyEnv(ProviderId.GEMINI, "GEMINI_API_KEY")
        );

        assertEquals("Prompt execution is not implemented for provider gemini", error.getMessage());
    }

    @Test
    void disconnectRemovesStoredApiKeyFile() {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ReadyOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state-disconnect-api-key.json"));
        ApiKeyStore apiKeyStore = new ApiKeyStore(tempDir.resolve("auth"));
        ProviderService providerService = new ProviderService(
                providerRegistry,
                stateStore,
                new OpenAiBrowserAuthService(),
                apiKeyStore,
                new OpenAiOAuthStore(tempDir.resolve("oauth"))
        );

        providerService.connectApiKeyEnv(ProviderId.OPENAI, "sk-openclaude-test");
        String credentialReference = stateStore.load().get(ProviderId.OPENAI).credentialReference();
        Path credentialFile = Path.of(credentialReference.substring("file:".length()));

        assertTrue(Files.exists(credentialFile));

        String message = providerService.disconnect(ProviderId.OPENAI);

        assertEquals("Disconnected openai", message);
        assertTrue(Files.notExists(credentialFile));
    }

    @Test
    void disconnectRemovesStoredOpenAiBrowserSession() {
        ProviderRegistry providerRegistry = new ProviderRegistry(List.of(new ReadyBrowserOpenAiProvider()));
        OpenClaudeStateStore stateStore = new OpenClaudeStateStore(tempDir.resolve("state-disconnect-browser.json"));
        OpenAiOAuthStore oauthStore = new OpenAiOAuthStore(tempDir.resolve("oauth"));
        ProviderService providerService = new ProviderService(
                providerRegistry,
                stateStore,
                new OpenAiBrowserAuthService(),
                new ApiKeyStore(tempDir.resolve("auth")),
                oauthStore
        );

        oauthStore.save(
                OpenAiOAuthStore.DEFAULT_CREDENTIAL_REFERENCE,
                new OpenAiOAuthSession(
                        "access-token",
                        "refresh-token",
                        "id-token",
                        Instant.parse("2026-04-03T00:00:00Z"),
                        "account-id",
                        "user@example.com",
                        Instant.parse("2026-04-02T00:00:00Z")
                )
        );
        Path oauthFile = oauthStore.credentialFile(OpenAiOAuthStore.DEFAULT_CREDENTIAL_REFERENCE);
        stateStore.saveConnection(new ProviderConnectionState(
                ProviderId.OPENAI,
                AuthMethod.BROWSER_SSO,
                OpenAiOAuthStore.DEFAULT_CREDENTIAL_REFERENCE,
                Instant.parse("2026-04-02T00:00:00Z")
        ));

        assertTrue(Files.exists(oauthFile));

        String message = providerService.disconnect(ProviderId.OPENAI);

        assertEquals("Disconnected openai", message);
        assertTrue(Files.notExists(oauthFile));
    }

    private static class ReadyOpenAiProvider implements ProviderPlugin {
        @Override
        public ProviderId id() {
            return ProviderId.OPENAI;
        }

        @Override
        public String displayName() {
            return "OpenAI";
        }

        @Override
        public Set<AuthMethod> supportedAuthMethods() {
            return Set.of(AuthMethod.API_KEY);
        }

        @Override
        public List<ModelDescriptor> supportedModels() {
            return List.of(new ModelDescriptor("gpt-5.3-codex", "GPT-5.3 Codex", id()));
        }

        @Override
        public boolean supportsPromptExecution() {
            return true;
        }
    }

    private static final class ReadyBrowserOpenAiProvider extends ReadyOpenAiProvider {
        @Override
        public Set<AuthMethod> supportedAuthMethods() {
            return Set.of(AuthMethod.API_KEY, AuthMethod.BROWSER_SSO);
        }
    }

    private static final class UnreadyGeminiProvider implements ProviderPlugin {
        @Override
        public ProviderId id() {
            return ProviderId.GEMINI;
        }

        @Override
        public String displayName() {
            return "Gemini";
        }

        @Override
        public Set<AuthMethod> supportedAuthMethods() {
            return Set.of(AuthMethod.API_KEY);
        }

        @Override
        public List<ModelDescriptor> supportedModels() {
            return List.of(new ModelDescriptor("gemini/gemini-2.5-pro", "Gemini 2.5 Pro", id()));
        }
    }
}
