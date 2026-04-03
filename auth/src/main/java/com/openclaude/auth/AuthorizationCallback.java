package com.openclaude.auth;

import java.util.Map;

public record AuthorizationCallback(
        String code,
        String state,
        Map<String, String> parameters
) {
}

