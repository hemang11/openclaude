package com.openclaude.provider.spi;

public enum AuthMethod {
    API_KEY("api_key"),
    BROWSER_SSO("browser_sso"),
    AWS_CREDENTIALS("aws_credentials");

    private final String cliValue;

    AuthMethod(String cliValue) {
        this.cliValue = cliValue;
    }

    public String cliValue() {
        return cliValue;
    }
}
