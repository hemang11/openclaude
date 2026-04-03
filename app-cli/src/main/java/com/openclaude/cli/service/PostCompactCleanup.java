package com.openclaude.cli.service;

import com.openclaude.core.session.ConversationSession;
import java.util.Map;

final class PostCompactCleanup {
    ConversationSession apply(ConversationSession session) {
        return session.withReadFileState(Map.of());
    }
}
