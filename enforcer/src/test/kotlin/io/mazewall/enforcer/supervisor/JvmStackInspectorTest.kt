package io.mazewall.enforcer.supervisor

import org.junit.jupiter.api.Test
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
}
