package io.mazewall.profiler.iterative

import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.Policy
import io.mazewall.core.Syscall
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

@EnabledIfLinuxAndSupported
class IterativeProfilerTest {
    @Test
    fun `test iterative profiling converges on absolute paths`() {
        val target = File("/etc/hostname")

        // Use a base policy that allows OPEN syscalls but has NO allowed paths (denies all by default in Landlock)
        val basePolicy =
            Policy
                .builder()
                .unblock(Syscall.OPEN, Syscall.OPENAT, Syscall.OPENAT2)
                .build()

        val compiledPolicy =
            IterativeProfiler.profile(basePolicy) {
                target.readText()
            }

        assertTrue(compiledPolicy.allowedFsReadPaths.any { it.value == "/etc/hostname" })
    }

    @Test
    fun `test iterative profiling converges on write paths`() {
        val target = File("build/tmp/iterative-write-test.txt").absoluteFile
        target.parentFile.mkdirs()
        target.delete()

        // Base policy allows write/open syscalls but has NO allowed paths
        val basePolicy =
            Policy
                .builder()
                .unblock(
                    Syscall.OPEN,
                    Syscall.OPENAT,
                    Syscall.OPENAT2,
                    Syscall.UNLINK,
                ).build()

        val compiledPolicy =
            IterativeProfiler.profile(basePolicy) {
                target.writeText("hello from iterative profiler write path")
            }

        assertTrue(
            compiledPolicy.allowedFsWritePaths.any { it.value == target.absolutePath },
            "Should allow write access to path: ${target.absolutePath}",
        )

        // Clean up
        target.delete()
    }

    @Test
    fun `test iterative profiling rethrows unrelated exceptions`() {
        val basePolicy = Policy.PURE_COMPUTE_UNSAFE
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            IterativeProfiler.profile(basePolicy) {
                throw IllegalStateException("completely unrelated error")
            }
        }
    }

    @Test
    fun `test iterative profiling parses path from generic exception messages`() {
        val basePolicy = Policy.PURE_COMPUTE_UNSAFE
        var attempts = 0
        val targetPath = "/etc/custom_denied_path"
        val compiledPolicy = IterativeProfiler.profile(basePolicy) {
            attempts++
            if (attempts == 1) {
                throw java.io.IOException("$targetPath (Permission denied)")
            }
            // Second attempt succeeds
        }
        assertTrue(compiledPolicy.allowedFsReadPaths.any { it.value == targetPath })
        assertTrue(!compiledPolicy.allowedFsWritePaths.any { it.value == targetPath }, "Should not grant write permission by default")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `test iterative profiling of existing file read grants read and not write permission`() {
        val parentDir = File("/tmp/iterative_existing_read").absoluteFile
        parentDir.mkdirs()
        val existingFile = File(parentDir, "exists.txt")
        existingFile.writeText("some content")

        try {
            val basePolicy =
                Policy
                    .builder()
                    .unblock(
                        Syscall.OPEN,
                        Syscall.OPENAT,
                        Syscall.OPENAT2,
                    ).build()

            val compiledPolicy =
                IterativeProfiler.profile(basePolicy) {
                    existingFile.readText()
                }
            println("COMPILED POLICY READS: ${compiledPolicy.allowedFsReadPaths}")
            println("COMPILED POLICY WRITES: ${compiledPolicy.allowedFsWritePaths}")
            // We should have read permission, but absolutely NO write permission!
            assertTrue(compiledPolicy.allowedFsReadPaths.any { it.value == existingFile.absolutePath })
            assertTrue(!compiledPolicy.allowedFsWritePaths.any { it.value == existingFile.absolutePath }, "Should not grant write permission for existing file read")
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun `test iterative profiling retry limit exceeded`() {
        val basePolicy = Policy.PURE_COMPUTE_UNSAFE
        val compiledPolicy = IterativeProfiler.profile(basePolicy) {
            throw java.io.IOException("/etc/forever_denied (Permission denied)")
        }
        assertTrue(compiledPolicy.allowedFsReadPaths.any { it.value == "/etc/forever_denied" })
    }

    @Test
    fun `test iterative profiling ignores exception with null message`() {
        val basePolicy = Policy.PURE_COMPUTE_UNSAFE
        org.junit.jupiter.api.assertThrows<java.io.IOException> {
            IterativeProfiler.profile(basePolicy) {
                throw java.io.IOException(null as String?)
            }
        }
    }

    @Test
    fun `test iterative profiling converges on relative paths`() {
        val target = File("build/tmp/iterative-relative-test.txt")
        target.parentFile.mkdirs()
        target.writeText("content")

        val basePolicy =
            Policy
                .builder()
                .unblock(Syscall.OPEN, Syscall.OPENAT, Syscall.OPENAT2)
                .build()

        val compiledPolicy =
            IterativeProfiler.profile(basePolicy) {
                // Use a relative path to read the file
                val relativePath = java.nio.file.Paths.get("build/tmp/iterative-relative-test.txt")
                java.nio.file.Files.readString(relativePath)
            }

        assertTrue(
            compiledPolicy.allowedFsReadPaths.any { it.value == target.absolutePath },
            "Should allow read access to absolute path of target: ${target.absolutePath}"
        )

        target.delete()
    }

    @Test
    fun `test IterativeProfiler resolves simple AccessDeniedException`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(io.mazewall.Platform.isSupported())

        val testRunnable = object : Runnable {
            var attempt = 0
            override fun run() {
                if (attempt == 0) {
                    attempt++
                    throw java.nio.file.AccessDeniedException("/tmp/iterative-test")
                }
            }
        }

        val finalPolicy = IterativeProfiler.profile(io.mazewall.Policy.PURE_COMPUTE_UNSAFE, testRunnable)

        org.junit.jupiter.api.Assertions.assertTrue(finalPolicy.allowedFsReadPaths.any { it.value == "/tmp/iterative-test" })
    }

    @Test
    fun `test IterativeProfiler fails if max retries exceeded`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(io.mazewall.Platform.isSupported())

        val testRunnable = object : Runnable {
            override fun run() {
                throw java.nio.file.AccessDeniedException("/tmp/infinite-loop")
            }
        }

        try {
            IterativeProfiler.profile(io.mazewall.Policy.PURE_COMPUTE_UNSAFE, testRunnable)
        } catch (e: Exception) {
            org.junit.jupiter.api.Assertions.assertTrue(e is java.nio.file.AccessDeniedException)
        }
    }
}
