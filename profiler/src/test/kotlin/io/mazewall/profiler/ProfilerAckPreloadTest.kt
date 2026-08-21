package io.mazewall.profiler

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class ProfilerAckPreloadTest {
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
