package io.mazewall.profiler.engine

import io.mazewall.core.Tid
import io.mazewall.enforcer.api.ContainmentViolationException
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

    @Test
    fun `test resolve returns empty list for socket syscalls like sendmsg and recvmsg`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val socketSyscalls = listOf("SENDMSG", "RECVMSG", "CONNECT", "BIND", "SENDTO", "RECVFROM")
                for (syscall in socketSyscalls) {
                    val event = makeRawEvent(syscall, listOf(0x1000L, 0x2000L, 0x3000L))
                    val resolved = makeResolver(reader).resolve(event)
                    assertTrue(reader.readAddresses.isEmpty(), "No memory read should occur for socket syscall $syscall")
                    assertTrue(resolved.paths.isEmpty(), "No paths should be resolved for socket syscall $syscall")
                }
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
    fun `yama denied path reads abort resolution instead of publishing a pathless event`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val reader = object : ProfilerMemoryReader {
                    context(arena: NativeArena)
                    override fun readStringFromProcess(tid: Tid, remoteAddr: Long, maxLen: Int): String? {
                        throw ContainmentViolationException("Permission denied reading memory from TID ${tid.value}")
                    }

                    context(arena: NativeArena)
                    override fun resolveLink(tid: Tid, link: String): String? = null
                }
                val ledger = SessionEventLedger()
                val resolver = SyscallPathResolver(reader, ledger)
                val event = SyscallEvent<SyscallEventState.Raw>(Tid(1), "OPEN", listOf(100L))
                val resolved = resolver.resolve(event)
                assertTrue(resolved.paths.isEmpty())
                assertTrue(
                    ledger.dump().any { event ->
                        event is SessionEvent.VmReadvResolved && !event.success
                    },
                    "Yama-denied path reads must be recorded as failed inspections: ${ledger.dump()}",
                )
            }
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

    @Test
    fun `test resolve IOCTL returns empty paths and does not read memory`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val event = makeRawEvent("IOCTL", listOf(5L, 0x12345678L, 0x7fff_1000L))
                val resolved = makeResolver(reader).resolve(event)
                assertTrue(reader.readAddresses.isEmpty(), "No memory read should occur for IOCTL")
                assertTrue(resolved.paths.isEmpty(), "No paths should be resolved for IOCTL")
            }
        }
    }

    @Test
    fun `test PathNormalizerHelper normalizePath all branches`() {
        // empty path
        assertEquals("", PathNormalizerHelper.normalizePath(""))

        // roots
        assertEquals("/", PathNormalizerHelper.normalizePath("/"))
        assertEquals(".", PathNormalizerHelper.normalizePath("."))
        assertEquals("/", PathNormalizerHelper.normalizePath("///"))

        // regular
        assertEquals("/a/b/c", PathNormalizerHelper.normalizePath("/a/b/c"))
        assertEquals("a/b/c", PathNormalizerHelper.normalizePath("a/b/c"))
        assertEquals("/a/b/c", PathNormalizerHelper.normalizePath("///a//b///c///"))

        // dots
        assertEquals("/a/b", PathNormalizerHelper.normalizePath("/a/./b"))
        assertEquals("a/b", PathNormalizerHelper.normalizePath("./a/b"))
        assertEquals("a/b", PathNormalizerHelper.normalizePath("a/b/."))

        // double dots absolute
        assertEquals("/a/c", PathNormalizerHelper.normalizePath("/a/b/../c"))
        assertEquals("/a", PathNormalizerHelper.normalizePath("/../a"))
        assertEquals("/", PathNormalizerHelper.normalizePath("/a/../.."))
        assertEquals("/", PathNormalizerHelper.normalizePath("/a/b/../../.."))

        // double dots relative
        assertEquals("a/c", PathNormalizerHelper.normalizePath("a/b/../c"))
        assertEquals("../a", PathNormalizerHelper.normalizePath("../a"))
        assertEquals("..", PathNormalizerHelper.normalizePath("a/../.."))
        assertEquals("../..", PathNormalizerHelper.normalizePath("a/../../.."))
        assertEquals("../../a", PathNormalizerHelper.normalizePath("a/../../../a"))

        // fallback triggers (stack overflow and size limits)
        val deepPath = (1..130).joinToString("/") { "a" }
        assertEquals(java.nio.file.Paths.get(deepPath).normalize().toString(), PathNormalizerHelper.normalizePath(deepPath))

        val longPath = "a".repeat(4100)
        assertEquals(java.nio.file.Paths.get(longPath).normalize().toString(), PathNormalizerHelper.normalizePath(longPath))
    }

    @Test
    fun `test PathNormalizerHelper pathStartsWith all branches`() {
        assertTrue(PathNormalizerHelper.pathStartsWith("/a/b", "/a/b"))
        assertTrue(PathNormalizerHelper.pathStartsWith("/a/b", "/"))
        assertTrue(PathNormalizerHelper.pathStartsWith("/a/b/c", "/a/b"))
        assertTrue(PathNormalizerHelper.pathStartsWith("/", "/"))
        assertTrue(PathNormalizerHelper.pathStartsWith("/a", "/"))

        assertTrue(!PathNormalizerHelper.pathStartsWith("/a/b-other", "/a/b"))
        assertTrue(!PathNormalizerHelper.pathStartsWith("/a/b", "/c"))
        assertTrue(!PathNormalizerHelper.pathStartsWith("a/b", "/a"))
    }

    @Test
    fun `test resolvePaths overload direct call`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val resolver = SyscallPathResolver(stubMemoryReader, SessionEventLedger())
                val pathsList = resolver.resolvePaths(Tid(1), "OPEN", listOf(100L))
                assertEquals(listOf("/etc/passwd"), pathsList)

                val pathsArr = resolver.resolvePaths(Tid(1), "OPEN", longArrayOf(100L))
                assertEquals(listOf("/etc/passwd"), pathsArr)
            }
        }
    }
}
