package com.openclaude.auth;

import com.openclaude.provider.spi.ProviderId;

public interface BrowserAuthCoordinator {
    boolean supports(ProviderId providerId);

    BrowserAuthSession begin(BrowserAuthRequest request);
}
