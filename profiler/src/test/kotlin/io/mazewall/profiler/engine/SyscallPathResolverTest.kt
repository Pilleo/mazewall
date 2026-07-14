package io.mazewall.profiler.engine

import io.mazewall.core.Tid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena

/**
 * Unit tests for [SyscallPathResolver].
 */
class SyscallPathResolverTest {

    private class RecordingMockReader : ProfilerMemoryReader {
        val addressToString: MutableMap<Long, String?> = mutableMapOf()
        val linkToPath: MutableMap<String, String> = mutableMapOf()
        val readAddresses: MutableList<Long> = mutableListOf()

        context(arena: Arena)
        override fun readStringFromProcess(tid: Tid, remoteAddr: Long, maxLen: Int): String? {
            readAddresses.add(remoteAddr)
            return addressToString[remoteAddr]
        }

        context(arena: Arena)
        override fun resolveLink(tid: Tid, link: String): String? = linkToPath[link]
    }

    private fun makeRawEvent(syscallName: String, args: List<Long>): SyscallEvent<SyscallEventState.Raw> {
        val padded = args + List(6 - args.size) { 0L }
        return SyscallEvent(tid = Tid(1234), syscallName = syscallName, args = padded)
    }

    private fun makeResolver(reader: RecordingMockReader): SyscallPathResolver =
        SyscallPathResolver(reader, SessionEventLedger())

