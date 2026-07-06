package io.mazewall.profiler.engine

import io.mazewall.core.Tid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SyscallPathResolver].
 *
 * These tests verify the correct mapping of raw syscall register arguments to resolved
 * filesystem paths, with particular focus on [SYMLINKAT] whose argument layout is a
 * common source of misclassification bugs.
 *
 * Linux syscall argument conventions tested here:
 * - symlinkat(target, newdirfd, linkpath) → args[0]=target ptr, args[1]=newdirfd, args[2]=linkpath ptr
 * - renameat(olddirfd, oldpath, newdirfd, newpath) → args[0..3]
 * - linkat(olddirfd, oldpath, newdirfd, newpath, flags) → args[0..3]
 */
class SyscallPathResolverTest {

    /**
     * A programmable mock that records which (addr, dirfd) pairs were requested during
     * path resolution. This lets tests assert *which arguments* were treated as string
     * pointers vs. directory file-descriptors — the critical distinction for SYMLINKAT.
     */
    private class RecordingMockReader : ProfilerMemoryReader {
        /**
         * Map from remote address → string to return. `null` means "return null" (read failure).
         */
        val addressToString: MutableMap<Long, String?> = mutableMapOf()

        /**
         * Map from proc-link name → resolved path.
         */
        val linkToPath: MutableMap<String, String> = mutableMapOf()

        /**
         * Sequence of (remoteAddr) calls made to readStringFromProcess.
         */
        val readAddresses: MutableList<Long> = mutableListOf()

        override fun readStringFromProcess(tid: Tid, remoteAddr: Long, maxLen: Int): String? {
            readAddresses.add(remoteAddr)
            return addressToString[remoteAddr]
        }

        override fun resolveLink(tid: Tid, link: String): String? = linkToPath[link]
    }

    private fun makeRawEvent(syscallName: String, args: List<Long>): SyscallEvent<SyscallEventState.Raw> {
        // Pad args to 6 elements (seccomp captures 6 registers).
        val padded = args + List(6 - args.size) { 0L }
        return SyscallEvent(tid = Tid(1234), syscallName = syscallName, args = padded)
    }

    private fun makeResolver(reader: RecordingMockReader): SyscallPathResolver =
        SyscallPathResolver(reader, SessionEventLedger())

    // -------------------------------------------------------------------------
    // SYMLINKAT — primary regression guard
    // -------------------------------------------------------------------------

    /**
     * Regression guard: SYMLINKAT MUST read args[0] as the target string pointer and
     * args[2] relative to args[1] (the newdirfd), NOT args[1] as a pointer and args[3].
     *
     * Linux: symlinkat(const char *target, int newdirfd, const char *linkpath)
     *         args[0]=target_ptr, args[1]=newdirfd, args[2]=linkpath_ptr
     *
     * The old buggy grouping (RENAMEAT branch) would call:
     *   tryRead(pid, args[1], args[0])  → interprets newdirfd (e.g. 3) as a char* → EFAULT
     *   tryRead(pid, args[3], args[2])  → reads register garbage as a char*       → EFAULT
     */
    @Test
    fun `SYMLINKAT resolves target from args0 and linkpath from args2 relative to newdirfd at args1`() {
        val reader = RecordingMockReader()

        // args[0] = pointer to target string "/etc/real-file"
        val targetPtr = 0x7fff_0000L
        // args[1] = newdirfd (a real FD, e.g. 5) — NOT a string pointer
        val newdirfd = 5L
        // args[2] = pointer to linkpath "/var/link"
        val linkPtr = 0x7fff_1000L
        // args[3] = (unused / register garbage) — must NEVER be used as a pointer
        val garbage = 0xDEAD_BEEFL

        reader.addressToString[targetPtr] = "/etc/real-file"   // absolute → returned as-is
        reader.addressToString[linkPtr] = "link-name"           // relative → needs dirfd resolution
        reader.linkToPath["fd/5"] = "/var/links"               // newdirfd=5 → /var/links

        val event = makeRawEvent(
            "SYMLINKAT",
            listOf(targetPtr, newdirfd, linkPtr, garbage),
        )
        val resolved = makeResolver(reader).resolve(event)

        // The resolver must have attempted exactly two pointer reads: args[0] and args[2].
        // It must NOT have attempted to read from args[1] (newdirfd=5) or args[3] (garbage).
        assertEquals(
            listOf(targetPtr, linkPtr),
            reader.readAddresses,
            "SYMLINKAT must read args[0] (target) and args[2] (linkpath); reading args[1] or args[3] indicates the old RENAMEAT-branch bug.",
        )

        // Verify resolved paths are correct.
        assertEquals(2, resolved.paths.size, "Expected exactly 2 resolved paths for SYMLINKAT")
        assertTrue("/etc/real-file" in resolved.paths, "Target path must be resolved")
        assertTrue("/var/links/link-name" in resolved.paths, "Linkpath must be resolved relative to newdirfd")
    }

