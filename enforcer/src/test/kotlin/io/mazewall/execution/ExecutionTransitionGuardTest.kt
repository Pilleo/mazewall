package io.mazewall.enforcer.internal.execution

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ExecutionTransitionGuardTest {
    @Test
    fun `host dispatch and exact active inline are the only accepted transitions`() {
        assertEquals(ExecutionTransition.Dispatch, ExecutionTransitionGuard.evaluate(false, false, false, false))
        assertEquals(ExecutionTransition.InlineCurrentWorker, ExecutionTransitionGuard.evaluate(true, true, true, true))
        assertEquals(ExecutionTransition.Reject, ExecutionTransitionGuard.evaluate(true, false, true, true))
        assertEquals(ExecutionTransition.Reject, ExecutionTransitionGuard.evaluate(true, true, false, true))
        assertEquals(ExecutionTransition.Reject, ExecutionTransitionGuard.evaluate(false, false, false, true))
    }
}
