package io.mazewall.profiler.compiler

import io.mazewall.Policy
import io.mazewall.core.Arch
import io.mazewall.core.Syscall
import io.mazewall.profiler.engine.TraceEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BobCompilerTest {
    companion object {
        private const val O_WRONLY = 1L
        private const val O_CREAT = 64L
    }

    @Test
    fun `test compiling various events to policy and DSL`() {
        val events =
            listOf(
                TraceEvent(12345, "CONNECT", longArrayOf(3, 139626353982016, 16, 0, 0, 0), emptyList()),
                TraceEvent(
                    12345,
                    "OPENAT",
                    longArrayOf(0, 139626353983000, 0, 0, 0, 0),
                    listOf("/etc/hostname"),
                ), // O_RDONLY
                TraceEvent(
                    12345,
                    "OPEN",
                    longArrayOf(139626353983000, O_WRONLY, 0, 0, 0, 0),
                    listOf("/tmp/write-test.txt"),
                ), // O_WRONLY
                TraceEvent(12345, "MKDIR", longArrayOf(139626353983000, 0, 0, 0, 0, 0), listOf("/tmp/new-dir")),
            )

        // Compile to Bill of Behavior
        val bob = BobCompiler.compile(events)

        // Transpile to Policy
        val policy = bob.toPolicy(Policy.PURE_COMPUTE_UNSAFE, allowIncomplete = true)

        // Verify unrestricted syscalls
        // PURE_COMPUTE blocks CONNECT, OPEN, OPENAT. They should be unrestricted now.
        assertTrue(policy.isSyscallAllowed(Syscall.CONNECT), "CONNECT should be unrestricted")
        assertTrue(policy.isSyscallAllowed(Syscall.OPEN), "OPEN should be unrestricted")
        assertTrue(policy.isSyscallAllowed(Syscall.OPENAT), "OPENAT should be unrestricted")

        // Verify fs paths
        assertTrue(policy.allowedFsReadPaths.any { it.value == "/etc/hostname" }, "Should contain read path")
        assertTrue(policy.allowedFsWritePaths.any { it.value == "/tmp/write-test.txt" }, "Should contain write path")
        assertTrue(policy.allowedFsWritePaths.any { it.value == "/tmp/new-dir" }, "Should contain write path")

        // Compile to DSL
        val dsl = bob.toDsl("Policy.PURE_COMPUTE_UNSAFE", Policy.PURE_COMPUTE_UNSAFE, allowIncomplete = true)
        println("Generated DSL:\n$dsl")

        val expectedDsl =
            """
val policy = Policy.threadLocalBuilder()
    .base(Policy.PURE_COMPUTE_UNSAFE)
    .unblock(
        Syscall.CONNECT,
        Syscall.MKDIR,
        Syscall.OPEN,
        Syscall.OPENAT
    )
    .allowFsRead("/etc/hostname")
    .allowFsWrite("/tmp/new-dir")
    .allowFsWrite("/tmp/write-test.txt")
    .build()
            """.trimIndent()

        assertEquals(expectedDsl.trim(), dsl.trim())
    }

    @Test
    fun `test empty events returns unmodified base policy`() {
        val bob = BobCompiler.compile(emptyList())
        val policy = bob.toPolicy(Policy.PURE_COMPUTE_UNSAFE, allowIncomplete = true)

        assertFalse(policy.isSyscallAllowed(Syscall.CONNECT))
        assertFalse(policy.isSyscallAllowed(Syscall.MKDIR))
        assertFalse(policy.isSyscallAllowed(Syscall.OPEN))
        assertTrue(policy.allowedFsReadPaths.isEmpty())
        assertTrue(policy.allowedFsWritePaths.isEmpty())

        val dsl = bob.toDsl("Policy.PURE_COMPUTE_UNSAFE", Policy.PURE_COMPUTE_UNSAFE, allowIncomplete = true)
        val expectedDsl =
            """
val policy = Policy.builder()
    .base(Policy.PURE_COMPUTE_UNSAFE)
    .build()
            """.trimIndent()
        assertEquals(expectedDsl.trim(), dsl.trim())
    }

    @Test
    fun `test C-1 bug fix - syscall observed but not restricted by base policy is absent from compiled policy`() {
        // GETPID is generally not restricted by Policy.PURE_COMPUTE_UNSAFE.
        // If we observe GETPID, compiling against PURE_COMPUTE should NOT list it in the unrestricted list of the DSL
        // and should not have any effect.
        val events =
            listOf(
                TraceEvent(12345, "GETPID", longArrayOf(0, 0, 0, 0, 0, 0), emptyList()),
            )

        val bob = BobCompiler.compile(events)

        // Generate DSL - GETPID should not be listed as unrestricted since PURE_COMPUTE does not block it.
        val dsl = bob.toDsl("Policy.PURE_COMPUTE_UNSAFE", Policy.PURE_COMPUTE_UNSAFE, allowIncomplete = true)
        val expectedDsl =
            """
val policy = Policy.builder()
    .base(Policy.PURE_COMPUTE_UNSAFE)
    .build()
            """.trimIndent()

        assertEquals(expectedDsl.trim(), dsl.trim())
    }

    @Test
    fun `test OPENAT2 and AT variants categorization`() {
        val events =
            listOf(
                TraceEvent(
                    12345,
                    "OPENAT2",
                    longArrayOf(0, 0, 0x12345678, 0, 0, 0), // args[2] is a pointer
                    listOf("/tmp/openat2-test.txt"),
                ),
                TraceEvent(
                    12345,
                    "UNLINKAT",
                    longArrayOf(0, 0, 0, 0, 0, 0),
                    listOf("/tmp/deleted-file.txt"),
                ),
                TraceEvent(
                    12345,
                    "MKDIRAT",
                    longArrayOf(0, 0, 0, 0, 0, 0),
                    listOf("/tmp/new-subdir"),
                ),
                TraceEvent(
                    12345,
                    "RENAMEAT2",
                    longArrayOf(0, 0, 0, 0, 0, 0),
                    listOf("/tmp/old-name", "/tmp/new-name"),
                ),
            )

        val bob = BobCompiler.compile(events)

        assertTrue(bob.opens.contains("/tmp/openat2-test.txt"), "OPENAT2 without flags is not a proven write")
        assertTrue(bob.fsWritePaths.contains("/tmp/deleted-file.txt"), "UNLINKAT should be treated as write")
        assertTrue(bob.fsWritePaths.contains("/tmp/new-subdir"), "MKDIRAT should be treated as write")
        assertTrue(bob.fsWritePaths.contains("/tmp/old-name"), "RENAMEAT2 should be treated as write")
        assertTrue(bob.fsWritePaths.contains("/tmp/new-name"), "RENAMEAT2 target should be treated as write")
    }

    @Test
    fun `test compile with empty list edge cases`() {
        val bob = BobCompiler.compile(emptyList())
        assertTrue(bob.opens.isEmpty())
        assertTrue(bob.fsWritePaths.isEmpty())
        assertTrue(bob.syscalls.isEmpty())
        assertTrue(bob.execs.isEmpty())
    }

    @Test
    fun `test unknown and invalid syscall names`() {
        val events = listOf(
            TraceEvent(tidValue = 1, syscallName = "UNKNOWN_SYSCALL_123", args = longArrayOf(), paths = listOf("/path/unknown")),
            TraceEvent(tidValue = 1, syscallName = "", args = longArrayOf(), paths = listOf("/path/empty")),
            TraceEvent(tidValue = 1, syscallName = "NOT_A_SYSCALL", args = longArrayOf(), paths = listOf("/path/invalid")),
        )
        val bob = BobCompiler.compile(events)

        // They should not throw, should not add to syscalls, but should add paths to opens as fallback
        assertTrue(bob.syscalls.isEmpty())
        assertTrue(bob.execs.isEmpty())
        assertTrue(bob.fsWritePaths.isEmpty())
        assertEquals(setOf("/path/unknown", "/path/empty", "/path/invalid"), bob.opens)
    }

    @Test
    fun `test execve and execveat`() {
        val events = listOf(
            TraceEvent(tidValue = 1, syscallName = "EXECVE", args = longArrayOf(), paths = listOf("/bin/sh")),
            TraceEvent(tidValue = 1, syscallName = "EXECVEAT", args = longArrayOf(), paths = listOf("/bin/bash")),
        )
        val bob = BobCompiler.compile(events)

        assertEquals(setOf(Syscall.EXECVE, Syscall.EXECVEAT), bob.syscalls)
        assertEquals(setOf("/bin/sh", "/bin/bash"), bob.execs)
        assertTrue(bob.fsWritePaths.isEmpty())
        assertTrue(bob.opens.isEmpty())
    }

    @ParameterizedTest(name = "mutation syscall {0} maps to fsWritePaths")
    @ValueSource(
        strings = [
            "MKDIR", "MKDIRAT", "RMDIR", "UNLINK", "UNLINKAT",
            "RENAME", "RENAMEAT", "RENAMEAT2", "LINK", "LINKAT",
            "SYMLINK", "SYMLINKAT", "CHMOD", "FCHMODAT", "CHOWN",
            "LCHOWN", "FCHOWNAT", "CREAT", "TRUNCATE", "FTRUNCATE",
            "UTIME", "UTIMES", "UTIMENSAT",
        ],
    )
    fun `test file system mutation syscalls complete`(syscallName: String) {
        val event = TraceEvent(tidValue = 1, syscallName = syscallName, args = longArrayOf(), paths = listOf("/path/$syscallName"))
        val bob = BobCompiler.compile(listOf(event))

        assertEquals(setOf("/path/$syscallName"), bob.fsWritePaths)
        assertTrue(bob.execs.isEmpty())
        assertTrue(bob.opens.isEmpty())

        val expectedSyscall = runCatching { Syscall.valueOf(syscallName) }.getOrNull()
        if (expectedSyscall != null) {
            assertTrue(bob.syscalls.contains(expectedSyscall))
        }
    }

    @ParameterizedTest(name = "{0} with flags {1} (isWrite={2}, isRead={3})")
    @CsvSource(
        // OPEN variants (flags at arg index 1)
        "OPEN, 0, false, true",         // O_RDONLY
        "OPEN, 1, true, false",          // O_WRONLY
        "OPEN, 2, true, false",          // O_RDWR
        "OPEN, 64, true, false",         // O_CREAT
        "OPEN, 512, true, false",        // O_TRUNC
        "OPEN, 16777216, false, false",  // O_PATH (0x01000000)
        // OPENAT variants (flags at arg index 2)
        "OPENAT, 0, false, true",        // O_RDONLY
        "OPENAT, 1, true, false",         // O_WRONLY
        "OPENAT, 2, true, false",         // O_RDWR
        "OPENAT, 64, true, false",        // O_CREAT
        "OPENAT, 512, true, false",       // O_TRUNC
        "OPENAT, 16777216, false, false", // O_PATH (0x01000000)
    )
    fun `test OPEN and OPENAT flags classification`(
        syscallName: String,
        flags: Long,
        expectWrite: Boolean,
        expectRead: Boolean,
    ) {
        val args = if (syscallName == "OPEN") longArrayOf(10L, flags) else longArrayOf(10L, 20L, flags)
        val path = "/path/test-$syscallName-$flags"
        val event = TraceEvent(tidValue = 1, syscallName = syscallName, args = args, paths = listOf(path))
        val bob = BobCompiler.compile(listOf(event))

        if (expectWrite) {
            assertEquals(setOf(path), bob.fsWritePaths)
        } else {
            assertTrue(bob.fsWritePaths.isEmpty(), "fsWritePaths should be empty for flags $flags")
        }

        if (expectRead) {
            assertEquals(setOf(path), bob.opens)
        } else {
            assertTrue(bob.opens.isEmpty(), "opens should be empty for flags $flags")
        }
    }

    @Test
    fun `test overlapping paths`() {
        // A path is opened for reading, then opened for writing
        val events = listOf(
            TraceEvent(tidValue = 1, syscallName = "OPEN", args = longArrayOf(10, 0L), paths = listOf("/shared/path")), // Read-only
            TraceEvent(tidValue = 1, syscallName = "OPEN", args = longArrayOf(10, 1L), paths = listOf("/shared/path")), // Write-only
        )

        val bob = BobCompiler.compile(events)

        // Should appear in both sets
        assertTrue(bob.opens.contains("/shared/path"))
        assertTrue(bob.fsWritePaths.contains("/shared/path"))
    }

    @Test
    fun `test unknown syscall with arguments behaves as default`() {
        val events = listOf(
            TraceEvent(tidValue = 1, syscallName = "UNKNOWN", args = longArrayOf(10, 1L, 64L), paths = listOf("/path/unknown")),
        )

        val bob = BobCompiler.compile(events)

        // Unknown syscall, defaults to 0 flags, so not a write.
        assertTrue(bob.fsWritePaths.isEmpty())
        assertTrue(bob.opens.contains("/path/unknown"))
    }

    @Test
    fun `test OPEN and OPENAT with O_PATH flag does not grant read or write permissions`() {
        val oPath = 0x01000000L
        val events = listOf(
            TraceEvent(
                tidValue = 1,
                syscallName = "OPENAT",
                args = longArrayOf(0, 0, oPath),
                paths = listOf("/secret/path"),
            ),
            TraceEvent(
                tidValue = 1,
                syscallName = "OPEN",
                args = longArrayOf(0, oPath),
                paths = listOf("/secret/open-path"),
            ),
            TraceEvent(
                tidValue = 1,
                syscallName = "OPENAT",
                args = longArrayOf(0, 0, 0L),
                paths = listOf("/readable/path"),
            ),
        )

        val bob = BobCompiler.compile(events)

        assertFalse(bob.opens.contains("/secret/path"), "O_PATH observation must not be added to opens")
        assertFalse(bob.fsWritePaths.contains("/secret/path"), "O_PATH observation must not be added to fsWritePaths")
        assertFalse(bob.opens.contains("/secret/open-path"), "O_PATH observation must not be added to opens")
        assertFalse(bob.fsWritePaths.contains("/secret/open-path"), "O_PATH observation must not be added to fsWritePaths")
        assertTrue(bob.opens.contains("/readable/path"), "O_RDONLY must be added to opens")

        val policy = bob.toPolicy(Policy.PURE_COMPUTE_UNSAFE, allowIncomplete = true)
        assertFalse(policy.allowedFsReadPaths.any { it.value == "/secret/path" })
        assertFalse(policy.allowedFsReadPaths.any { it.value == "/secret/open-path" })
        assertTrue(policy.allowedFsReadPaths.any { it.value == "/readable/path" })
    }

    @Test
    fun `test mutating io_uring operations are classified as fsWritePaths`() {
        val corr = io.mazewall.profiler.ObservationCorrelation(tgid = 1, tid = io.mazewall.core.Tid(1))
        val observations = listOf(
            io.mazewall.profiler.ProfileObservation.IoUring(
                correlation = corr,
                source = io.mazewall.profiler.ObservationSource.EBPF,
                opcode = "IORING_OP_WRITE",
                paths = listOf("/tmp/mutated.txt"),
            ),
            io.mazewall.profiler.ProfileObservation.IoUring(
                correlation = corr,
                source = io.mazewall.profiler.ObservationSource.EBPF,
                opcode = "IORING_OP_UNLINKAT",
                paths = listOf("/tmp/deleted.txt"),
            ),
            io.mazewall.profiler.ProfileObservation.IoUring(
                correlation = corr,
                source = io.mazewall.profiler.ObservationSource.EBPF,
                opcode = "IORING_OP_RENAMEAT",
                paths = listOf("/tmp/renamed.txt"),
            ),
            io.mazewall.profiler.ProfileObservation.IoUring(
                correlation = corr,
                source = io.mazewall.profiler.ObservationSource.EBPF,
                opcode = "IORING_OP_OPENAT",
                paths = listOf("/tmp/opened.txt"),
            ),
        )

        val bob = BobCompiler.compileObservations(observations)
        assertTrue(bob.fsWritePaths.contains("/tmp/mutated.txt"))
        assertTrue(bob.fsWritePaths.contains("/tmp/deleted.txt"))
        assertTrue(bob.fsWritePaths.contains("/tmp/renamed.txt"))
        assertTrue(bob.opens.contains("/tmp/opened.txt"))
        assertFalse(bob.opens.contains("/tmp/mutated.txt"))
    }
}