    /**
     * SYMLINKAT with an absolute linkpath: the dirfd (newdirfd) should be ignored
     * since the linkpath itself is absolute.
     */
    @Test
    fun `SYMLINKAT resolves absolute linkpath without consulting newdirfd`() {
        val reader = RecordingMockReader()

        val targetPtr = 0x7fff_0000L
        val newdirfd = 5L
        val linkPtr = 0x7fff_1000L

        reader.addressToString[targetPtr] = "/etc/real-file"
        reader.addressToString[linkPtr] = "/absolute/link"   // already absolute

        val event = makeRawEvent("SYMLINKAT", listOf(targetPtr, newdirfd, linkPtr, 0L))
        val resolved = makeResolver(reader).resolve(event)

        assertEquals(listOf(targetPtr, linkPtr), reader.readAddresses)
        assertTrue("/etc/real-file" in resolved.paths)
        assertTrue("/absolute/link" in resolved.paths)
    }

    /**
     * SYMLINKAT with AT_FDCWD as newdirfd: the linkpath must be resolved against the CWD.
     */
    @Test
    fun `SYMLINKAT resolves relative linkpath against CWD when newdirfd is AT_FDCWD`() {
        val reader = RecordingMockReader()

        val targetPtr = 0x7fff_0000L
        val newdirfd = AT_FDCWD_VAL   // -100L
        val linkPtr = 0x7fff_1000L

        reader.addressToString[targetPtr] = "/some/target"
        reader.addressToString[linkPtr] = "relative-link"
        reader.linkToPath["cwd"] = "/home/user"  // CWD resolution for AT_FDCWD

        val event = makeRawEvent("SYMLINKAT", listOf(targetPtr, newdirfd, linkPtr, 0L))
        val resolved = makeResolver(reader).resolve(event)

        assertTrue("/home/user/relative-link" in resolved.paths,
            "Relative linkpath with AT_FDCWD must resolve against CWD")
    }

    // -------------------------------------------------------------------------
    // Comparison: RENAMEAT / LINKAT must NOT change behaviour
    // -------------------------------------------------------------------------

    /**
     * Ensures the RENAMEAT branch still reads (olddirfd=args[0], oldpath=args[1]) and
     * (newdirfd=args[2], newpath=args[3]) — four-argument layout unchanged.
     *
     * Linux: renameat(olddirfd, oldpath, newdirfd, newpath)
     */
    @Test
    fun `RENAMEAT resolves both old and new paths with their respective dirfds`() {
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

        // RENAMEAT reads oldpath=args[1] and newpath=args[3]
        assertEquals(listOf(oldPathPtr, newPathPtr), reader.readAddresses)
        assertTrue("/var/old/old.txt" in resolved.paths)
        assertTrue("/var/new/new.txt" in resolved.paths)
    }

