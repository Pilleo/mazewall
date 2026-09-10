package io.mazewall.profiler.collector

import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StraceProcessTreeTest {
    @Test
    fun `interruption terminates descendants and preserves interrupt status`() {
        val process = ProcessBuilder("sh", "-c", "sleep 30 & wait").start()
        val interrupted = AtomicBoolean()
        val waiter = Thread {
            try {
                StraceProcessTree.await(process, Duration.ofSeconds(30), "test process")
            } catch (_: InterruptedException) {
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        waiter.start()
        while (process
            .toHandle()
            .descendants()
            .findAny()
            .isEmpty
        ) {
                Thread.onSpinWait()
            }

        waiter.interrupt()
        waiter.join(5_000)

        assertFalse(waiter.isAlive)
        assertTrue(interrupted.get())
        assertFalse(process.isAlive)
        assertTrue(process.toHandle().descendants().noneMatch(ProcessHandle::isAlive))
    }
}
