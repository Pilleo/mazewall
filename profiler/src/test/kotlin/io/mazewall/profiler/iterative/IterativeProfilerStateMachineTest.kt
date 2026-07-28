package io.mazewall.profiler.iterative

import io.mazewall.Policy
import io.mazewall.Uncompiled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IterativeProfilerStateMachineTest {

    @Test
    fun `test IterativeProfiler state machine with custom taskExecutor`() {
        val originalExecutor = IterativeProfiler.taskExecutor
        try {
            var calls = 0
            val customExecutor = object : IterativeTaskExecutor {
                override fun executeTask(
                    currentPolicy: Policy<*, Uncompiled>,
                    task: Runnable
                ): Throwable? {
                    calls++
                    return when (calls) {
                        1 -> java.nio.file.AccessDeniedException("/tmp/path1")
                        2 -> java.nio.file.AccessDeniedException("/tmp/path1") // This triggers updating with write!
                        else -> null
                    }
                }
            }

            IterativeProfiler.taskExecutor = customExecutor

            val policy = IterativeProfiler.profile(Policy.PURE_COMPUTE_UNSAFE) {
                // Task is run through customExecutor
            }

            assertEquals(3, calls, "Should call executeTask 3 times (1st read denied, 2nd write denied, 3rd success)")
            assertTrue(policy.allowedFsReadPaths.any { it.value == "/tmp/path1" }, "Should allow read to /tmp/path1")
            assertTrue(policy.allowedFsWritePaths.any { it.value == "/tmp/path1" }, "Should allow write to /tmp/path1")
        } finally {
            IterativeProfiler.taskExecutor = originalExecutor
        }
    }

    @Test
    fun `test IterativeProfiler limit exceeded state`() {
        val originalExecutor = IterativeProfiler.taskExecutor
        try {
            var calls = 0
            val customExecutor = object : IterativeTaskExecutor {
                override fun executeTask(
                    currentPolicy: Policy<*, Uncompiled>,
                    task: Runnable
                ): Throwable? {
                    calls++
                    return java.nio.file.AccessDeniedException("/tmp/infinite-$calls")
                }
            }

            IterativeProfiler.taskExecutor = customExecutor

            val policy = IterativeProfiler.profile(Policy.PURE_COMPUTE_UNSAFE) { }

            assertEquals(20, calls, "Should stop at max 20 retries")
            assertTrue(policy.allowedFsReadPaths.any { it.value == "/tmp/infinite-1" })
        } finally {
            IterativeProfiler.taskExecutor = originalExecutor
        }
    }

    @Test
    fun `test IterativeProfiler failed state throws exception`() {
        val originalExecutor = IterativeProfiler.taskExecutor
        try {
            val customExecutor = object : IterativeTaskExecutor {
                override fun executeTask(
                    currentPolicy: Policy<*, Uncompiled>,
                    task: Runnable
                ): Throwable? {
                    return IllegalArgumentException("some error that cannot be parsed as a file path")
                }
            }

            IterativeProfiler.taskExecutor = customExecutor

            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                IterativeProfiler.profile(Policy.PURE_COMPUTE_UNSAFE) { }
            }
        } finally {
            IterativeProfiler.taskExecutor = originalExecutor
        }
    }

    @Test
    fun `test IterativeProfiler converges on IOException path with spaces`() {
        val originalExecutor = IterativeProfiler.taskExecutor
        try {
            var calls = 0
            val targetPath = "/etc/custom denied path with spaces.txt"
            val customExecutor = object : IterativeTaskExecutor {
                override fun executeTask(
                    currentPolicy: Policy<*, Uncompiled>,
                    task: Runnable
                ): Throwable? {
                    calls++
                    return if (calls == 1) {
                        java.io.IOException("$targetPath (Permission denied)")
                    } else {
                        null
                    }
                }
            }

            IterativeProfiler.taskExecutor = customExecutor

            val policy = IterativeProfiler.profile(Policy.PURE_COMPUTE_UNSAFE) { }

            assertEquals(2, calls)
            assertTrue(policy.allowedFsReadPaths.any { it.value == targetPath })
        } finally {
            IterativeProfiler.taskExecutor = originalExecutor
        }
    }

    @Test
    fun `test IterativeProfiler handles null message exception gracefully`() {
        val originalExecutor = IterativeProfiler.taskExecutor
        try {
            val customExecutor = object : IterativeTaskExecutor {
                override fun executeTask(
                    currentPolicy: Policy<*, Uncompiled>,
                    task: Runnable
                ): Throwable? {
                    return java.io.IOException(null as String?)
                }
            }

            IterativeProfiler.taskExecutor = customExecutor

            org.junit.jupiter.api.assertThrows<java.io.IOException> {
                IterativeProfiler.profile(Policy.PURE_COMPUTE_UNSAFE) { }
            }
        } finally {
            IterativeProfiler.taskExecutor = originalExecutor
        }
    }

    @Test
    fun `test IterativeProfiler parses relative path with spaces and resolves it`() {
        val originalExecutor = IterativeProfiler.taskExecutor
        try {
            var calls = 0
            val relativePath = "build/tmp/custom relative path with spaces.txt"
            val expectedAbsolutePath = java.nio.file.Paths.get(relativePath).toAbsolutePath().normalize().toString()
            val customExecutor = object : IterativeTaskExecutor {
                override fun executeTask(
                    currentPolicy: Policy<*, Uncompiled>,
                    task: Runnable
                ): Throwable? {
                    calls++
                    return if (calls == 1) {
                        java.io.IOException("$relativePath (Permission denied)")
                    } else {
                        null
                    }
                }
            }

            IterativeProfiler.taskExecutor = customExecutor

            val policy = IterativeProfiler.profile(Policy.PURE_COMPUTE_UNSAFE) { }

            assertEquals(2, calls)
            assertTrue(policy.allowedFsReadPaths.any { it.value == expectedAbsolutePath })
        } finally {
            IterativeProfiler.taskExecutor = originalExecutor
        }
    }
}