    /**
     * LINKAT has the same four-argument layout as RENAMEAT.
     *
     * Linux: linkat(olddirfd, oldpath, newdirfd, newpath, flags)
     */
    @Test
    fun `LINKAT resolves both old and new paths with their respective dirfds`() {
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

    // -------------------------------------------------------------------------
    // Sanity checks for other common syscall categories
    // -------------------------------------------------------------------------

    /**
     * Simple single-path syscalls (OPEN, MKDIR, …) read exactly args[0].
     */
    @Test
    fun `OPEN resolves path from args0`() {
        val reader = RecordingMockReader()
        val pathPtr = 0x7fff_ABCDL

        reader.addressToString[pathPtr] = "/etc/passwd"

        val event = makeRawEvent("OPEN", listOf(pathPtr))
        val resolved = makeResolver(reader).resolve(event)

        assertEquals(listOf(pathPtr), reader.readAddresses)
        assertEquals(listOf("/etc/passwd"), resolved.paths)
    }

    /**
     * OPENAT reads path from args[1] relative to dirfd in args[0].
     *
     * Linux: openat(dirfd, pathname, flags, …)
     */
    @Test
    fun `OPENAT resolves path from args1 relative to dirfd in args0`() {
        val reader = RecordingMockReader()
        val dirfd = 6L
        val pathPtr = 0x7fff_1234L

        reader.addressToString[pathPtr] = "config.yaml"
        reader.linkToPath["fd/6"] = "/etc/app"

        val event = makeRawEvent("OPENAT", listOf(dirfd, pathPtr, 0L))
        val resolved = makeResolver(reader).resolve(event)

        // Only args[1] is a string pointer; args[0] is a dirfd
        assertEquals(listOf(pathPtr), reader.readAddresses)
        assertEquals(listOf("/etc/app/config.yaml"), resolved.paths)
    }

    /**
     * SYMLINK (without AT suffix) reads two raw string pointers: args[0] and args[1].
     *
     * Linux: symlink(target, linkpath)  — no dirfd at all.
     */
    @Test
    fun `SYMLINK resolves both target and linkpath from args0 and args1 directly`() {
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

    /**
     * Unknown syscalls must produce an empty paths list and must not attempt any
     * memory reads.
     */
    @Test
    fun `unknown syscall produces empty paths without any memory reads`() {
        val reader = RecordingMockReader()

        val event = makeRawEvent("UNKNOWN_SYSCALL", listOf(0x1000L, 0x2000L))
        val resolved = makeResolver(reader).resolve(event)

        assertTrue(reader.readAddresses.isEmpty(), "No reads should occur for unknown syscalls")
        assertTrue(resolved.paths.isEmpty())
    }

    /**
     * Null address (0L) in args[0] must not cause a memory read — the resolver skips it.
     */
    @Test
    fun `null address in OPEN is skipped and produces empty paths`() {
        val reader = RecordingMockReader()

        val event = makeRawEvent("OPEN", listOf(0L))
        val resolved = makeResolver(reader).resolve(event)

        assertTrue(reader.readAddresses.isEmpty(), "Zero address must not be passed to readStringFromProcess")
        assertTrue(resolved.paths.isEmpty())
    }

    private val stubMemoryReader = object : ProfilerMemoryReader {
        override fun readStringFromProcess(tid: io.mazewall.core.Tid, remoteAddr: Long, maxLen: Int): String? {
            if (remoteAddr == 100L) return "/etc/passwd"
            if (remoteAddr == 101L) return "relative/path"
            if (remoteAddr == 102L) return "/var/log"
            return null
        }

        override fun resolveLink(tid: io.mazewall.core.Tid, path: String): String? {
            if (path == "cwd") return "/home/user"
            if (path == "fd/5") return "/opt/app"
            return null
        }
    }

    private val resolver = SyscallPathResolver(stubMemoryReader, SessionEventLedger())

    @Test
    fun `test resolve single string arg syscall`() {
        val event = SyscallEvent<SyscallEventState.Raw>(io.mazewall.core.Tid(1), "OPEN", listOf(100L))
        val resolved = resolver.resolve(event)

        org.junit.jupiter.api.Assertions.assertEquals(1, resolved.paths.size)
        org.junit.jupiter.api.Assertions.assertEquals("/etc/passwd", resolved.paths[0])
    }

    @Test
    fun `test resolve dirfd syscall with absolute path`() {
        val event = SyscallEvent<SyscallEventState.Raw>(io.mazewall.core.Tid(1), "OPENAT", listOf(5L, 100L))
        val resolved = resolver.resolve(event)

        org.junit.jupiter.api.Assertions.assertEquals(1, resolved.paths.size)
        org.junit.jupiter.api.Assertions.assertEquals("/etc/passwd", resolved.paths[0])
    }

    @Test
    fun `test resolve dirfd syscall with relative path and AT_FDCWD`() {
        val event = SyscallEvent<SyscallEventState.Raw>(io.mazewall.core.Tid(1), "OPENAT", listOf(-100L, 101L))
        val resolved = resolver.resolve(event)

        org.junit.jupiter.api.Assertions.assertEquals(1, resolved.paths.size)
        org.junit.jupiter.api.Assertions.assertEquals("/home/user/relative/path", resolved.paths[0])
    }

    @Test
    fun `test resolve dirfd syscall with relative path and valid dirfd`() {
        val event = SyscallEvent<SyscallEventState.Raw>(io.mazewall.core.Tid(1), "OPENAT", listOf(5L, 101L))
        val resolved = resolver.resolve(event)

        org.junit.jupiter.api.Assertions.assertEquals(1, resolved.paths.size)
        org.junit.jupiter.api.Assertions.assertEquals("/opt/app/relative/path", resolved.paths[0])
    }

    @Test
    fun `test resolve rename syscall`() {
        val event = SyscallEvent<SyscallEventState.Raw>(io.mazewall.core.Tid(1), "RENAME", listOf(100L, 102L))
        val resolved = resolver.resolve(event)

        org.junit.jupiter.api.Assertions.assertEquals(2, resolved.paths.size)
        org.junit.jupiter.api.Assertions.assertEquals("/etc/passwd", resolved.paths[0])
        org.junit.jupiter.api.Assertions.assertEquals("/var/log", resolved.paths[1])
    }

    @Test
    fun `test resolve renameat syscall`() {
        val event = SyscallEvent<SyscallEventState.Raw>(io.mazewall.core.Tid(1), "RENAMEAT", listOf(5L, 101L, -100L, 101L))
        val resolved = resolver.resolve(event)

        org.junit.jupiter.api.Assertions.assertEquals(2, resolved.paths.size)
        org.junit.jupiter.api.Assertions.assertEquals("/opt/app/relative/path", resolved.paths[0])
        org.junit.jupiter.api.Assertions.assertEquals("/home/user/relative/path", resolved.paths[1])
    }
}
