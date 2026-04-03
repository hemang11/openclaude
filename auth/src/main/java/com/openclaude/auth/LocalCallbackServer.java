package com.openclaude.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class LocalCallbackServer implements AutoCloseable {
    private final HttpServer httpServer;
    private final String callbackHost;
    private final String callbackPath;
    private final CompletableFuture<AuthorizationCallback> callbackFuture = new CompletableFuture<>();

    public LocalCallbackServer() {
        this("127.0.0.1", 0, "/callback");
    }

    public LocalCallbackServer(String callbackHost, int callbackPort, String callbackPath) {
        try {
            this.callbackHost = callbackHost == null || callbackHost.isBlank() ? "127.0.0.1" : callbackHost;
            this.callbackPath = normalizePath(callbackPath);
            this.httpServer = HttpServer.create(new InetSocketAddress(this.callbackHost, callbackPort), 0);
            this.httpServer.createContext(this.callbackPath, this::handleExchange);
            this.httpServer.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start local callback server", exception);
        }
    }

    public URI callbackUri() {
        return URI.create("http://" + callbackHost + ":" + httpServer.getAddress().getPort() + callbackPath);
    }

    public CompletableFuture<AuthorizationCallback> callbackFuture() {
        return callbackFuture;
    }

    private void handleExchange(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> parameters = parseQuery(exchange.getRequestURI().getRawQuery());
            callbackFuture.complete(new AuthorizationCallback(
                    parameters.get("code"),
                    parameters.get("state"),
                    parameters));

            byte[] response = "OpenClaude authentication received. You can return to the terminal.".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        return Arrays.stream(rawQuery.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> decode(pair[0]),
                        pair -> pair.length > 1 ? decode(pair[1]) : ""));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        httpServer.stop(0);
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/callback";
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
