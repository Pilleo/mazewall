package io.mazewall

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IsolatedProcessTesterLifecycleTest {
    @Test
    fun `invalid timeout is rejected before the child starts`() {
        val root = Files.createTempDirectory("isolated-invalid-timeout-")
        withTimeoutProperty("invalid") {
            assertIs<IllegalArgumentException>(
                runCatching {
                    IsolatedProcessTester.runIsolatedTest(
                        IsolatedProcessLifecycleFixture::class.java.name,
                        "exit",
                        root.toString(),
                    )
                }.exceptionOrNull(),
            )
        }
        Thread.sleep(250)
        assertFalse(root.resolve("parent.pid").exists(), "timeout validation must happen before ProcessBuilder.start")
    }

    @Test
    fun `timeout forcibly reaps the parent and descendants within a bound`() {
        val root = Files.createTempDirectory("isolated-timeout-")
        val started = System.nanoTime()
        withTimeoutProperty("1") {
            assertIs<IllegalStateException>(
                runCatching {
                    IsolatedProcessTester.runIsolatedTest(
                        IsolatedProcessLifecycleFixture::class.java.name,
                        "hang",
                        root.toString(),
                    )
                }.exceptionOrNull(),
            )
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(8))
        assertEventuallyDead(root.resolve("parent.pid"))
        assertEventuallyDead(root.resolve("child.pid"))
    }

    @Test
    fun `interruption reaps the process tree and preserves interrupt status`() {
        val root = Files.createTempDirectory("isolated-interrupt-")
        val failure = AtomicReference<Throwable>()
        val interrupted = AtomicBoolean()
        val waiter = Thread {
            try {
                IsolatedProcessTester.runIsolatedTest(IsolatedProcessLifecycleFixture::class.java.name, "hang", root.toString())
            } catch (caught: Throwable) {
                failure.set(caught)
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        withTimeoutProperty("30") {
            waiter.start()
            waitFor(root.resolve("ready"))
            waiter.interrupt()
            waiter.join(8_000)
        }
        assertFalse(waiter.isAlive, "interrupted waiter must terminate within the cleanup bound")
        assertIs<IllegalStateException>(failure.get())
        assertTrue(interrupted.get(), "interrupt status must be restored")
        assertEventuallyDead(root.resolve("parent.pid"))
        assertEventuallyDead(root.resolve("child.pid"))
    }

    private fun withTimeoutProperty(
        value: String,
        action: () -> Unit,
    ) {
        val key = "io.mazewall.isolatedTestTimeoutSeconds"
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, value)
            action()
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }

    private fun waitFor(path: java.nio.file.Path) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!path.exists() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(path.exists(), "fixture did not become ready: $path")
    }

    private fun assertEventuallyDead(pidFile: java.nio.file.Path) {
        waitFor(pidFile)
        val pid = Files.readString(pidFile).trim().toLong()
        val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "process $pid was not reaped")
    }
}
