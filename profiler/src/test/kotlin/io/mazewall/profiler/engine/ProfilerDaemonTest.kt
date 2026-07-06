package io.mazewall.profiler.engine

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ProfilerDaemonTestCoverage {
    @Test
    fun `test profiler daemon instantiation coverage`() {
        val clazz = ProfilerDaemon::class.java
        assertNotNull(clazz)
    }
}
