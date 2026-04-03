package com.openclaude.core.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openclaude.core.session.ConversationSession;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanModeToolRuntimeTest {
    private final EnterPlanModeToolRuntime enterRuntime = new EnterPlanModeToolRuntime();
    private final ExitPlanModeToolRuntime exitRuntime = new ExitPlanModeToolRuntime();

    @Test
    void enterPlanModeSetsPlanModeSessionEffect() {
        ToolExecutionResult result = enterRuntime.execute(new ToolExecutionRequest("tool-1", "EnterPlanMode", "{}"));

        assertFalse(result.error());
        assertTrue(Boolean.TRUE.equals(result.sessionEffect().planMode()));
        assertTrue(result.text().contains("Entered plan mode"));
    }

    @Test
    void enterPlanModeRequestsPermission() {
        List<ToolExecutionUpdate> updates = new ArrayList<>();

        ToolExecutionResult result = enterRuntime.execute(
                new ToolExecutionRequest("tool-1", "EnterPlanMode", "{}"),
                request -> ToolPermissionDecision.allow("approved"),
                updates::add
        );

        assertFalse(result.error());
        assertTrue(updates.stream().anyMatch(update -> "permission_requested".equals(update.phase())));
        assertTrue(updates.stream().anyMatch(update -> "enter_plan_mode".equals(update.interactionType())));
    }

    @Test
    void exitPlanModeRequiresPlanModeAndApproval() {
        ConversationSession session = ConversationSession.create("session-1").withPlanMode(true);
        ToolExecutionResult result = exitRuntime.execute(
                new ToolExecutionRequest("tool-1", "ExitPlanMode", "{}", session),
                request -> ToolPermissionDecision.allow("approved"),
                update -> {}
        );

        assertFalse(result.error());
        assertTrue(Boolean.FALSE.equals(result.sessionEffect().planMode()));
    }

    @Test
    void exitPlanModeFailsOutsidePlanMode() {
        ConversationSession session = ConversationSession.create("session-1");
        ToolExecutionResult result = exitRuntime.execute(
                new ToolExecutionRequest("tool-1", "ExitPlanMode", "{}", session),
                request -> ToolPermissionDecision.allow("approved"),
                update -> {}
        );

        assertTrue(result.error());
        assertTrue(result.text().contains("not in plan mode"));
    }
}
