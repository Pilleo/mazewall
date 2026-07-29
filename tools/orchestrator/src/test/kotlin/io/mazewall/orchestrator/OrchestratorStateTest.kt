package io.mazewall.orchestrator

import kotlin.test.Test
import kotlin.test.assertTrue

class OrchestratorStateTest {

    @Test
    fun `fromName resolves correctly`() {
        assertTrue(OrchestratorState.fromName("SELECT_TASK") is SelectTaskState)
        assertTrue(OrchestratorState.fromName("AWAITING_JULES_START") is AwaitingJulesStartState)
        assertTrue(OrchestratorState.fromName("PENDING_APPROVAL") is PendingApprovalState)
        assertTrue(OrchestratorState.fromName("AWAITING_PR") is AwaitingPrState)
        assertTrue(OrchestratorState.fromName("CI_RUNNING") is CiRunningState)
        assertTrue(OrchestratorState.fromName("AWAITING_REVIEW") is AwaitingReviewState)
        assertTrue(OrchestratorState.fromName("AWAITING_MERGE") is AwaitingMergeState)
        assertTrue(OrchestratorState.fromName("RESOLVE_TASK") is ResolveTaskState)
    }

    @Test
    fun `fromName resolves legacy names correctly`() {
        assertTrue(OrchestratorState.fromName("AWAIT_START_APPROVAL") is PendingApprovalState)
        assertTrue(OrchestratorState.fromName("AWAIT_JULES_START") is AwaitingJulesStartState)
        assertTrue(OrchestratorState.fromName("AWAIT_PR_CREATION") is AwaitingPrState)
        assertTrue(OrchestratorState.fromName("MONITOR_PR") is CiRunningState)
        assertTrue(OrchestratorState.fromName(null) is SelectTaskState)
        assertTrue(OrchestratorState.fromName("UNKNOWN") is SelectTaskState)
    }
}
