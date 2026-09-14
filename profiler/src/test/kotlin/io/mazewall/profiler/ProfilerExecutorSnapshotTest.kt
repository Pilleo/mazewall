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
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilerExecutorSnapshotTest {
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
