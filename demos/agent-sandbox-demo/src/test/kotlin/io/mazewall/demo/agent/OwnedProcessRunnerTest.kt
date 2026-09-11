package io.mazewall.demo.agent

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OwnedProcessRunnerTest {
    @Test
    fun `stderr-heavy child cannot block process completion`() {
        val outcome = OwnedProcessRunner.run(
            listOf("sh", "-c", "head -c 1048576 /dev/zero >&2; printf completed"),
            Duration.ofSeconds(5),
        )

        val completed = assertIs<ProcessOutcome.Completed>(outcome)
        assertTrue(completed.output.length < 70_000, "captured diagnostics must remain bounded")
    }

    @Test
    fun `timeout terminates descendants and parent`() {
        val fixture = Files.createTempDirectory("agent-process-timeout-")
        try {
            val childPidFile = fixture.resolve("child.pid")
            val outcome = OwnedProcessRunner.run(
                listOf("sh", "-c", "sleep 120 & echo \$! > '${childPidFile}'; wait"),
                Duration.ofMillis(300),
            )

            assertIs<ProcessOutcome.TimedOut>(outcome)
            val childPid = Files.readString(childPidFile).trim().toLong()
            assertProcessStops(childPid)
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }

    @Test
    fun `interruption terminates descendants and preserves caller interrupt status`() {
        val fixture = Files.createTempDirectory("agent-process-interrupt-")
        try {
            val childPidFile = fixture.resolve("child.pid")
            val outcome = AtomicReference<ProcessOutcome>()
            val interrupted = AtomicBoolean()
            val caller = Thread.ofPlatform().start {
                outcome.set(
                    OwnedProcessRunner.run(
                        listOf("sh", "-c", "sleep 120 & echo \$! > '${childPidFile}'; wait"),
                        Duration.ofSeconds(30),
                    ),
                )
                interrupted.set(Thread.currentThread().isInterrupted)
            }
            waitForFile(childPidFile)

            caller.interrupt()
            caller.join(5_000)

            assertFalse(caller.isAlive, "interrupted process owner did not terminate")
            assertIs<ProcessOutcome.Interrupted>(outcome.get())
            assertTrue(interrupted.get(), "interrupt status was not restored")
            assertProcessStops(Files.readString(childPidFile).trim().toLong())
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }

    @Test
    fun `interrupted tool call cancels accepted work before fixture cleanup`() {
        val fixture = Files.createTempDirectory("agent-tool-interrupt-")
        try {
            val started = fixture.resolve("started")
            val lateWrite = fixture.resolve("late-write")
            val tools = AgentTools(false, fixture.toFile())
            val caller = Thread.ofPlatform().start {
                runCatching {
                    tools.executeDataAnalysis(
                        "touch '${started}'; (sleep 2; touch '${lateWrite}') & wait",
                    )
                }
            }
            waitForFile(started)

            caller.interrupt()
            caller.join(5_000)
            tools.shutdown()
            Thread.sleep(2_200)

            assertFalse(caller.isAlive)
            assertFalse(Files.exists(lateWrite), "cancelled process wrote after tool cleanup")
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }

    private fun waitForFile(path: java.nio.file.Path) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!Files.exists(path) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(Files.exists(path), "child did not publish its lifecycle fixture")
    }

    private fun assertProcessStops(pid: Long) {
        val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "descendant $pid remained alive")
    }
}
