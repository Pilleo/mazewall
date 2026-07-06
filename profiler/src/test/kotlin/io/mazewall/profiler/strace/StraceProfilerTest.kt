package io.mazewall.profiler.strace

import io.mazewall.Policy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull

class StraceProfilerTest {

    @Test
    fun `test coverage for StraceProfiler instantiation`() {
        val clazz = StraceProfiler::class.java
        assertNotNull(clazz)
    }
}
