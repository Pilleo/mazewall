package io.mazewall.enforcer.api

import io.mazewall.enforcer.state.ContainmentStateRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContainedExecutorWrapTest {
    @Test
    @EnabledIfLinuxAndSupported
    fun `experimental coroutine resumes through required internal installation after public retirement`() {
        val caller = Thread.currentThread()
        var worker: Thread? = null
        try {
            runBlocking {
                withTimeout(5_000) {
                    val result = SandboxDispatcher.executeSuspend(Policy.NO_EXEC_HOTSPOT) {
                        worker = Thread.currentThread()
                        assertTrue(worker !== caller)
                        assertFalse(Thread.currentThread().isVirtual)
                        assertTrue(ContainmentStateRegistry.threadState.filterDepth > 0)
                        yield()
                        assertTrue(ContainmentStateRegistry.threadState.filterDepth > 0)
                        42
                    }
                    assertEquals(42, result)
                }
            }
        } finally {
            SandboxDispatcher.shutdownAll()
        }
        val ownedWorker = checkNotNull(worker)
        // Executor termination can precede JVM thread-exit hooks.
        ownedWorker.join(5_000)
        assertFalse(ownedWorker.isAlive, "Contained platform worker did not physically terminate")
    }

    @Test
    fun `borrowed executor entrypoints and view types are absent from emitted API`() {
        val retired = setOf("wrap", "wrapTyped", "newSingleThreadExecutor", "newFixedThreadPool", "newCachedThreadPool")
        assertFalse(ContainedExecutors::class.java.methods.any { it.name in retired })
        for (name in listOf("ContainedExecutor", "ContainedExecutorView")) {
            assertFailsWith<ClassNotFoundException> {
                Class.forName("io.mazewall.enforcer.api.$name")
            }
        }
    }
}
