package io.mazewall.enforcer.internal.execution

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GenerationLifecycleTest {
    @Test
    fun `failed or draining startup cannot publish protected readiness`() {
        for (terminal in listOf(GenerationPhase.FAILED, GenerationPhase.DRAINING, GenerationPhase.CLOSED)) {
            assertNotEquals(GenerationPhase.READY, GenerationLifecycle.evaluate(terminal, GenerationEvent.PREPARED))
        }
    }

    @Test
    fun `retired generations never reopen on delayed preparation or failure`() {
        assertEquals(GenerationPhase.CLOSED, GenerationLifecycle.evaluate(GenerationPhase.CLOSED, GenerationEvent.PREPARED))
        assertEquals(GenerationPhase.CLOSED, GenerationLifecycle.evaluate(GenerationPhase.CLOSED, GenerationEvent.FAILED))
    }
}
