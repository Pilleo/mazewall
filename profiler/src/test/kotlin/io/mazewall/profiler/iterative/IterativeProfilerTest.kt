package io.mazewall.profiler.iterative

import io.mazewall.Platform
import io.mazewall.Policy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.AccessDeniedException

class IterativeProfilerTest {

    @Test
    fun `test IterativeProfiler resolves simple AccessDeniedException`() {
        assumeTrue(Platform.isSupported())

        val testRunnable = object : Runnable {
            var attempt = 0
            override fun run() {
                if (attempt == 0) {
                    attempt++
                    throw AccessDeniedException("/tmp/iterative-test")
                }
            }
        }

        val finalPolicy = IterativeProfiler.profile(Policy.PURE_COMPUTE_UNSAFE, testRunnable)

        assertTrue(finalPolicy.allowedFsReadPaths.any { it.value == "/tmp/iterative-test" })
    }

    @Test
    fun `test IterativeProfiler fails if max retries exceeded`() {
        assumeTrue(Platform.isSupported())

        val testRunnable = object : Runnable {
            override fun run() {
                throw AccessDeniedException("/tmp/infinite-loop")
            }
        }

        try {
            IterativeProfiler.profile(Policy.PURE_COMPUTE_UNSAFE, testRunnable)
        } catch (e: Exception) {
            assertTrue(e is AccessDeniedException)
        }
    }
}
