package io.mazewall.enforcer.supervisor

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class JvmStackInspectorTest {

    @Test
    fun `inspect returns SafeToValidate with current stack and arguments`() {
        val state = JvmStackInspector.inspect(42, listOf("arg1"), Thread.currentThread())
        assertTrue(state is ScopingValidationState.SafeToValidate)
        assertEquals(42, state.nr)
        assertEquals(listOf("arg1"), state.argsList)
        assertTrue(state.rawStack.isNotEmpty())
    }

    @Test
    fun `inspect with null thread returns SafeToValidate with empty rawStack`() {
        val state = JvmStackInspector.inspect(1, listOf("arg1"), null)
        assertTrue(state is ScopingValidationState.SafeToValidate)
        assertEquals(1, state.nr)
        assertEquals(listOf("arg1"), state.argsList)
        assertTrue(state.rawStack.isEmpty(), "rawStack should be empty when targetThread is null")
    }

    @Test
    fun `inspect twice returns different stack arrays`() {
        val state1 = JvmStackInspector.inspect(1, listOf("arg1"), Thread.currentThread())
        val state2 = JvmStackInspector.inspect(1, listOf("arg1"), Thread.currentThread())

        assertTrue(state1 is ScopingValidationState.SafeToValidate)
        assertTrue(state2 is ScopingValidationState.SafeToValidate)

        assertFalse(
            state1.rawStack === state2.rawStack,
            "rawStack arrays should be different instances to prove no caching"
        )
    }

    @Test
    fun `inspect with different nr returns updated nr`() {
        val state1 = JvmStackInspector.inspect(1, listOf("arg1"), Thread.currentThread())
        val state2 = JvmStackInspector.inspect(2, listOf("arg2"), Thread.currentThread())

        assertTrue(state1 is ScopingValidationState.SafeToValidate)
        assertTrue(state2 is ScopingValidationState.SafeToValidate)

        assertEquals(1, state1.nr, "First call should have nr=1")
        assertEquals(2, state2.nr, "Second call should have nr=2, not stale nr=1")
    }
}
