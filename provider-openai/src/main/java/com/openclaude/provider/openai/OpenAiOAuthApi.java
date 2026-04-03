package com.openclaude.provider.openai;

import com.openclaude.auth.PkceChallenge;
import java.net.URI;

interface OpenAiOAuthApi {
    OpenAiTokenResponse exchangeAuthorizationCode(String code, URI redirectUri, PkceChallenge pkceChallenge);

    OpenAiTokenResponse refreshAccessToken(String refreshToken);
}
