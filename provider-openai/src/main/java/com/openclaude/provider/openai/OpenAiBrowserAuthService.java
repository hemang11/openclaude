package com.openclaude.provider.openai;

import com.openclaude.auth.AuthorizationCallback;
import com.openclaude.auth.BrowserAuthCoordinator;
import com.openclaude.auth.BrowserAuthRequest;
import com.openclaude.auth.BrowserAuthSession;
import com.openclaude.auth.BrowserLauncher;
import com.openclaude.auth.DefaultBrowserAuthCoordinator;
import com.openclaude.auth.DesktopBrowserLauncher;
import com.openclaude.provider.spi.ProviderId;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class OpenAiBrowserAuthService {
    static final URI AUTHORIZATION_ENDPOINT = URI.create("https://auth.openai.com/oauth/authorize");
    static final List<String> SCOPES = List.of("openid", "profile", "email", "offline_access");
    static final Map<String, String> AUTH_PARAMETERS = Map.of(
            "id_token_add_organizations", "true",
            "codex_cli_simplified_flow", "true",
            "originator", "openclaude"
    );
    static final int CALLBACK_PORT = 1455;
    static final String CALLBACK_HOST = "localhost";
    static final String CALLBACK_PATH = "/auth/callback";

    private final BrowserAuthCoordinator browserAuthCoordinator;
    private final BrowserLauncher browserLauncher;
    private final OpenAiOAuthApi oauthApi;
    private final OpenAiOAuthStore oauthStore;
    private final Clock clock;
    private final Duration callbackTimeout;

    public OpenAiBrowserAuthService() {
        this(
                new DefaultBrowserAuthCoordinator(),
                new DesktopBrowserLauncher(),
                new HttpOpenAiOAuthApi(),
                new OpenAiOAuthStore(),
                Clock.systemUTC(),
                Duration.ofMinutes(5)
        );
    }

    OpenAiBrowserAuthService(
            BrowserAuthCoordinator browserAuthCoordinator,
            BrowserLauncher browserLauncher,
            OpenAiOAuthApi oauthApi,
            OpenAiOAuthStore oauthStore,
            Clock clock,
            Duration callbackTimeout
    ) {
        this.browserAuthCoordinator = Objects.requireNonNull(browserAuthCoordinator, "browserAuthCoordinator");
        this.browserLauncher = Objects.requireNonNull(browserLauncher, "browserLauncher");
        this.oauthApi = Objects.requireNonNull(oauthApi, "oauthApi");
        this.oauthStore = Objects.requireNonNull(oauthStore, "oauthStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.callbackTimeout = Objects.requireNonNull(callbackTimeout, "callbackTimeout");
    }

    public OpenAiOAuthSession authorize(Consumer<String> statusConsumer) {
        BrowserAuthRequest request = new BrowserAuthRequest(
                ProviderId.OPENAI,
                AUTHORIZATION_ENDPOINT,
                HttpOpenAiOAuthApi.CLIENT_ID,
                SCOPES,
                AUTH_PARAMETERS,
                CALLBACK_HOST,
                CALLBACK_PORT,
                CALLBACK_PATH
        );

        try (BrowserAuthSession authSession = browserAuthCoordinator.begin(request)) {
            emit(statusConsumer, "Opening browser for OpenAI authentication...");
            boolean opened = browserLauncher.open(authSession.authorizationUri());
            if (!opened) {
                emit(statusConsumer, "Open this URL in your browser:");
                emit(statusConsumer, authSession.authorizationUri().toString());
            }
            emit(statusConsumer, "Waiting for OpenAI authentication callback...");

            AuthorizationCallback callback = authSession.callbackFuture().get(
                    callbackTimeout.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            validateCallback(callback, authSession);

            OpenAiTokenResponse tokens = oauthApi.exchangeAuthorizationCode(
                    callback.code(),
                    authSession.redirectUri(),
                    authSession.pkceChallenge()
            );

            OpenAiOAuthSession session = OpenAiOAuthSession.fromTokenResponse(tokens, clock.instant());
            oauthStore.save(OpenAiOAuthStore.DEFAULT_CREDENTIAL_REFERENCE, session);
            emit(statusConsumer, "OpenAI browser authentication completed.");
            return session;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to complete OpenAI browser authentication", exception);
        }
    }

    OpenAiOAuthSession resolveSession(String credentialReference) {
        OpenAiOAuthSession current = oauthStore.load(credentialReference)
                .orElseThrow(() -> new IllegalStateException(
                        "No OpenAI browser session is stored for " + normalizeReference(credentialReference)
                ));

        Instant now = clock.instant();
        if (!current.expiresSoon(now)) {
            return current;
        }

        if (current.refreshToken() == null || current.refreshToken().isBlank()) {
            throw new IllegalStateException("OpenAI browser session cannot be refreshed because no refresh token is stored.");
        }

        OpenAiTokenResponse refreshed = oauthApi.refreshAccessToken(current.refreshToken());
        OpenAiOAuthSession next = current.refresh(refreshed, now);
        oauthStore.save(normalizeReference(credentialReference), next);
        return next;
    }

    private static void validateCallback(AuthorizationCallback callback, BrowserAuthSession authSession) {
        String error = parameter(callback, "error");
        if (error != null) {
            String description = parameter(callback, "error_description");
            throw new IllegalStateException(description == null ? error : description);
        }

        if (callback.code() == null || callback.code().isBlank()) {
            throw new IllegalStateException("Missing authorization code in OpenAI browser callback.");
        }

        if (!authSession.state().equals(callback.state())) {
            throw new IllegalStateException("Invalid browser auth state returned by OpenAI.");
        }
    }

    private static String parameter(AuthorizationCallback callback, String name) {
        String value = callback.parameters().get(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeReference(String credentialReference) {
        return credentialReference == null || credentialReference.isBlank()
                ? OpenAiOAuthStore.DEFAULT_CREDENTIAL_REFERENCE
                : credentialReference;
    }

    private static void emit(Consumer<String> statusConsumer, String message) {
        if (statusConsumer != null && message != null && !message.isBlank()) {
            statusConsumer.accept(message);
        }
    }
}
