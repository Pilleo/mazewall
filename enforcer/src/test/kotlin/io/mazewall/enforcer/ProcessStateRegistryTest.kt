package io.mazewall.enforcer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProcessStateRegistryTest {

    @Test
    fun `test ProcessStateRegistry update`() {
        val originalState = ProcessStateRegistry.state
        assertNotNull(originalState)

        try {
            ProcessStateRegistry.update { state ->
                state.copy(filterDepth = 100)
            }

            assertEquals(100, ProcessStateRegistry.state.filterDepth)
        } finally {
            // Restore state to avoid polluting other tests
            ProcessStateRegistry.state = originalState
        }
    }

    @Test
    fun `test ProcessStateRegistry concurrent updates and state resolutions`() {
        val originalState = ProcessStateRegistry.state
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
                            ProcessStateRegistry.update { state ->
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
                            val resolved = ContainerState.resolveCurrentState()
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
            ProcessStateRegistry.state = originalState
        }
    }
}
