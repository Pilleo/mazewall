package io.mazewall.profiler.iterative

import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.Policy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.AccessDeniedException
import io.mazewall.enforcer.ContainmentViolationException
import java.util.concurrent.atomic.AtomicInteger

@EnabledIfLinuxAndSupported
class IterativeProfilerProfileTest {

    @Test
    fun `test profile`() {
        val tempFile = File.createTempFile("iterative", ".txt")
        tempFile.deleteOnExit()

        val task = Runnable {
            tempFile.writeText("test")
            tempFile.readText()
        }

        val finalPolicy = IterativeProfiler.profile(Policy.PURE_COMPUTE_UNSAFE, task)
        assertNotNull(finalPolicy)
    }

    @Test
    fun `test profile convergence with mock error`() {
        val counter = AtomicInteger(0)
        val tempFile = File.createTempFile("iterative", ".txt")
        tempFile.deleteOnExit()
        val finalPath = tempFile.absolutePath

        val task = Runnable {
            val count = counter.getAndIncrement()
            if (count == 0) {
                throw ContainmentViolationException("Violated", AccessDeniedException(finalPath))
            }
        }

        val finalPolicy = IterativeProfiler.profile(Policy.PURE_COMPUTE_UNSAFE, task)
        assertNotNull(finalPolicy)
        assertTrue(finalPolicy.allowedFsReadPaths.any { it.value == finalPath })
    }
}
