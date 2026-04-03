package com.openclaude.provider.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ProviderHttpException extends RuntimeException {
    private final ProviderId providerId;
    private final int statusCode;
    private final String responseBody;
    private final Map<String, List<String>> responseHeaders;

    public ProviderHttpException(
            ProviderId providerId,
            int statusCode,
            String responseBody,
            Map<String, List<String>> responseHeaders,
            String message
    ) {
        super(message);
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
        this.responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
    }

    public ProviderId providerId() {
        return providerId;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }

    public Map<String, List<String>> responseHeaders() {
        return responseHeaders;
    }
}
