package io.mazewall.profiler.hotspot

import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorProcess
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorTiming
import io.mazewall.profiler.internal.hotspot.HotSpotSessionFailure
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.FilterOutputStream
import java.io.InputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HotSpotSessionLifecycleTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `concurrent finish shares one result and one graceful stop`() {
        val owner = HotSpotCollectorProcess.attach(HotSpotChildFixture.argv("success", directory), 123, 456uL)
        Executors.newFixedThreadPool(2).use { pool ->
            val first = pool.submit<Any> { owner.finish() }
            val second = pool.submit<Any> { owner.finish() }
            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `late child acquisition is owned and aborted after bounded startup failure`() {
        val argv = HotSpotChildFixture.argv("no-ready", directory)
        val release = CountDownLatch(1)
        val child = AtomicReference<Process?>()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                release.await()
                return RealProcessLauncher.startProcess(args, redirectErrorStream).also(child::set)
            }
        }
        val failure = try {
            org.junit.jupiter.api.Assertions.assertThrows(HotSpotSessionFailure::class.java) {
                HotSpotCollectorProcess.attach(argv, 123, 456uL, launcher, HotSpotCollectorTiming(startupNanos = 50_000_000, stopNanos = 100_000_000))
            }
        } finally {
            release.countDown()
        }
        assertTrue(failure.snapshot.pendingOwnership)
        org.junit.jupiter.api.Assertions
            .assertThrows(HotSpotSessionFailure::class.java) { failure.owner.close() }
        assertFalse(failure.owner.snapshot().pendingOwnership)
        assertFalse(requireNotNull(child.get()).isAlive)
    }

    @Test
    fun `held stderr reader cannot be hidden by process exit or repeated close`() {
        val argv = HotSpotChildFixture.argv("success", directory)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                val child = RealProcessLauncher.startProcess(args, redirectErrorStream)
                val held = object : InputStream() {
                    override fun read(): Int {
                        entered.countDown()
                        release.await()
                        return -1
                    }
                }
                return object : Process() {
                    override fun getOutputStream() = child.outputStream

                    override fun getInputStream() = child.inputStream

                    override fun getErrorStream() = held

                    override fun waitFor() = child.waitFor()

                    override fun exitValue() = child.exitValue()

                    override fun destroy() = child.destroy()

                    override fun destroyForcibly(): Process {
                        child.destroyForcibly()
                        return this
                    }

                    override fun isAlive() = child.isAlive
                }
            }
        }
        val owner = HotSpotCollectorProcess.attach(argv, 123, 456uL, launcher, HotSpotCollectorTiming(stopNanos = 100_000_000))
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            val failure = org.junit.jupiter.api.Assertions
                .assertThrows(HotSpotSessionFailure::class.java) { owner.finish() }
            assertTrue(failure.snapshot.pendingOwnership)
            org.junit.jupiter.api.Assertions
                .assertThrows(HotSpotSessionFailure::class.java) { owner.close() }
            assertTrue(owner.snapshot().pendingOwnership)
        } finally {
            release.countDown()
        }
        org.junit.jupiter.api.Assertions
            .assertThrows(HotSpotSessionFailure::class.java) { owner.close() }
        assertFalse(owner.snapshot().pendingOwnership)
    }

    @Test
    fun `caller interruption preserves cause and interrupt status while aborting startup`() {
        val argv = HotSpotChildFixture.argv("no-ready", directory)
        val result = AtomicReference<Throwable?>()
        val interrupted = AtomicReference(false)
        val caller = Thread.ofPlatform().start {
            try {
                HotSpotCollectorProcess.attach(argv, 123, 456uL)
            } catch (failure: HotSpotSessionFailure) {
                result.set(failure)
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        caller.interrupt()
        caller.join(5000)
        assertFalse(caller.isAlive)
        val failure = assertIs<HotSpotSessionFailure>(result.get())
        assertIs<InterruptedException>(failure.cause)
        assertTrue(interrupted.get())
        assertFalse(failure.owner.snapshot().pendingOwnership)
    }

    @Test
    fun `failed owned pipe close preserves permission cause and remains retryable`() {
        val argv = HotSpotChildFixture.argv("success", directory)
        val denied = AtomicBoolean(true)
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                val child = RealProcessLauncher.startProcess(args, redirectErrorStream)
                val output = object : FilterOutputStream(child.outputStream) {
                    override fun close() {
                        if (denied.get()) throw AccessDeniedException("owned stdin")
                        super.close()
                    }
                }
                return object : Process() {
                    override fun getOutputStream() = output

                    override fun getInputStream() = child.inputStream

                    override fun getErrorStream() = child.errorStream

                    override fun waitFor() = child.waitFor()

                    override fun exitValue() = child.exitValue()

                    override fun destroy() = child.destroy()

                    override fun destroyForcibly(): Process {
                        child.destroyForcibly()
                        return this
                    }

                    override fun isAlive() = child.isAlive
                }
            }
        }
        val owner = HotSpotCollectorProcess.attach(argv, 123, 456uL, launcher, HotSpotCollectorTiming(stopNanos = 200_000_000))
        val failure = org.junit.jupiter.api.Assertions
            .assertThrows(HotSpotSessionFailure::class.java) { owner.finish() }
        assertIs<AccessDeniedException>(failure.cause)
        assertTrue(failure.snapshot.pendingOwnership)
        denied.set(false)
        org.junit.jupiter.api.Assertions
            .assertThrows(HotSpotSessionFailure::class.java) { owner.close() }
        assertFalse(owner.snapshot().pendingOwnership)
    }
}
