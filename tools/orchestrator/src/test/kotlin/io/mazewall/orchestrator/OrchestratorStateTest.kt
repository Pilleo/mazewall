package io.mazewall.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals

class OrchestratorStateTest {

    @Test
    fun `fromName resolves correctly`() {
        assertEquals(OrchestratorState.SELECT_TASK, OrchestratorState.fromName("SELECT_TASK"))
        assertEquals(OrchestratorState.AWAITING_JULES_START, OrchestratorState.fromName("AWAITING_JULES_START"))
        assertEquals(OrchestratorState.PENDING_APPROVAL, OrchestratorState.fromName("PENDING_APPROVAL"))
        assertEquals(OrchestratorState.AWAITING_PR, OrchestratorState.fromName("AWAITING_PR"))
        assertEquals(OrchestratorState.CI_RUNNING, OrchestratorState.fromName("CI_RUNNING"))
        assertEquals(OrchestratorState.AWAITING_REVIEW, OrchestratorState.fromName("AWAITING_REVIEW"))
        assertEquals(OrchestratorState.AWAITING_MERGE, OrchestratorState.fromName("AWAITING_MERGE"))
        assertEquals(OrchestratorState.RESOLVE_TASK, OrchestratorState.fromName("RESOLVE_TASK"))
    }

    @Test
    fun `fromName resolves legacy names correctly`() {
        assertEquals(OrchestratorState.PENDING_APPROVAL, OrchestratorState.fromName("AWAIT_START_APPROVAL"))
        assertEquals(OrchestratorState.AWAITING_JULES_START, OrchestratorState.fromName("AWAIT_JULES_START"))
        assertEquals(OrchestratorState.AWAITING_PR, OrchestratorState.fromName("AWAIT_PR_CREATION"))
        assertEquals(OrchestratorState.CI_RUNNING, OrchestratorState.fromName("MONITOR_PR"))
        assertEquals(OrchestratorState.SELECT_TASK, OrchestratorState.fromName(null))
        assertEquals(OrchestratorState.SELECT_TASK, OrchestratorState.fromName("UNKNOWN"))
    }
}
