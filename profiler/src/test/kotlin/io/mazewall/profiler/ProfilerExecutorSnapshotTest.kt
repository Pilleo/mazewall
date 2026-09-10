package io.mazewall.profiler

import io.mazewall.MockNativeEngine
import io.mazewall.PolicyPresets
import io.mazewall.core.Tid
import io.mazewall.profiler.engine.PathResolution
import io.mazewall.profiler.engine.PathResolutionFailure
import io.mazewall.profiler.engine.RealProfilerInstaller
import io.mazewall.profiler.engine.SyscallEvent
import io.mazewall.profiler.engine.SyscallEventState
import io.mazewall.profiler.engine.TraceEvent
import io.mazewall.profiler.internal.DaemonContext
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilerExecutorSnapshotTest {
    @Test
    fun `failed collector installation is never mistaken for an unused wrapper`() {
        val session = session()
        assertFailsWith<io.mazewall.UnsupportedKernelFeatureException> { session.installOnCurrentThread() }
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
