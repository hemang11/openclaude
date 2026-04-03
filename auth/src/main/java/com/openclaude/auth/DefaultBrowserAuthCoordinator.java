package com.openclaude.auth;

import com.openclaude.provider.spi.ProviderId;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

public final class DefaultBrowserAuthCoordinator implements BrowserAuthCoordinator {
    @Override
    public boolean supports(ProviderId providerId) {
        return true;
    }

    @Override
    public BrowserAuthSession begin(BrowserAuthRequest request) {
        PkceChallenge pkceChallenge = PkceUtil.generate();
        String state = PkceUtil.randomState();
        LocalCallbackServer callbackServer = new LocalCallbackServer(
                request.callbackHost(),
                request.callbackPort(),
                request.callbackPath()
        );
        URI redirectUri = callbackServer.callbackUri();
        URI authorizationUri = buildAuthorizationUri(request, redirectUri, pkceChallenge, state);
        return new BrowserAuthSession(authorizationUri, redirectUri, state, pkceChallenge, callbackServer);
    }

    private URI buildAuthorizationUri(
            BrowserAuthRequest request,
            URI redirectUri,
            PkceChallenge pkceChallenge,
            String state
    ) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("response_type", "code");
        parameters.put("client_id", request.clientId());
        parameters.put("redirect_uri", redirectUri.toString());
        parameters.put("scope", String.join(" ", request.scopes()));
        parameters.put("state", state);
        parameters.put("code_challenge", pkceChallenge.challenge());
        parameters.put("code_challenge_method", "S256");
        parameters.putAll(request.additionalParameters());

        StringJoiner query = new StringJoiner("&");
        parameters.forEach((key, value) -> query.add(
                URLEncoder.encode(key, StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(value, StandardCharsets.UTF_8)));

        return URI.create(request.authorizationEndpoint() + "?" + query);
    }
}
