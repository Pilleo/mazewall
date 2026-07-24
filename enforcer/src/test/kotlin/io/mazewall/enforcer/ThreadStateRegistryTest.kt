package io.mazewall.enforcer

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadStateRegistryTest {

    @AfterEach
    fun teardown() {
        ThreadStateRegistry.state = ContainerState()
        ProcessStateRegistry.state = ContainerState()
    }

    @Test
    fun `test state getting and setting`() {
        val original = ThreadStateRegistry.state
        assertNotNull(original)

        val newState = ContainerState(filterDepth = 42)
        ThreadStateRegistry.state = newState
        assertEquals(newState, ThreadStateRegistry.state)
        assertEquals(42, ThreadStateRegistry.state.filterDepth)
    }

    @Test
    fun `test resolveCurrentState caches resolved state and invalidates correctly`() {
        // Ensure clean initial state
        ThreadStateRegistry.state = ContainerState()
        ProcessStateRegistry.state = ContainerState()

        val state1 = ContainerState.resolveCurrentState()
        val state2 = ContainerState.resolveCurrentState()

        // They must be the exact same instance due to caching!
        assertTrue(state1 === state2, "Expected cached state to be returned on subsequent resolves")

        // Invalidate thread state
        ThreadStateRegistry.state = ContainerState(filterDepth = 1)
        val state3 = ContainerState.resolveCurrentState()
        assertTrue(state1 !== state3, "Expected new resolved state after thread state modification")
        assertEquals(1, state3.filterDepth)

        val state4 = ContainerState.resolveCurrentState()
        assertTrue(state3 === state4, "Expected cached state after new thread state resolution")

        // Invalidate process state
        ProcessStateRegistry.state = ContainerState(filterDepth = 5)
        val state5 = ContainerState.resolveCurrentState()
        assertTrue(state4 !== state5, "Expected new resolved state after process state modification")
        assertEquals(6, state5.filterDepth) // 1 (thread) + 5 (process)

        val state6 = ContainerState.resolveCurrentState()
        assertTrue(state5 === state6, "Expected cached state after new process state resolution")
    }
}
