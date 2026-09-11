package demo

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class OwnedProcessRunnerTest {
    @Test
    fun `submission failure still retires the owned executor`() {
        val executor = Executors.newSingleThreadExecutor()

        val failure =
            assertFailsWith<IllegalStateException> {
                OwnedActionRunner.run<String>(executor) { throw IllegalStateException("submission failed") }
            }

        assertEquals("submission failed", failure.message)
        assertTrue(executor.isTerminated)
    }

    @Test
    fun `timeout terminates and reaps descendants`() {
        val outcome =
            OwnedProcessRunner.run(
                listOf("sh", "-c", "sleep 30 & child=${'$'}!; echo ${'$'}child; wait ${'$'}child"),
                Duration.ofMillis(250),
            )

        val timedOut = assertIs<ProcessOutcome.TimedOut>(outcome)
        val childPid = timedOut.output.lineSequence().first { it.isNotBlank() }.trim().toLong()
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
    }

    @Test
    fun `interruption terminates process and restores caller interrupt`() {
        val ready = Files.createTempFile("cli-owned-process-", ".ready")
        Files.delete(ready)
        val outcome = AtomicReference<ProcessOutcome>()
        val interrupted = AtomicBoolean()
        val caller =
            Thread {
                outcome.set(
                    OwnedProcessRunner.run(
                        listOf("sh", "-c", "touch '${ready.toAbsolutePath()}'; sleep 30"),
                        Duration.ofSeconds(30),
                    ),
                )
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        caller.start()
        eventually { Files.exists(ready) }
        caller.interrupt()
        caller.join(5_000)

        assertFalse(caller.isAlive)
        assertIs<ProcessOutcome.Interrupted>(outcome.get())
        assertTrue(interrupted.get())
        Files.deleteIfExists(ready)
    }

    @Test
    fun `safe and unsafe runners do not return before interrupted work stops`() {
        listOf<(()-> String) -> Unit>(SafeRunner::runContained, UnsafeRunner::runAction).forEach { runner ->
            val started = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            val callerInterrupted = AtomicBoolean()
            val caller =
                Thread {
                    try {
                        runner {
                            started.countDown()
                            try {
                                CountDownLatch(1).await()
                                "unreachable"
                            } finally {
                                stopped.countDown()
                            }
                        }
                    } catch (_: InterruptedException) {
                        callerInterrupted.set(Thread.currentThread().isInterrupted)
                    }
                }
            caller.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            caller.interrupt()
            caller.join(5_000)

            assertFalse(caller.isAlive)
            assertEquals(0, stopped.count, "worker must stop before fixture-owning caller returns")
            assertTrue(callerInterrupted.get())
        }
    }

    private fun eventually(assertion: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!assertion() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(assertion())
    }
}
