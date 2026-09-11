package io.mazewall.profiler.compiler

import io.mazewall.profiler.ObservationCorrelation
import io.mazewall.profiler.ObservationSource
import io.mazewall.profiler.OpenFlagsEvidence
import io.mazewall.profiler.ProfileObservation
import io.mazewall.profiler.engine.PathResolution
import io.mazewall.profiler.engine.PathResolutionFailure
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StraceLogParserTest {
    @Test
    fun `quoted commas escapes and flag-looking path text cannot change the flags operand`() {
        val observation = StraceLogParser.parseLine(
            "123 openat(AT_FDCWD, \"/tmp/a, b\\\"O_WRONLY\\\\tail\", O_RDONLY|O_CLOEXEC) = 4",
        ) as ProfileObservation.Syscall

        assertEquals(listOf("/tmp/a, b\"O_WRONLY\\tail"), observation.paths)
        assertEquals(OpenFlagsEvidence.Complete(0x80000), observation.openFlagsEvidence)
        assertEquals(0x80000L, observation.openFlags)
    }

    @Test
    fun `openat parses numeric flags without inspecting pathname or result`() {
        val observation = StraceLogParser.parseLine(
            "123 openat(AT_FDCWD, \"/tmp/O_TRUNC 0x41\", 0x80041) = 4",
        ) as ProfileObservation.Syscall

        assertEquals(OpenFlagsEvidence.Complete(0x80041), observation.openFlagsEvidence)
    }

    @Test
    fun `openat2 parses only flags field and reports unknown symbolic flags`() {
        val observation = StraceLogParser.parseLine(
            "123 openat2(AT_FDCWD, \"/tmp/O_CREAT\", {flags=O_RDONLY|O_FUTURE, mode=O_TRUNC, resolve=0}, 24) = 4",
        ) as ProfileObservation.Syscall

        assertEquals(
            OpenFlagsEvidence.Incomplete(0, setOf("O_FUTURE")),
            observation.openFlagsEvidence,
        )
        assertEquals(null, observation.openFlags)
    }

    @Test
    fun `openat2 missing open how structure or flags retains explicit incomplete evidence`() {
        val lines = listOf(
            "123 openat2(AT_FDCWD, \"/tmp/missing-how\") = -1 EFAULT",
            "123 openat2(AT_FDCWD, \"/tmp/null-how\", NULL, 24) = -1 EFAULT",
            "123 openat2(AT_FDCWD, \"/tmp/missing-flags\", {mode=0, resolve=0}, 24) = 4",
            "123 openat2(AT_FDCWD, \"/tmp/empty-flags\", {flags=, mode=0}, 24) = 4",
        )

        for (line in lines) {
            val observation = StraceLogParser.parseLine(line) as ProfileObservation.Syscall
            assertEquals(
                OpenFlagsEvidence.Incomplete(0, setOf("open_how.flags")),
                observation.openFlagsEvidence,
                line,
            )
            assertEquals(null, observation.openFlags, line)
        }
    }

    @Test
    fun `incomplete open flags are rejected instead of compiled as numeric zero`() {
        val observation = StraceLogParser.parseLine(
            "123 open(\"/tmp/write-target\", O_WRONLY|O_FUTURE) = 4",
        ) as ProfileObservation.Syscall

        assertFailsWith<IllegalArgumentException> {
            BobCompiler.compileObservations(listOf(observation))
        }
    }

    @Test
    fun `octal bytes use filesystem encoding and C control escapes`() {
        val observation = StraceLogParser.parseLine(
            """123 open("/tmp/caf\303\251\b", O_RDONLY) = 4""",
        ) as ProfileObservation.Syscall

        assertEquals(listOf("/tmp/café\b"), observation.paths)
    }

    @Test
    fun `truncated quoted pathname is incomplete instead of an accepted prefix`() {
        val observation = StraceLogParser.parseLine(
            """123 open("/prefix"..., O_RDONLY) = 4""",
        ) as ProfileObservation.Syscall

        assertEquals(emptyList(), observation.paths)
        val resolution = assertIs<PathResolution.Failed>(observation.pathResolution)
        assertEquals(PathResolutionFailure.Reason.Truncated, resolution.failures.single().reason)
    }

    @Test
    fun `undecodable pathname bytes are incomplete`() {
        val observation = StraceLogParser.parseLine(
            """123 open("/tmp/\377", O_RDONLY) = 4""",
        ) as ProfileObservation.Syscall

        assertEquals(emptyList(), observation.paths)
        val resolution = assertIs<PathResolution.Failed>(observation.pathResolution)
        assertEquals(PathResolutionFailure.Reason.Truncated, resolution.failures.single().reason)
        assertTrue(
            resolution.failures
            .single()
            .detail
            .contains("undecodable"),
        )
    }

    @Test
    fun `flag names inside pathnames do not suppress observed reads`() {
        val observation = StraceLogParser.parseLine("123 open(\"/data/O_PATH\", O_RDONLY) = 4") as ProfileObservation.Syscall
        assertEquals(0L, observation.openFlags)
        assertEquals(setOf("/data/O_PATH"), BobCompiler.compileObservations(listOf(observation)).opens)
    }

    @Test
    fun `O_PATH observations do not grant content access`() {
        for (line in listOf(
            "123 open(\"/secret\", O_PATH|O_CLOEXEC) = 4",
            "123 openat(AT_FDCWD, \"/secret\", O_PATH) = 4",
            "123 openat2(AT_FDCWD, \"/secret\", {flags=O_PATH, resolve=0}, 24) = 4",
        )) {
            val observation = StraceLogParser.parseLine(line) as ProfileObservation.Syscall
            assertEquals(0x00200000L, requireNotNull(observation.openFlags) and 0x00200000L)
            val bob = BobCompiler.compileObservations(listOf(observation))
            assertEquals(emptySet(), bob.opens)
            assertEquals(emptySet(), bob.fsWritePaths)
        }
    }

    @Test
    fun `execve only extracts first path`() {
        val line = "12345 execve(\"/usr/bin/bash\", [\"bash\", \"-c\", \"echo hello\"], 0x7ffd42a3b4d0 /* 84 vars */) = 0"
        val result = StraceLogParser.parseLine(line)

        assert(result is ProfileObservation.Syscall)
        val syscall = result as ProfileObservation.Syscall
        assertEquals("EXECVE", syscall.name)
        assertEquals(listOf("/usr/bin/bash"), syscall.paths)
    }

    @Test
    fun `readlink buffer is ignored`() {
        val line = "12345 readlink(\"/proc/self/exe\", \"buffer\", 1024) = 15"
        val result = StraceLogParser.parseLine(line)

        assert(result is ProfileObservation.Syscall)
        val syscall = result as ProfileObservation.Syscall
        assertEquals("READLINK", syscall.name)
        assertEquals(listOf("/proc/self/exe"), syscall.paths)
    }

    @Test
    fun `symlinkat extracts both operands`() {
        val line = "12345 symlinkat(\"/source\", AT_FDCWD, \"/target\") = 0"
        val result = StraceLogParser.parseLine(line)

        assert(result is ProfileObservation.Syscall)
        val syscall = result as ProfileObservation.Syscall
        assertEquals("SYMLINKAT", syscall.name)
        assertEquals(listOf("/source", "/target"), syscall.paths)
    }

    @Test
    fun `linkat extracts both operands`() {
        val line = "12345 linkat(AT_FDCWD, \"/source\", AT_FDCWD, \"/target\", 0) = 0"
        val result = StraceLogParser.parseLine(line)

        assert(result is ProfileObservation.Syscall)
        val syscall = result as ProfileObservation.Syscall
        assertEquals("LINKAT", syscall.name)
        assertEquals(listOf("/source", "/target"), syscall.paths)
    }

    @Test
    fun `renameat extracts both operands`() {
        val line = "12345 renameat(AT_FDCWD, \"/old\", AT_FDCWD, \"/new\") = 0"
        val result = StraceLogParser.parseLine(line)

        assert(result is ProfileObservation.Syscall)
        val syscall = result as ProfileObservation.Syscall
        assertEquals("RENAMEAT", syscall.name)
        assertEquals(listOf("/old", "/new"), syscall.paths)
    }

    @Test
    fun `renameat2 extracts both operands`() {
        val line = "12345 renameat2(AT_FDCWD, \"/old\", AT_FDCWD, \"/new\", 0) = 0"
        val result = StraceLogParser.parseLine(line)

        assert(result is ProfileObservation.Syscall)
        val syscall = result as ProfileObservation.Syscall
        assertEquals("RENAMEAT2", syscall.name)
        assertEquals(listOf("/old", "/new"), syscall.paths)
    }

    @Test
    fun `link extracts both operands`() {
        val line = "12345 link(\"/source\", \"/target\") = 0"
        val result = StraceLogParser.parseLine(line)

        assert(result is ProfileObservation.Syscall)
        val syscall = result as ProfileObservation.Syscall
        assertEquals("LINK", syscall.name)
        assertEquals(listOf("/source", "/target"), syscall.paths)
    }

    @Test
    fun `symlink extracts both operands`() {
        val line = "12345 symlink(\"/source\", \"/target\") = 0"
        val result = StraceLogParser.parseLine(line)

        assert(result is ProfileObservation.Syscall)
        val syscall = result as ProfileObservation.Syscall
        assertEquals("SYMLINK", syscall.name)
        assertEquals(listOf("/source", "/target"), syscall.paths)
    }

    @Test
    fun `rename extracts both operands`() {
        val line = "12345 rename(\"/old\", \"/new\") = 0"
        val result = StraceLogParser.parseLine(line)

        assert(result is ProfileObservation.Syscall)
        val syscall = result as ProfileObservation.Syscall
        assertEquals("RENAME", syscall.name)
        assertEquals(listOf("/old", "/new"), syscall.paths)
    }

    @Test
    fun `open extracts first path`() {
        val line = "12345 open(\"/etc/passwd\", O_RDONLY) = 3"
        val result = StraceLogParser.parseLine(line)

        assert(result is ProfileObservation.Syscall)
        val syscall = result as ProfileObservation.Syscall
        assertEquals("OPEN", syscall.name)
        assertEquals(listOf("/etc/passwd"), syscall.paths)
    }

    @Test
    fun `openat extracts first path`() {
        val line = "12345 openat(AT_FDCWD, \"/etc/passwd\", O_RDONLY) = 3"
        val result = StraceLogParser.parseLine(line)

        assert(result is ProfileObservation.Syscall)
        val syscall = result as ProfileObservation.Syscall
        assertEquals("OPENAT", syscall.name)
        assertEquals(listOf("/etc/passwd"), syscall.paths)
    }

    @Test
    fun `rename with single quoted string extracts first path`() {
        val line = "12345 rename(\"/old\", 0x7ffd42a3b4d0) = 0"
        val result = StraceLogParser.parseLine(line)

        assert(result is ProfileObservation.Syscall)
        val syscall = result as ProfileObservation.Syscall
        assertEquals("RENAME", syscall.name)
        assertEquals(listOf("/old"), syscall.paths)
    }

    @Test
    fun `linkat with single quoted string extracts first path`() {
        val line = "12345 linkat(AT_FDCWD, \"/source\", AT_FDCWD, 0x7ffd42a3b4d0) = 0"
        val result = StraceLogParser.parseLine(line)

        assert(result is ProfileObservation.Syscall)
        val syscall = result as ProfileObservation.Syscall
        assertEquals("LINKAT", syscall.name)
        assertEquals(listOf("/source"), syscall.paths)
    }

    @Test
    fun `parseWithStats counts unfinished lines as dropped records and ignores non-syscall markers`() {
        val log = """
            +++ exited with 0 +++
            --- SIGCHLD {si_signo=SIGCHLD} ---
            12345 openat(AT_FDCWD, "/tmp/a", O_RDONLY) = 3
            12345 openat(AT_FDCWD, "/tmp/b", O_RDONLY <unfinished ...>
            12345 <... openat resumed> ) = 4
            12345 invalid_syntax_without_parens
        """.trimIndent()

        val result = StraceLogParser.parseWithStats(log)
        assertEquals(1, result.observations.size)
        assertEquals(listOf("/tmp/a"), result.observations[0].paths)
        assertEquals(2, result.droppedRecords)
    }
}
