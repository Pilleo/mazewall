package io.mazewall.profiler.api

import io.mazewall.enforcer.api.BaseIntegrationTest
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class ProcessWideProfilerIntegrationTest : BaseIntegrationTest() {
    companion object {
        @org.junit.jupiter.api.AfterAll
        @JvmStatic
        fun tearDownAll() {
            Profiler.shutdown()
        }
    }

    @Test
    fun `test processWide profiling captures background thread syscalls`() {
        val className = "io.mazewall.profiler.api.ProcessWideProfilerIntegrationTest\$SubprocessMain"
        io.mazewall.enforcer.api.IsolatedProcessTester
            .runIsolatedTest(className)
    }

    object SubprocessMain {
        @JvmStatic
        fun main(args: Array<String>) {
            val targetFile = File("/etc/hostname")
            assertTrue(targetFile.exists())

            val latch = java.util.concurrent.CountDownLatch(1)
            val completed = java.util.concurrent.CompletableFuture<String>()
            val bgThread = Thread {
                latch.await()
                try {
                    completed.complete(targetFile.readText())
                } catch (failure: Throwable) {
                    completed.completeExceptionally(failure)
                }
            }
            bgThread.start()

            try {
                val result = Profiler.profile(processWide = true) {
                    latch.countDown()
                    completed.get(5, TimeUnit.SECONDS)
                }
                assertTrue(result.value.isNotBlank(), "The actual background read must succeed")
                val bob = result.behavior
                assertTrue(bob.opens.contains("/etc/hostname"), "Process-wide profiling should capture /etc/hostname from background thread")
            } finally {
                latch.countDown()
                bgThread.join(5_000)
                kotlin.test.assertFalse(bgThread.isAlive, "Background producer must physically retire")
                Profiler.shutdown() // Explicitly shutdown daemon in subprocess
            }
        }
    }
}
