package com.openclaude.provider.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openclaude.auth.AuthorizationCallback;
import com.openclaude.auth.BrowserAuthCoordinator;
import com.openclaude.auth.BrowserAuthRequest;
import com.openclaude.auth.BrowserAuthSession;
import com.openclaude.auth.BrowserLauncher;
import com.openclaude.auth.LocalCallbackServer;
import com.openclaude.auth.PkceChallenge;
import com.openclaude.provider.spi.ProviderId;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiBrowserAuthServiceTest {
    @Test
    void authorizePersistsOAuthSessionAndEmitsStatus() {
        Instant now = Instant.parse("2026-04-02T05:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        OpenAiOAuthStore store = new OpenAiOAuthStore(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("openclaude-auth-test-" + System.nanoTime()));
        List<String> statusMessages = new ArrayList<>();
        RecordingOAuthApi oauthApi = new RecordingOAuthApi(tokenResponse("acct_browser"));

        OpenAiBrowserAuthService service = new OpenAiBrowserAuthService(
                new SuccessfulCoordinator(),
                uri -> false,
                oauthApi,
                store,
                clock,
                Duration.ofSeconds(5)
        );

        OpenAiOAuthSession session = service.authorize(statusMessages::add);

        assertEquals("acct_browser", session.accountId());
        assertEquals("user@example.com", session.email());
        assertEquals("code-123", oauthApi.lastAuthorizationCode);
        assertEquals("verifier-123", oauthApi.lastVerifier);
        assertEquals("http://localhost:1455/auth/callback", oauthApi.lastRedirectUri.toString());
        assertFalse(statusMessages.isEmpty());
        assertTrue(statusMessages.stream().anyMatch(message -> message.contains("Open this URL in your browser")));
        assertEquals(
                "acct_browser",
                store.load(OpenAiOAuthStore.DEFAULT_CREDENTIAL_REFERENCE).orElseThrow().accountId()
        );
    }

    @Test
    void resolveSessionRefreshesExpiringTokens() {
        Instant now = Instant.parse("2026-04-02T06:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        OpenAiOAuthStore store = new OpenAiOAuthStore(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("openclaude-auth-refresh-" + System.nanoTime()));
        store.save(
                OpenAiOAuthStore.DEFAULT_CREDENTIAL_REFERENCE,
                new OpenAiOAuthSession(
                        "access-old",
                        "refresh-old",
                        jwt("acct_old", "old@example.com"),
                        now.minusSeconds(30),
                        "acct_old",
                        "old@example.com",
                        now.minusSeconds(3600)
                )
        );

        RecordingOAuthApi oauthApi = new RecordingOAuthApi(tokenResponse("acct_new"));
        OpenAiBrowserAuthService service = new OpenAiBrowserAuthService(
                new SuccessfulCoordinator(),
                uri -> true,
                oauthApi,
                store,
                clock,
                Duration.ofSeconds(5)
        );

        OpenAiOAuthSession refreshed = service.resolveSession(OpenAiOAuthStore.DEFAULT_CREDENTIAL_REFERENCE);

        assertEquals("access-acct_new", refreshed.accessToken());
        assertEquals("refresh-acct_new", refreshed.refreshToken());
        assertEquals("acct_new", refreshed.accountId());
        assertEquals("refresh-old", oauthApi.lastRefreshToken);
    }

    private static OpenAiTokenResponse tokenResponse(String accountId) {
        return new OpenAiTokenResponse(
                jwt(accountId, "user@example.com"),
                "access-" + accountId,
                "refresh-" + accountId,
                7200
        );
    }

    private static String jwt(String accountId, String email) {
        String header = encode("{\"alg\":\"none\"}");
        String payload = encode("{\"chatgpt_account_id\":\"" + accountId + "\",\"email\":\"" + email + "\"}");
        return header + "." + payload + ".signature";
    }

    private static String encode(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final class SuccessfulCoordinator implements BrowserAuthCoordinator {
        @Override
        public boolean supports(ProviderId providerId) {
            return providerId == ProviderId.OPENAI;
        }

        @Override
        public BrowserAuthSession begin(BrowserAuthRequest request) {
            LocalCallbackServer callbackServer = new LocalCallbackServer();
            BrowserAuthSession session = new BrowserAuthSession(
                    URI.create("https://auth.openai.com/oauth/authorize?client_id=test"),
                    URI.create("http://localhost:1455/auth/callback"),
                    "state-123",
                    new PkceChallenge("verifier-123", "challenge-123"),
                    callbackServer
            );
            session.callbackFuture().complete(new AuthorizationCallback(
                    "code-123",
                    "state-123",
                    Map.of("code", "code-123", "state", "state-123")
            ));
            return session;
        }
    }

    private static final class RecordingOAuthApi implements OpenAiOAuthApi {
        private final OpenAiTokenResponse tokenResponse;
        private URI lastRedirectUri;
        private String lastAuthorizationCode;
        private String lastVerifier;
        private String lastRefreshToken;

        private RecordingOAuthApi(OpenAiTokenResponse tokenResponse) {
            this.tokenResponse = tokenResponse;
        }

        @Override
        public OpenAiTokenResponse exchangeAuthorizationCode(String code, URI redirectUri, PkceChallenge pkceChallenge) {
            this.lastAuthorizationCode = code;
            this.lastRedirectUri = redirectUri;
            this.lastVerifier = pkceChallenge.verifier();
            return tokenResponse;
        }

        @Override
        public OpenAiTokenResponse refreshAccessToken(String refreshToken) {
            this.lastRefreshToken = refreshToken;
            return tokenResponse;
        }
    }
}
