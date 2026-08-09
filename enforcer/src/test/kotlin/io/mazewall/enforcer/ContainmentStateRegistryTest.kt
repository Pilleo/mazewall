package io.mazewall.enforcer

import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContainmentStateRegistryTest {

    @AfterEach
    fun teardown() {
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
    }

    @Test
    fun `test ProcessStateRegistry update`() {
        val originalState = ContainmentStateRegistry.processState
        assertNotNull(originalState)

        try {
            ContainmentStateRegistry.updateProcessState { state ->
                state.copy(filterDepth = 100)
            }

            assertEquals(100, ContainmentStateRegistry.processState.filterDepth)
        } finally {
            // Restore state to avoid polluting other tests
            ContainmentStateRegistry.processState = originalState
        }
    }

    @Test
    fun `test ProcessStateRegistry concurrent updates and state resolutions`() {
        val originalState = ContainmentStateRegistry.processState
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val exceptions = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

        try {
            val tasks = mutableListOf<java.util.concurrent.Future<*>>()

            // Spawn 4 threads updating the process state
            repeat(4) {
                tasks.add(executor.submit {
                    while (!stopFlag.get()) {
                        try {
                            ContainmentStateRegistry.updateProcessState { state ->
                                state.copy(filterDepth = state.filterDepth + 1)
                            }
                        } catch (t: Throwable) {
                            exceptions.add(t)
                        }
                    }
                })
            }

            // Spawn 4 threads concurrently resolving current state
            repeat(4) {
                tasks.add(executor.submit {
                    while (!stopFlag.get()) {
                        try {
                            val resolved = ContainmentStateRegistry.resolveCurrentState()
                            assertNotNull(resolved)
                        } catch (t: Throwable) {
                            exceptions.add(t)
                        }
                    }
                })
            }

            // Let them run for 300ms
            Thread.sleep(300)
            stopFlag.set(true)

            // Wait for all tasks to complete
            tasks.forEach { it.get() }

            // Check if any exceptions were thrown
            assertTrue(exceptions.isEmpty(), "Expected no concurrent modification exceptions, but got: ${exceptions.map { it.stackTraceToString() }}")

        } finally {
            executor.shutdownNow()
            executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
            ContainmentStateRegistry.processState = originalState
        }
    }
    
    @Test
    fun `test state getting and setting`() {
        val original = ContainmentStateRegistry.threadState
        assertNotNull(original)

        val newState = ContainerState(filterDepth = 42)
        ContainmentStateRegistry.threadState = newState
        assertEquals(newState, ContainmentStateRegistry.threadState)
        assertEquals(42, ContainmentStateRegistry.threadState.filterDepth)
    }

    @Test
    fun `test resolveCurrentState caches resolved state and invalidates correctly`() {
        val state1 = ContainmentStateRegistry.resolveCurrentState()
        val state2 = ContainmentStateRegistry.resolveCurrentState()

        // They must be the exact same instance due to caching!
        assertTrue(state1 === state2, "Expected cached state to be returned on subsequent resolves")

        // Invalidate thread state
        ContainmentStateRegistry.threadState = ContainerState(filterDepth = 1)
        val state3 = ContainmentStateRegistry.resolveCurrentState()
        assertTrue(state1 !== state3, "Expected new resolved state after thread state modification")
        assertEquals(1, state3.filterDepth)

        val state4 = ContainmentStateRegistry.resolveCurrentState()
        assertTrue(state3 === state4, "Expected cached state after new thread state resolution")

        // Invalidate process state
        ContainmentStateRegistry.processState = ContainerState(filterDepth = 5)
        val state5 = ContainmentStateRegistry.resolveCurrentState()
        assertTrue(state4 !== state5, "Expected new resolved state after process state modification")
        assertEquals(6, state5.filterDepth) // 1 (thread) + 5 (process)

        val state6 = ContainmentStateRegistry.resolveCurrentState()
        assertTrue(state5 === state6, "Expected cached state after new process state resolution")
    }
}
