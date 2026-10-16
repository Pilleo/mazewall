package io.mazewall.profiler.api
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class ProfilerAckPreloadTest {
    @Test
    fun `preload covers every sealed parser and operand outcome variant`() {
        val roots = listOf(
            io.mazewall.profiler.internal.TraceListenerState::class,
            io.mazewall.profiler.internal.TraceListenerEvent::class,
            io.mazewall.profiler.internal.TraceEventDecodeResult::class,
            TraceEvent::class,
            PathResolution::class,
            PathResolutionFailure.Reason::class,
            ConnectResolution::class,
            OpenFlagsEvidence::class,
            AddressFamily::class,
        )
        for (root in roots) {
            for (type in listOf(root) + root.sealedSubclasses) {
                assertTrue(type.java.name in ProfilerAckPreload.requiredBinaryNames, "ACK path type was omitted from pre-install initialization: ${type.java.name}")
            }
        }
        ProfilerAckPreload.ensureLoaded()
    }

    @Test
    fun `preload initializes result and listener state types`() {
        ProfilerAckPreload.ensureLoaded()
        val cl = ProfilerAckPreload::class.java.classLoader
        for (name in ProfilerAckPreload.requiredBinaryNames) {
            Class.forName(name, false, cl)
        }
        assertTrue(ProfilerAckPreload.requiredBinaryNames.any { it.endsWith("ProfilingResult") })
        assertTrue(ProfilerAckPreload.requiredBinaryNames.any { it.contains("TraceListenerState") })
        assertTrue(ProfilerAckPreload.requiredBinaryNames.any { it.endsWith("TraceEvent\$Open") || it.endsWith("TraceEvent.Open") })
    }
}
