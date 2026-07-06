package io.mazewall.profiler.engine

import io.mazewall.core.Tid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyscallPathResolverTest {

    private val stubMemoryReader = object : ProfilerMemoryReader {
        override fun readStringFromProcess(tid: Tid, remoteAddr: Long, maxLen: Int): String? {
            if (remoteAddr == 100L) return "/etc/passwd"
            if (remoteAddr == 101L) return "relative/path"
            if (remoteAddr == 102L) return "/var/log"
            return null
        }

        override fun resolveLink(tid: Tid, path: String): String? {
            if (path == "cwd") return "/home/user"
            if (path == "fd/5") return "/opt/app"
            return null
        }
    }

    private val resolver = SyscallPathResolver(stubMemoryReader, SessionEventLedger())

    @Test
    fun `test resolve single string arg syscall`() {
        val event = SyscallEvent<SyscallEventState.Raw>(Tid(1), "OPEN", listOf(100L))
        val resolved = resolver.resolve(event)

        assertEquals(1, resolved.paths.size)
        assertEquals("/etc/passwd", resolved.paths[0])
    }

    @Test
    fun `test resolve dirfd syscall with absolute path`() {
        val event = SyscallEvent<SyscallEventState.Raw>(Tid(1), "OPENAT", listOf(5L, 100L))
        val resolved = resolver.resolve(event)

        assertEquals(1, resolved.paths.size)
        assertEquals("/etc/passwd", resolved.paths[0])
    }

    @Test
    fun `test resolve dirfd syscall with relative path and AT_FDCWD`() {
        val event = SyscallEvent<SyscallEventState.Raw>(Tid(1), "OPENAT", listOf(-100L, 101L))
        val resolved = resolver.resolve(event)

        assertEquals(1, resolved.paths.size)
        assertEquals("/home/user/relative/path", resolved.paths[0])
    }

    @Test
    fun `test resolve dirfd syscall with relative path and valid dirfd`() {
        val event = SyscallEvent<SyscallEventState.Raw>(Tid(1), "OPENAT", listOf(5L, 101L))
        val resolved = resolver.resolve(event)

        assertEquals(1, resolved.paths.size)
        assertEquals("/opt/app/relative/path", resolved.paths[0])
    }

    @Test
    fun `test resolve rename syscall`() {
        val event = SyscallEvent<SyscallEventState.Raw>(Tid(1), "RENAME", listOf(100L, 102L))
        val resolved = resolver.resolve(event)

        assertEquals(2, resolved.paths.size)
        assertEquals("/etc/passwd", resolved.paths[0])
        assertEquals("/var/log", resolved.paths[1])
    }

    @Test
    fun `test resolve renameat syscall`() {
        val event = SyscallEvent<SyscallEventState.Raw>(Tid(1), "RENAMEAT", listOf(5L, 101L, -100L, 101L))
        val resolved = resolver.resolve(event)

        assertEquals(2, resolved.paths.size)
        assertEquals("/opt/app/relative/path", resolved.paths[0])
        assertEquals("/home/user/relative/path", resolved.paths[1])
    }
}
