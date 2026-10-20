package io.mazewall.enforcer.diagnostics

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompletionPhaseSlotTest {
    @Test
    fun `worker identity is fixed and evidence cannot regress`() {
        val slot = CompletionPhaseSlot()
        slot.bind(41L, 124)
        slot.advance(CompletionPhase.INSTALLED)
        val before = slot.read()
        slot.advance(CompletionPhase.RETURNING)
        assertEquals(CompletionSample(41L, 124, CompletionPhase.INSTALLED), before)
        assertEquals(CompletionSample(41L, 124, CompletionPhase.RETURNING), slot.read())
        assertFailsWith<IllegalStateException> { slot.bind(42L, 125) }
        assertFailsWith<IllegalArgumentException> { slot.advance(CompletionPhase.INSTALLING) }
    }
}
