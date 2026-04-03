package com.openclaude.provider.openai;

record OpenAiTokenResponse(
        String idToken,
        String accessToken,
        String refreshToken,
        long expiresInSeconds
) {
    OpenAiTokenResponse {
        idToken = blankToNull(idToken);
        accessToken = blankToNull(accessToken);
        refreshToken = blankToNull(refreshToken);
        expiresInSeconds = expiresInSeconds <= 0 ? 3600 : expiresInSeconds;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
