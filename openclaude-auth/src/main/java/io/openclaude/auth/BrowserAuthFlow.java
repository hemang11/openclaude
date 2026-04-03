package io.openclaude.auth;

import java.net.URI;

public interface BrowserAuthFlow {
    URI buildAuthorizationUri();
}

