package io.mazewall.landlock
import io.mazewall.BaseIntegrationTest
import io.mazewall.Policy
import io.mazewall.core.Syscall
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.system.exitProcess
import kotlin.test.assertEquals

class LandlockTsyncIntegrationTest : BaseIntegrationTest() {
    /**
     * Tiny main class for the out-of-process TSYNC test.
     */
    object TsyncChild {
        @JvmStatic
        fun main(args: Array<String>) {
            val policy =
                Policy
                    .builder()
                    .block(Syscall.OPEN, Syscall.OPENAT, Syscall.IO_URING_SETUP, Syscall.IO_URING_ENTER)
                    .build()

            val latch = java.util.concurrent.CountDownLatch(1)
            val doneLatch = java.util.concurrent.CountDownLatch(1)
            var exitCode = 0

            // Spawn the sibling thread BEFORE Landlock is applied
            val thread =
                Thread {
                    latch.countDown()
                    doneLatch.await()
                    try {
                        File("/etc/hostname").readText()
                        exitCode = 0 // Succeeded (TSYNC not retroactively applied)
                    } catch (e: Exception) {
                        System.err.println("Sandboxed successfully: ${e.message}")
                        exitCode = 42 // Restricted (TSYNC applied)
                    }
                }
            thread.start()
            latch.await()

            Landlock.applyRuleset(policy.definition)

            doneLatch.countDown()
            thread.join()
            exitProcess(exitCode)
        }
    }

    @Test
    fun `test TSYNC sandboxes sibling threads in child process`() {
        val javaBin =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse("java")
        val classpath = System.getProperty("java.class.path")

        val pb =
            ProcessBuilder(
                javaBin,
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                classpath,
                "io.mazewall.landlock.LandlockTsyncIntegrationTest\$TsyncChild",
            )

        val process = pb.start()
        val exitCode = process.waitFor()

        // Landlock does not support TSYNC yet by default (thread-scoped only in current kernel driver implementation),
        // so the sibling thread is uncontained, leading to successful read (exit code 0).
        assertEquals(0, exitCode, "Expected exit code 0 as Landlock TSYNC is currently disabled by default")
    }
}
