package com.openclaude.auth;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

public final class DesktopBrowserLauncher implements BrowserLauncher {
    @Override
    public boolean open(URI uri) {
        if (uri == null) {
            return false;
        }

        if (Desktop.isDesktopSupported()) {
            try {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(uri);
                    return true;
                }
            } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            }
        }

        return false;
    }
}