    @Test
    fun `SYMLINKAT resolves target from args0 and linkpath from args2 relative to newdirfd at args1`() {
        Arena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val targetPtr = 0x7fff_0000L
                val newdirfd = 5L
                val linkPtr = 0x7fff_1000L
                val garbage = 0xDEAD_BEEFL

                reader.addressToString[targetPtr] = "/etc/real-file"
                reader.addressToString[linkPtr] = "link-name"
                reader.linkToPath["fd/5"] = "/var/links"

                val event = makeRawEvent("SYMLINKAT", listOf(targetPtr, newdirfd, linkPtr, garbage))
                val resolved = makeResolver(reader).resolve(event)

                assertEquals(listOf(targetPtr, linkPtr), reader.readAddresses)
                assertEquals(2, resolved.paths.size)
                assertTrue("/etc/real-file" in resolved.paths)
                assertTrue("/var/links/link-name" in resolved.paths)
            }
        }
    }

    @Test
    fun `SYMLINKAT resolves absolute linkpath without consulting newdirfd`() {
        Arena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val targetPtr = 0x7fff_0000L
                val newdirfd = 5L
                val linkPtr = 0x7fff_1000L

                reader.addressToString[targetPtr] = "/etc/real-file"
                reader.addressToString[linkPtr] = "/absolute/link"

                val event = makeRawEvent("SYMLINKAT", listOf(targetPtr, newdirfd, linkPtr, 0L))
                val resolved = makeResolver(reader).resolve(event)

                assertEquals(listOf(targetPtr, linkPtr), reader.readAddresses)
                assertTrue("/etc/real-file" in resolved.paths)
                assertTrue("/absolute/link" in resolved.paths)
            }
        }
    }

    @Test
    fun `SYMLINKAT resolves relative linkpath against CWD when newdirfd is AT_FDCWD`() {
        Arena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val targetPtr = 0x7fff_0000L
                val newdirfd = AT_FDCWD_VAL
                val linkPtr = 0x7fff_1000L

                reader.addressToString[targetPtr] = "/some/target"
                reader.addressToString[linkPtr] = "relative-link"
                reader.linkToPath["cwd"] = "/home/user"

                val event = makeRawEvent("SYMLINKAT", listOf(targetPtr, newdirfd, linkPtr, 0L))
                val resolved = makeResolver(reader).resolve(event)

                assertTrue("/home/user/relative-link" in resolved.paths)
            }
        }
    }

    @Test
    fun `RENAMEAT resolves both old and new paths with their respective dirfds`() {
        Arena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val olddirfd = 3L
                val oldPathPtr = 0x7fff_0100L
                val newdirfd = 7L
                val newPathPtr = 0x7fff_0200L

                reader.addressToString[oldPathPtr] = "old.txt"
                reader.addressToString[newPathPtr] = "new.txt"
                reader.linkToPath["fd/3"] = "/var/old"
                reader.linkToPath["fd/7"] = "/var/new"

                val event = makeRawEvent("RENAMEAT", listOf(olddirfd, oldPathPtr, newdirfd, newPathPtr))
                val resolved = makeResolver(reader).resolve(event)

                assertEquals(listOf(oldPathPtr, newPathPtr), reader.readAddresses)
                assertTrue("/var/old/old.txt" in resolved.paths)
                assertTrue("/var/new/new.txt" in resolved.paths)
            }
        }
    }

    @Test
    fun `LINKAT resolves both old and new paths with their respective dirfds`() {
        Arena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val olddirfd = AT_FDCWD_VAL
                val oldPathPtr = 0x7fff_0010L
                val newdirfd = 4L
                val newPathPtr = 0x7fff_0020L

                reader.addressToString[oldPathPtr] = "source.txt"
                reader.addressToString[newPathPtr] = "hardlink.txt"
                reader.linkToPath["cwd"] = "/home/src"
                reader.linkToPath["fd/4"] = "/home/dst"

                val event = makeRawEvent("LINKAT", listOf(olddirfd, oldPathPtr, newdirfd, newPathPtr, 0L))
                val resolved = makeResolver(reader).resolve(event)

                assertEquals(listOf(oldPathPtr, newPathPtr), reader.readAddresses)
                assertTrue("/home/src/source.txt" in resolved.paths)
                assertTrue("/home/dst/hardlink.txt" in resolved.paths)
            }
        }
    }

    @Test
    fun `OPEN resolves path from args0`() {
        Arena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val pathPtr = 0x7fff_ABCDL
                reader.addressToString[pathPtr] = "/etc/passwd"
                val event = makeRawEvent("OPEN", listOf(pathPtr))
                val resolved = makeResolver(reader).resolve(event)
                assertEquals(listOf(pathPtr), reader.readAddresses)
                assertEquals(listOf("/etc/passwd"), resolved.paths)
            }
        }
    }

    @Test
    fun `OPENAT resolves path from args1 relative to dirfd in args0`() {
        Arena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val dirfd = 6L
                val pathPtr = 0x7fff_1234L
                reader.addressToString[pathPtr] = "config.yaml"
                reader.linkToPath["fd/6"] = "/etc/app"
                val event = makeRawEvent("OPENAT", listOf(dirfd, pathPtr, 0L))
                val resolved = makeResolver(reader).resolve(event)
                assertEquals(listOf(pathPtr), reader.readAddresses)
                assertEquals(listOf("/etc/app/config.yaml"), resolved.paths)
            }
        }
    }

    @Test
    fun `SYMLINK resolves both target and linkpath from args0 and args1 directly`() {
        Arena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val targetPtr = 0x7fff_AAL
                val linkPtr = 0x7fff_BBL
                reader.addressToString[targetPtr] = "/real/target"
                reader.addressToString[linkPtr] = "/created/link"
                val event = makeRawEvent("SYMLINK", listOf(targetPtr, linkPtr))
                val resolved = makeResolver(reader).resolve(event)
                assertEquals(listOf(targetPtr, linkPtr), reader.readAddresses)
                assertTrue("/real/target" in resolved.paths)
                assertTrue("/created/link" in resolved.paths)
            }
        }
    }

    @Test
    fun `unknown syscall produces empty paths without any memory reads`() {
        Arena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val event = makeRawEvent("UNKNOWN_SYSCALL", listOf(0x1000L, 0x2000L))
                val resolved = makeResolver(reader).resolve(event)
                assertTrue(reader.readAddresses.isEmpty())
                assertTrue(resolved.paths.isEmpty())
            }
        }
    }

    @Test
    fun `null address in OPEN is skipped and produces empty paths`() {
        Arena.ofConfined().use { arena ->
            with(arena) {
                val reader = RecordingMockReader()
                val event = makeRawEvent("OPEN", listOf(0L))
                val resolved = makeResolver(reader).resolve(event)
                assertTrue(reader.readAddresses.isEmpty())
                assertTrue(resolved.paths.isEmpty())
            }
        }
    }

    private val stubMemoryReader = object : ProfilerMemoryReader {
        context(arena: Arena)
        override fun readStringFromProcess(tid: Tid, remoteAddr: Long, maxLen: Int): String? {
            if (remoteAddr == 100L) return "/etc/passwd"
            if (remoteAddr == 101L) return "relative/path"
            if (remoteAddr == 102L) return "/var/log"
            return null
        }
        context(arena: Arena)
        override fun resolveLink(tid: Tid, path: String): String? {
            if (path == "cwd") return "/home/user"
            if (path == "fd/5") return "/opt/app"
            return null
        }
    }

    @Test
    fun `test resolve single string arg syscall`() {
        Arena.ofConfined().use { arena ->
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
        Arena.ofConfined().use { arena ->
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
        Arena.ofConfined().use { arena ->
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
        Arena.ofConfined().use { arena ->
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
        Arena.ofConfined().use { arena ->
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
        Arena.ofConfined().use { arena ->
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
        Arena.ofConfined().use { arena ->
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
        Arena.ofConfined().use { arena ->
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
