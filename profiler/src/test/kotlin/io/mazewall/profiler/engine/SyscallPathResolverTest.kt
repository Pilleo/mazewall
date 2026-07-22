package io.mazewall.profiler.engine

import io.mazewall.core.Tid
import io.mazewall.ffi.memory.NativeArena
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyscallPathResolverTest {

    private val AT_FDCWD_VAL = -100L

    private class RecordingMockReader : ProfilerMemoryReader {
        val readAddresses = mutableListOf<Long>()
        val addressToString = mutableMapOf<Long, String>()
        val linkToPath = mutableMapOf<String, String>()

        context(arena: NativeArena)
        override fun readStringFromProcess(tid: Tid, remoteAddr: Long, maxLen: Int): String? {
            readAddresses.add(remoteAddr)
            return addressToString[remoteAddr]
        }

        context(arena: NativeArena)
        override fun resolveLink(tid: Tid, link: String): String? {
            return linkToPath[link]
        }
    }

    private fun makeResolver(reader: ProfilerMemoryReader) = SyscallPathResolver(reader, SessionEventLedger())

    private fun makeRawEvent(name: String, args: List<Long>) = SyscallEvent<SyscallEventState.Raw>(
        tid = Tid(123),
        syscallName = name,
        args = args
    )

    @Test
    fun `test resolve SYMLINKAT correctly resolves source and target paths`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val oldPathPtr = 0x1000L
                val newPathPtr = 0x2000L
                val newDirFd = 5L

                reader.addressToString[oldPathPtr] = "source.txt"
                reader.addressToString[newPathPtr] = "target.txt"
                reader.linkToPath["cwd"] = "/home/user"
                reader.linkToPath["fd/5"] = "/opt/app"

                // SYMLINKAT(oldpath, newdirfd, newpath)
                val event = makeRawEvent("SYMLINKAT", listOf(oldPathPtr, newDirFd, newPathPtr))
                val resolved = makeResolver(reader).resolve(event)

                assertEquals(listOf("/home/user/source.txt", "/opt/app/target.txt"), resolved.paths)
            }
        }
    }

    /**
     * Regression test for argument layout in resolve() loop.
     * Some syscalls like RENAMEAT2 have dirfd/path pairs in (arg0, arg1) and (arg2, arg3).
     */
    @Test
    fun `test resolve RENAMEAT2 correctly resolves both path pairs`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val oldDirFd = 5L
                val oldPathPtr = 0x1000L
                val newDirFd = 6L
                val newPathPtr = 0x2000L

                reader.addressToString[oldPathPtr] = "old.txt"
                reader.addressToString[newPathPtr] = "new.txt"
                reader.linkToPath["fd/5"] = "/dir1"
                reader.linkToPath["fd/6"] = "/dir2"

                // RENAMEAT2(olddirfd, oldpath, newdirfd, newpath, flags)
                val event = makeRawEvent("RENAMEAT2", listOf(oldDirFd, oldPathPtr, newDirFd, newPathPtr, 0L))
                val resolved = makeResolver(reader).resolve(event)

                assertEquals(listOf("/dir1/old.txt", "/dir2/new.txt"), resolved.paths)
            }
        }
    }

    @Test
    fun `test resolve returns empty list for unknown syscalls`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val event = makeRawEvent("UNKNOWN_SYSCALL", listOf(0x1000L, 0x2000L))
                val resolved = makeResolver(reader).resolve(event)
                assertTrue(reader.readAddresses.isEmpty())
                assertTrue(resolved.paths.isEmpty())
            }
        }
    }

    /**
     * Null address (0L) in args[0] must not cause a memory read — the resolver skips it.
     */
    @Test
    fun `null address in OPEN is skipped and produces empty paths`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val event = makeRawEvent("OPEN", listOf(0L))
                val resolved = makeResolver(reader).resolve(event)
                assertTrue(reader.readAddresses.isEmpty(), "Zero address must not be passed to readStringFromProcess")
                assertTrue(resolved.paths.isEmpty())
            }
        }
    }

    private val stubMemoryReader = object : ProfilerMemoryReader {
        context(arena: NativeArena)
        override fun readStringFromProcess(tid: Tid, remoteAddr: Long, maxLen: Int): String? {
            if (remoteAddr == 100L) return "/etc/passwd"
            if (remoteAddr == 101L) return "relative/path"
            if (remoteAddr == 102L) return "/var/log"
            return null
        }
        context(arena: NativeArena)
        override fun resolveLink(tid: Tid, path: String): String? {
            if (path == "cwd") return "/home/user"
            if (path == "fd/5") return "/opt/app"
            return null
        }
    }

    @Test
    fun `test resolve single string arg syscall`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val resolver = SyscallPathResolver(stubMemoryReader, SessionEventLedger())
                val event = SyscallEvent<SyscallEventState.Raw>(Tid(1), "OPEN", listOf(100L))
                val resolved = resolver.resolve(event)
                assertEquals(1, resolved.paths.size)
                assertEquals("/etc/passwd", resolved.paths[0])
            }
        }
    }

    @Test
    fun `test resolve dirfd syscall with absolute path`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val resolver = SyscallPathResolver(stubMemoryReader, SessionEventLedger())
                val event = SyscallEvent<SyscallEventState.Raw>(Tid(1), "OPENAT", listOf(5L, 100L))
                val resolved = resolver.resolve(event)
                assertEquals(1, resolved.paths.size)
                assertEquals("/etc/passwd", resolved.paths[0])
            }
        }
    }

    @Test
    fun `test resolve dirfd syscall with relative path and AT_FDCWD`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val resolver = SyscallPathResolver(stubMemoryReader, SessionEventLedger())
                val event = SyscallEvent<SyscallEventState.Raw>(Tid(1), "OPENAT", listOf(-100L, 101L))
                val resolved = resolver.resolve(event)
                assertEquals(1, resolved.paths.size)
                assertEquals("/home/user/relative/path", resolved.paths[0])
            }
        }
    }

    @Test
    fun `test resolve dirfd syscall with relative path and valid dirfd`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val resolver = SyscallPathResolver(stubMemoryReader, SessionEventLedger())
                val event = SyscallEvent<SyscallEventState.Raw>(Tid(1), "OPENAT", listOf(5L, 101L))
                val resolved = resolver.resolve(event)
                assertEquals(1, resolved.paths.size)
                assertEquals("/opt/app/relative/path", resolved.paths[0])
            }
        }
    }

    @Test
    fun `test resolve rename syscall`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val resolver = SyscallPathResolver(stubMemoryReader, SessionEventLedger())
                val event = SyscallEvent<SyscallEventState.Raw>(Tid(1), "RENAME", listOf(100L, 102L))
                val resolved = resolver.resolve(event)
                assertEquals(2, resolved.paths.size)
                assertEquals("/etc/passwd", resolved.paths[0])
                assertEquals("/var/log", resolved.paths[1])
            }
        }
    }

    @Test
    fun `test resolve renameat syscall`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val resolver = SyscallPathResolver(stubMemoryReader, SessionEventLedger())
                val event = SyscallEvent<SyscallEventState.Raw>(Tid(1), "RENAMEAT", listOf(5L, 101L, -100L, 101L))
                val resolved = resolver.resolve(event)
                assertEquals(2, resolved.paths.size)
                assertEquals("/opt/app/relative/path", resolved.paths[0])
                assertEquals("/home/user/relative/path", resolved.paths[1])
            }
        }
    }

    @Test
    fun `test resolve absolute path with dots`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val pathPtr = 0x7fff_1000L
                reader.addressToString[pathPtr] = "/home/user/../../etc/passwd"
                val event = makeRawEvent("OPEN", listOf(pathPtr))
                val resolved = makeResolver(reader).resolve(event)
                assertEquals(listOf("/etc/passwd"), resolved.paths)
            }
        }
    }

    @Test
    fun `test resolve relative path with dots`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val dirfd = AT_FDCWD_VAL
                val pathPtr = 0x7fff_1000L
                reader.addressToString[pathPtr] = "./relative/path"
                reader.linkToPath["cwd"] = "/opt/app/."
                val event = makeRawEvent("OPENAT", listOf(dirfd, pathPtr, 0L))
                val resolved = makeResolver(reader).resolve(event)
                assertEquals(listOf("/opt/app/relative/path"), resolved.paths)
            }
        }
    }
}
