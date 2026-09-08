package io.mazewall.profiler

import io.mazewall.core.Tid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProfilerRuntimeTest {
    @Test
    fun `runtime scope is visible only to its calling thread`() {
        val runtime = ProfilerRuntime(Profiler.daemonManagerProvider, Profiler.installerProvider) { Tid(73) }

        val scopedTid = Profiler.withRuntime(runtime) { Profiler.currentRuntime().tid() }
        val defaultTidProvider = Profiler.currentRuntime().tid

        assertEquals(Tid(73), scopedTid)
        assertEquals(Profiler.tidProvider, defaultTidProvider)
    }
}
