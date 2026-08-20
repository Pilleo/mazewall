package io.mazewall.profiler
import io.mazewall.BaseIntegrationTest
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.engine.JvmFloorWorkload
import io.mazewall.profiler.internal.DescendantStrace
import org.junit.jupiter.api.Test

/**
 * Lab dump: descendant strace of a fresh JVM (bootstrap floor).
 * Not the operator profiling API.
 */
class JvmFloorProfilingTest : BaseIntegrationTest() {
    /**
     * A wrapper workload that delegates to the enforcer's JvmFloorWorkload.
     * Child JVM entry for the internal strace runner.
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

        val result = DescendantStrace.profile(JvmFloorWorkloadWrapper::class.java)
        val bob = result.behavior
        assert(result.coverage.strategy == ProfileStrategy.STRACE)

        println("\n=== GENERATED JVM FLOOR BILL OF BEHAVIOR ===")
        println(bob.toDsl(baseCwd = java.nio.file.Paths.get("").toAbsolutePath(), allowIncomplete = true))
        println("============================================")

        // Basic assertions to ensure we captured the essentials
        assert(bob.syscalls.isNotEmpty()) { "Captured syscalls list should not be empty" }
    }
}
