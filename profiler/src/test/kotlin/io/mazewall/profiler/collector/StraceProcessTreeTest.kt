package io.mazewall.profiler.collector
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StraceProcessTreeTest {
    @Test
    fun `interruption terminates descendants and preserves interrupt status`() {
        val process = ProcessBuilder("sh", "-c", "sh -c 'trap \"\" TERM; while :; do :; done' & wait").start()
        val child = awaitDescendant(process)
        val interrupted = AtomicBoolean()
        val waiter = Thread {
            try {
                StraceProcessTree.await(process, Duration.ofSeconds(30), "test process")
            } catch (_: InterruptedException) {
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        waiter.start()
        try {
            waiter.interrupt()
            waiter.join(5_000)

            assertFalse(waiter.isAlive)
            assertTrue(interrupted.get())
            assertFalse(process.isAlive)
            assertFalse(child.isAlive, "captured TERM-resistant child must be killed after reparenting")
        } finally {
            child.destroyForcibly()
            process.destroyForcibly()
            waiter.interrupt()
            waiter.join(5_000)
        }
    }

    private fun awaitDescendant(process: Process): ProcessHandle {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            process
                .toHandle()
                .descendants()
                .findFirst()
                .orElse(null)
                ?.let { return it }
            Thread.onSpinWait()
        }
        error("child process did not start")
    }
}
