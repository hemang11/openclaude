package com.openclaude.core.provider;

import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.query.QueryEngine;
import com.openclaude.core.query.QueryTurnResult;
import com.openclaude.core.session.ConversationSessionStore;
import com.openclaude.core.tools.ToolPermissionGateway;
import com.openclaude.provider.spi.PromptEvent;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class PromptRouter {
    private final QueryEngine queryEngine;

    public PromptRouter(
            ProviderRegistry providerRegistry,
            OpenClaudeStateStore stateStore,
            ConversationSessionStore sessionStore
    ) {
        this.queryEngine = new QueryEngine(providerRegistry, stateStore, sessionStore);
    }

    public QueryTurnResult execute(String prompt) {
        return queryEngine.submit(prompt);
    }

    public QueryTurnResult execute(String prompt, Consumer<String> textDeltaConsumer) {
        return queryEngine.submit(prompt, textDeltaConsumer);
    }

    public QueryTurnResult executeWithEvents(String prompt, Consumer<PromptEvent> eventConsumer) {
        return queryEngine.submitWithEvents(prompt, eventConsumer);
    }

    public QueryTurnResult executeWithEvents(
            String prompt,
            Consumer<PromptEvent> eventConsumer,
            ToolPermissionGateway permissionGateway
    ) {
        return queryEngine.submitWithEvents(prompt, eventConsumer, permissionGateway);
    }

    public QueryTurnResult executeWithEvents(
            String prompt,
            Consumer<PromptEvent> eventConsumer,
            ToolPermissionGateway permissionGateway,
            Supplier<String> cancellationReasonSupplier
    ) {
        return queryEngine.submitWithEvents(prompt, eventConsumer, permissionGateway, cancellationReasonSupplier);
    }
}
