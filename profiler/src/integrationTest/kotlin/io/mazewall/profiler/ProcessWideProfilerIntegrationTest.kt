package io.mazewall.profiler

import io.mazewall.BaseIntegrationTest
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
        val javaBin = System.getProperty("java.home") + "/bin/java"
        val classpath = System.getProperty("java.class.path")
        val className = "io.mazewall.profiler.ProcessWideProfilerIntegrationTest\$SubprocessMain"
        
        val pb = ProcessBuilder(javaBin, "-cp", classpath, className)
        pb.inheritIO()
        val process = pb.start()
        val exitCode = process.waitFor()
        kotlin.test.assertEquals(0, exitCode, "Process-wide profiling in subprocess failed")
    }

    object SubprocessMain {
        @JvmStatic
        fun main(args: Array<String>) {
            val targetFile = File("/etc/hostname")
            assertTrue(targetFile.exists())

            val latch = java.util.concurrent.CountDownLatch(1)
            val testLatch = java.util.concurrent.CountDownLatch(1)
            val bgThread = Thread {
                latch.await()
                try {
                    targetFile.readText()
                } finally {
                    testLatch.countDown()
                }
            }
            bgThread.start()

            try {
                val result = Profiler.profile(processWide = true) {
                    latch.countDown()
                    testLatch.await(5, TimeUnit.SECONDS)
                }
                val bob = result.behavior
                assertTrue(bob.opens.contains("/etc/hostname"), "Process-wide profiling should capture /etc/hostname from background thread")
            } catch (e: IllegalStateException) {
                // If run on host without container privilege boundary, TSYNC will fail with EACCES
                assertTrue(e.message?.contains("EACCES") == true || e.message?.contains("Permission denied") == true)
            } finally {
                latch.countDown()
                bgThread.join()
                Profiler.shutdown() // Explicitly shutdown daemon in subprocess
            }
        }
    }
}
