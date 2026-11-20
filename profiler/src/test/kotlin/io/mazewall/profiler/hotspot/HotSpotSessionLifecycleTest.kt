package io.mazewall.profiler.hotspot

import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.profiler.internal.hotspot.HotSpotCollectionOutcome
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorProcess
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorSnapshot
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorTiming
import io.mazewall.profiler.internal.hotspot.HotSpotSessionFailure
import io.mazewall.profiler.internal.hotspot.HotSpotSessionState
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
import kotlin.test.assertNotNull
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

    @Test
    fun `held stdout EOF stalls finish while pending ownership remains true and promotes on release`() {
        val argv = HotSpotChildFixture.argv("success", directory)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val childRef = AtomicReference<Process?>()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                val child = RealProcessLauncher.startProcess(args, redirectErrorStream)
                childRef.set(child)
                return HeldStdoutProcess(child, entered, release)
            }
        }
        val owner = HotSpotCollectorProcess.attach(
            argv,
            123,
            456uL,
            launcher,
            HotSpotCollectorTiming(),
        )
        val pool = Executors.newSingleThreadExecutor()
        var primaryError: Throwable? = null
        try {
            val finishFuture = pool.submit<HotSpotCollectorSnapshot> { owner.finish() }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Actual stdout EOF gate must be entered")

            // Finish has not completed
            assertFalse(finishFuture.isDone)

            // State is not Completed, pendingOwnership is true
            val heldSnapshot = owner.snapshot()
            assertTrue(heldSnapshot.pendingOwnership)
            assertFalse(heldSnapshot.state is HotSpotSessionState.Completed)

            // The summary/payload was consumed where observable, but no final collection promotion occurred
            val partialEvidence = heldSnapshot.evidence
            assertNotNull(partialEvidence, "Accumulator must expose partial evidence after consuming payload")
            assertFalse(partialEvidence.finalized, "Partial evidence cannot be finalized before EOF wire receipt")
            assertFalse(partialEvidence.collectionLossComplete)

            // Release actual EOF
            release.countDown()

            // Observe bounded completion
            val completedSnapshot = finishFuture.get(5, TimeUnit.SECONDS)
            assertFalse(completedSnapshot.pendingOwnership)
            assertIs<HotSpotSessionState.Completed>(completedSnapshot.state)

            val finalEvidence = checkNotNull(completedSnapshot.evidence)
            assertTrue(finalEvidence.finalized)
            // Definitions legitimately remains UNKNOWN; assert actual lifecycle facts, not just global false
            assertIs<HotSpotCollectionOutcome.Unknown>(finalEvidence.collectionAccounting)
            assertFalse(finalEvidence.collectionLossComplete)
        } catch (t: Throwable) {
            primaryError = t
        } finally {
            cleanupSession(
                release = release,
                owner = owner,
                childRef = childRef,
                pool = pool,
                primaryError = primaryError,
            )
        }
    }

    @Test
    fun `held stdout EOF through deadline preserves failure and pending ownership past child exit`() {
        val argv = HotSpotChildFixture.argv("success", directory)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val childRef = AtomicReference<Process?>()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                val child = RealProcessLauncher.startProcess(args, redirectErrorStream)
                childRef.set(child)
                return HeldStdoutProcess(child, entered, release)
            }
        }
        val owner = HotSpotCollectorProcess.attach(
            argv,
            123,
            456uL,
            launcher,
            HotSpotCollectorTiming(stopNanos = 100_000_000L),
        )
        var primaryError: Throwable? = null
        try {
            val failure = org.junit.jupiter.api.Assertions.assertThrows(HotSpotSessionFailure::class.java) {
                owner.finish()
            }
            assertTrue(entered.await(0, TimeUnit.SECONDS), "Actual stdout EOF gate must have been entered")
            assertTrue(failure.snapshot.pendingOwnership)
            assertIs<HotSpotSessionState.Failed>(failure.snapshot.state)

            // Child process has exited, but process exit alone must not retire held reader
            val child = checkNotNull(childRef.get())
            assertTrue(child.waitFor(2, TimeUnit.SECONDS), "Underlying child process must have exited")
            assertTrue(owner.snapshot().pendingOwnership, "Ownership must remain pending while stdout reader is held")

            // Repeated close while held preserves pending ownership
            val closeFailure = org.junit.jupiter.api.Assertions.assertThrows(HotSpotSessionFailure::class.java) {
                owner.close()
            }
            assertTrue(closeFailure.snapshot.pendingOwnership)
            assertTrue(owner.snapshot().pendingOwnership)
        } catch (t: Throwable) {
            primaryError = t
        } finally {
            cleanupSession(
                release = release,
                owner = owner,
                childRef = childRef,
                primaryError = primaryError,
                extraChecks = {
                    assertIs<HotSpotSessionState.Failed>(owner.snapshot().state)
                },
            )
        }
    }

    @Test
    fun `caller interruption after held stdout EOF gate preserves cause and interrupt status while holding ownership`() {
        val argv = HotSpotChildFixture.argv("success", directory)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val childRef = AtomicReference<Process?>()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                val child = RealProcessLauncher.startProcess(args, redirectErrorStream)
                childRef.set(child)
                return HeldStdoutProcess(child, entered, release)
            }
        }
        val owner = HotSpotCollectorProcess.attach(
            argv,
            123,
            456uL,
            launcher,
            HotSpotCollectorTiming(),
        )
        val result = AtomicReference<Throwable?>()
        val interrupted = AtomicBoolean(false)
        val caller = Thread.ofPlatform().start {
            try {
                owner.finish()
            } catch (failure: Throwable) {
                result.set(failure)
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        var primaryError: Throwable? = null
        try {
            // Await actual stdout EOF gate BEFORE interrupting caller
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Actual stdout EOF gate must be entered before interruption")
            assertFalse(caller.isInterrupted)

            // Interrupt caller only after barrier
            caller.interrupt()
            caller.join(5000)
            assertFalse(caller.isAlive)

            val failure = assertIs<HotSpotSessionFailure>(result.get())
            assertIs<InterruptedException>(failure.cause)
            assertTrue(interrupted.get(), "Thread interrupt status must be retained")

            // Ownership remains pending until release; no Completed promotion
            assertTrue(owner.snapshot().pendingOwnership)
            assertFalse(owner.snapshot().state is HotSpotSessionState.Completed)
        } catch (t: Throwable) {
            primaryError = t
        } finally {
            cleanupSession(
                release = release,
                owner = owner,
                childRef = childRef,
                caller = caller,
                primaryError = primaryError,
                extraChecks = {
                    assertFalse(owner.snapshot().state is HotSpotSessionState.Completed)
                },
            )
        }
    }

    private fun cleanupSession(
        release: CountDownLatch,
        owner: HotSpotCollectorProcess,
        childRef: AtomicReference<Process?>,
        caller: Thread? = null,
        pool: java.util.concurrent.ExecutorService? = null,
        primaryError: Throwable? = null,
        extraChecks: (() -> Unit)? = null,
    ) {
        val cleanupErrors = mutableListOf<Throwable>()

        fun safeRun(step: () -> Unit) {
            try {
                step()
            } catch (t: Throwable) {
                cleanupErrors.add(t)
            }
        }

        // 1. Unconditionally release gate
        safeRun { release.countDown() }

        // 2. Boundedly join caller/executor with checked termination result
        if (caller != null) {
            safeRun {
                caller.join(5000)
                assertFalse(caller.isAlive, "Caller thread must terminate boundedly")
            }
        }
        if (pool != null) {
            safeRun {
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Executor pool must terminate boundedly")
            }
        }

        // 3. Drive owner.close/retirement, tolerating expected sticky HotSpotSessionFailure, asserting pendingOwnership false
        safeRun {
            try {
                owner.close()
            } catch (expected: HotSpotSessionFailure) {
                // Expected sticky failure on failed/interrupted sessions
            }
            assertFalse(owner.snapshot().pendingOwnership, "Ownership must be retired after release")
            extraChecks?.invoke()
        }

        // 4. Bounded child reap
        safeRun {
            val child = childRef.get()
            if (child != null) {
                if (child.isAlive) {
                    child.destroyForcibly()
                }
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Child process must terminate boundedly")
                assertFalse(child.isAlive, "Child process must not remain alive after cleanup")
            }
        }

        // 5. Preserve primary assertion failure; attach cleanup failures as suppressed
        if (primaryError != null) {
            for (err in cleanupErrors) {
                primaryError.addSuppressed(err)
            }
            throw primaryError
        } else if (cleanupErrors.isNotEmpty()) {
            val first = cleanupErrors.first()
            for (err in cleanupErrors.drop(1)) {
                first.addSuppressed(err)
            }
            throw first
        }
    }

    private class HeldStdoutProcess(
        private val child: Process,
        entered: CountDownLatch,
        release: CountDownLatch,
    ) : Process() {
        private val heldStdout = object : InputStream() {
            private val enteredLatch = AtomicBoolean(false)

            private fun awaitRelease() {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw java.io.IOException("Held stdout EOF release gate timeout expired after 5s")
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("Held stdout EOF wait interrupted")
                }
            }

            override fun read(): Int {
                val b = child.inputStream.read()
                if (b == -1) {
                    if (enteredLatch.compareAndSet(false, true)) {
                        entered.countDown()
                    }
                    awaitRelease()
                    return -1
                }
                return b
            }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                val count = child.inputStream.read(b, off, len)
                if (count == -1) {
                    if (enteredLatch.compareAndSet(false, true)) {
                        entered.countDown()
                    }
                    awaitRelease()
                    return -1
                }
                return count
            }

            override fun read(b: ByteArray): Int = read(b, 0, b.size)

            override fun close() {
                child.inputStream.close()
            }
        }

        override fun getOutputStream() = child.outputStream

        override fun getInputStream() = heldStdout

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
