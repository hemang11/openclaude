package com.openclaude.auth;

public record PkceChallenge(
        String verifier,
        String challenge
) {
}

