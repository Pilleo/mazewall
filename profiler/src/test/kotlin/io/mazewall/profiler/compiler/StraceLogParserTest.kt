package io.mazewall.profiler.compiler

import io.mazewall.profiler.ObservationCorrelation
import io.mazewall.profiler.ObservationSource
import io.mazewall.profiler.ProfileObservation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class StraceLogParserTest {

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
