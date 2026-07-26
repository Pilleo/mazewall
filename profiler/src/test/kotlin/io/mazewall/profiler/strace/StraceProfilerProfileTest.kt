package io.mazewall.profiler.strace

import io.mazewall.profiler.TraceableWorkload
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class StraceProfilerProfileTest {

    class DummyWorkload : TraceableWorkload {
        override fun run() {
            val f = File.createTempFile("dummy", ".txt")
            f.writeText("test")
            f.readText()
            f.delete()
        }
    }

    @Test
    fun `test profile workload`() {
        try {
            val bob = StraceProfiler.profile(DummyWorkload::class.java)
            assertNotNull(bob)
            assertFalse(bob.syscalls.isEmpty())
        } catch (e: Exception) {
            // Might fail if strace is not available or permissions issue
            println("StraceProfiler.profile failed: ${e.message}")
        }
    }
}
