package com.hechang.insighthub.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hechang.insighthub.service.TaskDispatchCommand;

class AgentTaskRequestFactoryTest {

    private final AgentTaskRequestFactory factory = new AgentTaskRequestFactory();

    @Test
    void streamRequestContainsTheSharedAgentContract() {
        Map<String, Object> request = factory.forStreamTask(
                "task-1", "workspace-1", "user-1", "query", 120, 8L,
                "run-retry", 2, List.of("kb-1"), true);

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) request.get("config");
        assertEquals("PLAN", request.get("phase"));
        assertEquals(2, request.get("planRevision"));
        assertEquals("run-retry", request.get("runId"));
        assertEquals(List.of("kb-1"), request.get("knowledgeBaseIds"));
        assertTrue((Boolean) config.get("requirePlanApproval"));
        assertTrue((Boolean) config.get("enableDataAnalysis"));
        assertEquals(120, config.get("timeoutSeconds"));
        assertEquals(8L, config.get("nextEventId"));
    }

    @Test
    void synchronousRequestDoesNotAccidentallyRequirePlanApproval() {
        Map<String, Object> request = factory.forSynchronousTask(
                "task-1", "workspace-1", "user-1", "query", null, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) request.get("config");
        assertFalse((Boolean) config.get("requirePlanApproval"));
        assertEquals(List.of(), request.get("knowledgeBaseIds"));
    }

    @Test
    void dispatchRequestPreservesTheOutboxCommandFields() {
        TaskDispatchCommand command = new TaskDispatchCommand(
                "task-1", "workspace-1", "user-1", "query", "trace-1", "run-1",
                "EXECUTE", 2, "use updated scope", "plan-hash", List.of("kb-1"), true);

        Map<String, Object> request = factory.forDispatchCommand(command, 90, null);

        assertEquals("EXECUTE", request.get("phase"));
        assertEquals("run-1", request.get("runId"));
        assertEquals(2, request.get("planRevision"));
        assertEquals("use updated scope", request.get("revisionInstruction"));
    }
}
