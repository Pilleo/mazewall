package io.mazewall.profiler
import io.mazewall.BaseIntegrationTest
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.JvmFloorWorkload
import io.mazewall.profiler.strace.StraceProfiler
import org.junit.jupiter.api.Test

/**
 * Executes the JvmFloorWorkload under the StraceProfiler to generate
 * an exhaustive Bill of Behavior (BoB) for the current JVM environment.
 */
class JvmFloorProfilingTest : BaseIntegrationTest() {
    /**
     * A wrapper workload that delegates to the enforcer's JvmFloorWorkload.
     * This is needed because StraceProfiler requires a [TraceableWorkload] class.
     */
    class JvmFloorWorkloadWrapper : TraceableWorkload {
        override fun run() {
            JvmFloorWorkload.run()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `profile JVM floor workload`() {
        println("=== PROFILING JVM FLOOR WORKLOAD ===")

        val bob = StraceProfiler.profile(JvmFloorWorkloadWrapper::class.java)

        println("\n=== GENERATED JVM FLOOR BILL OF BEHAVIOR ===")
        println(bob.toDsl(baseCwd = java.nio.file.Paths.get("").toAbsolutePath()))
        println("============================================")

        // Basic assertions to ensure we captured the essentials
        assert(bob.syscalls.isNotEmpty()) { "Captured syscalls list should not be empty" }
    }
}
