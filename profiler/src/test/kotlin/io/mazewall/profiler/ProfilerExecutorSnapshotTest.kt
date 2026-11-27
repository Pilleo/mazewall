package io.mazewall.profiler.api
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.PolicyPresets
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.api.PathResolution
import io.mazewall.profiler.api.PathResolutionFailure
import io.mazewall.profiler.api.SyscallEvent
import io.mazewall.profiler.api.SyscallEventState
import io.mazewall.profiler.api.TraceEvent
import io.mazewall.profiler.internal.DaemonContext
import io.mazewall.profiler.internal.engine.RealProfilerInstaller
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilerExecutorSnapshotTest {
    @Test
    fun `installation failure prevents execution through every admission method`() {
        val admissions: Map<String, (Profiler.ProfilerExecutorWrapper, Runnable, Callable<Int>) -> Unit> = mapOf(
            "execute" to { wrapper, task, _ -> wrapper.execute(task) },
            "submit callable" to { wrapper, _, task -> assertFailsWith<ExecutionException> { wrapper.submit(task).get() } },
            "submit runnable" to { wrapper, task, _ -> assertFailsWith<ExecutionException> { wrapper.submit(task).get() } },
            "submit runnable with result" to { wrapper, task, _ -> assertFailsWith<ExecutionException> { wrapper.submit(task, 7).get() } },
            "invokeAll" to { wrapper, _, task -> assertFailsWith<ExecutionException> { wrapper.invokeAll(listOf(task)).single().get() } },
            "timed invokeAll" to { wrapper, _, task -> assertFailsWith<ExecutionException> { wrapper.invokeAll(listOf(task), 5, TimeUnit.SECONDS).single().get() } },
            "invokeAny" to { wrapper, _, task -> assertFailsWith<ExecutionException> { wrapper.invokeAny(listOf(task)) } },
            "timed invokeAny" to { wrapper, _, task -> assertFailsWith<ExecutionException> { wrapper.invokeAny(listOf(task), 5, TimeUnit.SECONDS) } },
        )
        for ((name, admit) in admissions) {
            val delegate = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable).apply { uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> } }
            }
            val session = session()
            val wrapper = Profiler.ProfilerExecutorWrapper(delegate, session)
            val entered = AtomicInteger()
            try {
                admit(wrapper, Runnable { entered.incrementAndGet() }, Callable { entered.incrementAndGet() })
                wrapper.shutdown()
                assertTrue(wrapper.awaitTermination(5, TimeUnit.SECONDS), name)
                assertEquals(0, entered.get(), name)
                assertFalse(wrapper.snapshot().coverage.complete, name)
            } finally {
                delegate.shutdownNow()
                session.close()
            }
        }
    }

    @Test
    fun `orderly shutdown keeps coverage incomplete while accepted bodies remain live`() {
        val delegate = Executors.newSingleThreadExecutor()
        val session = session()
        val wrapper = Profiler.ProfilerExecutorWrapper(delegate, session)
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            // Direct work also keeps this owned executor live, even before a collector is requested.
            val body = delegate.submit {
                running.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(running.await(5, TimeUnit.SECONDS))
            wrapper.shutdown()
            assertFalse(wrapper.awaitTermination(1, TimeUnit.MILLISECONDS))
            assertFalse(wrapper.snapshot().coverage.complete)
            release.countDown()
            body.get(5, TimeUnit.SECONDS)
            assertTrue(wrapper.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(wrapper.snapshot().coverage.complete)
        } finally {
            release.countDown()
            delegate.shutdownNow()
            session.close()
        }
    }

    @Test
    fun `interrupt resistant work keeps shutdownNow incomplete and early cutoff remains visible`() {
        val delegate = Executors.newSingleThreadExecutor()
        val session = session()
        val wrapper = Profiler.ProfilerExecutorWrapper(delegate, session)
        val running = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val body = delegate.submit {
                running.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS))
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(running.await(5, TimeUnit.SECONDS))
            assertTrue(wrapper.shutdownNow().isEmpty())
            assertTrue(interrupted.await(5, TimeUnit.SECONDS))
            assertTrue(body.cancel(false))
            assertTrue(body.isDone)
            assertFalse(wrapper.awaitTermination(1, TimeUnit.MILLISECONDS))
            assertFalse(wrapper.snapshot().coverage.complete)
            // The service closes sessions directly when asked to stop collection early.
            session.close()
            release.countDown()
            assertTrue(wrapper.awaitTermination(5, TimeUnit.SECONDS))
            val coverage = wrapper.snapshot().coverage
            assertFalse(coverage.complete)
            assertTrue(coverage.warnings.any { "before accepted executor work ended" in it })
        } finally {
            release.countDown()
            delegate.shutdownNow()
            session.close()
        }
    }

    @Test
    fun `failed collector installation is never mistaken for an unused wrapper`() {
        val session = session()
        assertFailsWith<io.mazewall.enforcer.api.UnsupportedKernelFeatureException> { session.installOnCurrentThread() }
        session.close()
        assertFalse(session.result(Unit).coverage.complete)
    }

    @Test
    fun `wrapper snapshot retains incomplete path coverage and freezes history`() {
        val session = session()
        val failure = PathResolutionFailure(0, PathResolutionFailure.Reason.Truncated, "missing path tail")
        session.logs += TraceEvent.fromSyscallEvent(SyscallEvent.fromResolution<SyscallEventState.Resolved>(Tid(1), "RENAME", emptyList(), PathResolution.Failed(listOf(failure))))
        val delegate = Executors.newSingleThreadExecutor()
        try {
            val wrapper = Profiler.ProfilerExecutorWrapper(delegate, session)
            session.close()
            val result = wrapper.snapshot()
            assertFalse(result.coverage.complete)
            assertEquals(listOf(failure), result.coverage.pathFailures)
            wrapper.recentLogs.clear()
            assertEquals(1, result.observations.size)
            assertEquals(listOf(failure), result.coverage.pathFailures)
        } finally {
            delegate.shutdownNow()
            session.close()
        }
    }

    @Test
    fun `unused closed collector has no lost work but active collector is not drained`() {
        val session = session()
        assertFalse(session.result(Unit).coverage.complete)
        session.close()
        assertTrue(session.result(Unit).coverage.complete)
    }

    private fun session(): ProfilerSession {
        val native = MockNativeEngine()
        val runtime = ProfilerRuntime(
            { error("unused collector must not start daemon") },
            RealProfilerInstaller(native, false),
            { Tid(1) },
            native,
            { ProfileEnvironment("fixture", EbpfLoad.Denied("fixture")) },
        )
        return ProfilerSession(runtime, DaemonContext("unused", Path.of("unused"), ProfilerTest.MockProcess(1), Thread()), PolicyPresets.PURE_COMPUTE_UNSAFE, false, false) {}
    }
}
