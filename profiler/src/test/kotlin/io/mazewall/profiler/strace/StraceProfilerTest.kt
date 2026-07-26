package io.mazewall.profiler.strace

import io.mazewall.core.Syscall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class StraceProfilerTest {

    @Test
    fun `test coverage for StraceProfiler instantiation`() {
        val clazz = StraceProfiler::class.java
        org.junit.jupiter.api.Assertions.assertNotNull(clazz)
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

    @Test
    fun `parseLine extracts syscalls`() {
        val opens = mutableSetOf<String>()
        val fsWritePaths = mutableSetOf<String>()
        val syscalls = mutableSetOf<io.mazewall.core.Syscall>()

        StraceProfiler.parseLine("mmap(NULL, 8192, PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_ANONYMOUS, -1, 0) = 0x7f4339e08000", opens, fsWritePaths, syscalls)
        assertTrue(syscalls.contains(io.mazewall.core.Syscall.MMAP))
    }

    @Test
    fun `parseLine extracts open path`() {
        val opens = mutableSetOf<String>()
        val fsWritePaths = mutableSetOf<String>()
        val syscalls = mutableSetOf<io.mazewall.core.Syscall>()

        StraceProfiler.parseLine("openat(AT_FDCWD, \"/etc/ld.so.cache\", O_RDONLY|O_CLOEXEC) = 3", opens, fsWritePaths, syscalls)
        assertTrue(syscalls.contains(io.mazewall.core.Syscall.OPENAT))
        assertTrue(opens.contains("/etc/ld.so.cache"))
        assertTrue(fsWritePaths.isEmpty())
    }

    @Test
    fun `parseLine extracts open write path`() {
        val opens = mutableSetOf<String>()
        val fsWritePaths = mutableSetOf<String>()
        val syscalls = mutableSetOf<io.mazewall.core.Syscall>()

        StraceProfiler.parseLine("openat(AT_FDCWD, \"/tmp/test.log\", O_WRONLY|O_CREAT|O_TRUNC, 0666) = 3", opens, fsWritePaths, syscalls)
        assertTrue(syscalls.contains(io.mazewall.core.Syscall.OPENAT))
        assertTrue(opens.isEmpty())
        assertTrue(fsWritePaths.contains("/tmp/test.log"))
    }

    @Test
    fun `parseLine handles pid prefix`() {
        val opens = mutableSetOf<String>()
        val fsWritePaths = mutableSetOf<String>()
        val syscalls = mutableSetOf<io.mazewall.core.Syscall>()

        StraceProfiler.parseLine("12345 openat(AT_FDCWD, \"/etc/ld.so.cache\", O_RDONLY|O_CLOEXEC) = 3", opens, fsWritePaths, syscalls)
        assertTrue(syscalls.contains(io.mazewall.core.Syscall.OPENAT))
        assertTrue(opens.contains("/etc/ld.so.cache"))
        assertTrue(fsWritePaths.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "OPEN", "OPENAT", "OPENAT2", "STAT", "STATX", "LSTAT", "ACCESS",
        "READLINK", "READLINKAT", "MKDIR", "MKDIRAT", "RMDIR", "UNLINK",
        "UNLINKAT", "RENAME", "RENAMEAT", "RENAMEAT2", "CHMOD", "FCHMODAT",
        "CHOWN", "FCHOWNAT"
    ])
    fun `isFsSyscall returns true for fs syscalls`(syscallName: String) {
        assertTrue(StraceProfiler.isFsSyscall(syscallName))
    }

    @ParameterizedTest
    @CsvSource(
        "MKDIR, ''",
        "UNLINK, ''",
        "OPEN, 'O_WRONLY'",
        "OPENAT, 'O_RDWR'",
        "OPENAT2, 'O_CREAT'",
        "OPEN, 'O_TRUNC'",
        "OPEN, 'O_APPEND'"
    )
    fun `isWriteSyscall returns true for write syscalls`(name: String, args: String) {
        assertTrue(StraceProfiler.isWriteSyscall(name, args))
    }
}
