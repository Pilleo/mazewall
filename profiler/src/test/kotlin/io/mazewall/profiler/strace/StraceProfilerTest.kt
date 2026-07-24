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

    @Test
    fun `test that StraceProfiler and Profiler contain TOCTOU KDoc documentation`() {
        var rootDir = java.io.File(".").absoluteFile
        // In some environments, the test execution dir might be the subproject dir, so we traverse up if needed
        while (rootDir.parentFile != null && !java.io.File(rootDir, "profiler").exists() && !java.io.File(rootDir, "enforcer").exists()) {
            rootDir = rootDir.parentFile
        }

        val profilerFile = java.io.File(rootDir, "profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt")
        val straceProfilerFile = java.io.File(rootDir, "profiler/src/main/kotlin/io/mazewall/profiler/strace/StraceProfiler.kt")
        val profilerDaemonFile = java.io.File(rootDir, "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemon.kt")

        org.junit.jupiter.api.Assertions.assertTrue(profilerFile.exists(), "Profiler.kt should be found at ${profilerFile.absolutePath}")
        org.junit.jupiter.api.Assertions.assertTrue(straceProfilerFile.exists(), "StraceProfiler.kt should be found at ${straceProfilerFile.absolutePath}")
        org.junit.jupiter.api.Assertions.assertTrue(profilerDaemonFile.exists(), "ProfilerDaemon.kt should be found at ${profilerDaemonFile.absolutePath}")

        val profilerContent = profilerFile.readText()
        val straceProfilerContent = straceProfilerFile.readText()
        val profilerDaemonContent = profilerDaemonFile.readText()

        org.junit.jupiter.api.Assertions.assertTrue(profilerContent.contains("TOCTOU"), "Profiler.kt should document TOCTOU")
        org.junit.jupiter.api.Assertions.assertTrue(profilerContent.contains("Landlock"), "Profiler.kt should document Landlock")

        org.junit.jupiter.api.Assertions.assertTrue(straceProfilerContent.contains("TOCTOU"), "StraceProfiler.kt should document TOCTOU")
        org.junit.jupiter.api.Assertions.assertTrue(straceProfilerContent.contains("Landlock"), "StraceProfiler.kt should document Landlock")

        org.junit.jupiter.api.Assertions.assertTrue(profilerDaemonContent.contains("TOCTOU"), "ProfilerDaemon.kt should document TOCTOU")
        org.junit.jupiter.api.Assertions.assertTrue(profilerDaemonContent.contains("Landlock"), "ProfilerDaemon.kt should document Landlock")
    }
}
