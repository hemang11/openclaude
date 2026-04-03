package com.openclaude.auth;

import com.openclaude.provider.spi.ProviderId;
import java.net.URI;
import java.util.List;
import java.util.Map;

public record BrowserAuthRequest(
        ProviderId providerId,
        URI authorizationEndpoint,
        String clientId,
        List<String> scopes,
        Map<String, String> additionalParameters,
        String callbackHost,
        int callbackPort,
        String callbackPath
) {
    public BrowserAuthRequest(
            ProviderId providerId,
            URI authorizationEndpoint,
            String clientId,
            List<String> scopes,
            Map<String, String> additionalParameters
    ) {
        this(providerId, authorizationEndpoint, clientId, scopes, additionalParameters, "127.0.0.1", 0, "/callback");
    }

    public BrowserAuthRequest {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        additionalParameters = additionalParameters == null ? Map.of() : Map.copyOf(additionalParameters);
        callbackHost = callbackHost == null || callbackHost.isBlank() ? "127.0.0.1" : callbackHost;
        callbackPath = callbackPath == null || callbackPath.isBlank() ? "/callback" : callbackPath;
        if (!callbackPath.startsWith("/")) {
            callbackPath = "/" + callbackPath;
        }
    }
}
