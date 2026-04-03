package com.openclaude.auth;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

public record BrowserAuthSession(
        URI authorizationUri,
        URI redirectUri,
        String state,
        PkceChallenge pkceChallenge,
        LocalCallbackServer callbackServer
) implements AutoCloseable {
    public CompletableFuture<AuthorizationCallback> callbackFuture() {
        return callbackServer.callbackFuture();
    }

    @Override
    public void close() {
        callbackServer.close();
    }
}
