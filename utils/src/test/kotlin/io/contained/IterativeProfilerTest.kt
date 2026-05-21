package io.contained

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

class IterativeProfilerTest {

    @Test
    fun `test iterative profiling converges on absolute paths`() {
        if (!Platform.isSupported()) return

        val target = File("/etc/hostname")

        // Use a base policy that allows OPEN syscalls but has NO allowed paths (denies all by default in Landlock)
        val basePolicy = Policy.builder()
            .unblock(Syscall.OPEN, Syscall.OPENAT, Syscall.OPENAT2)
            .build()

        val compiledPolicy = IterativeProfiler.profile(basePolicy) {
            target.readText()
        }

        assertTrue(compiledPolicy.allowedFsReadPaths.contains("/etc/hostname"))
    }
}
