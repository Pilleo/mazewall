package io.mazewall.profiler.engine

import io.mazewall.core.Pid
import io.mazewall.core.Tid
import io.mazewall.ffi.memory.NativeArena

/**
 * Resolves syscall path arguments by reading from the tracee's memory.
 * Transforms [SyscallEvent.Raw] into [SyscallEvent.Resolved].
 */
internal class SyscallPathResolver(
    private val memoryReader: ProfilerMemoryReader,
    private val ledger: SessionEventLedger,
) {
    /**
     * Resolves path arguments for a raw syscall event.
     */
    context(arena: NativeArena)
    fun resolve(event: SyscallEvent<SyscallEventState.Raw>): SyscallEvent<SyscallEventState.Resolved> {
        val argsArr = LongArray(event.args.size) { i -> event.args[i] }
        val paths = resolvePaths(event.tid, event.syscallName, argsArr)
        return event.resolved(paths)
    }

    context(arena: NativeArena)
    fun resolvePaths(
        tid: Tid,
        syscallName: String,
        args: LongArray,
    ): List<String> {
        val paths = when (syscallName) {
            "OPEN", "EXECVE", "MKDIR", "RMDIR", "CHMOD", "CHOWN", "LCHOWN", "UNLINK", "READLINK", "CHROOT", "UTIME", "UTIMES" ->
                listOfNotNull(tryRead(tid, args[0]))

            "FCHMOD", "FCHOWN", "FSTAT" ->
                listOfNotNull(resolveFdPath(tid, args[0].toInt()))

            "SYMLINK", "LINK", "RENAME" ->
                listOfNotNull(tryRead(tid, args[0]), tryRead(tid, args[1]))

            "OPENAT", "EXECVEAT", "OPENAT2", "MKDIRAT", "UNLINKAT", "FCHMODAT", "FCHOWNAT", "UTIMENSAT", "FSTATAT", "READLINKAT" ->
                listOfNotNull(tryRead(tid, args[1], args[0]))

            "RENAMEAT", "RENAMEAT2", "LINKAT" ->
                listOfNotNull(
                    tryRead(tid, args[1], args[0]),
                    tryRead(tid, args[3], args[2]),
                )

            "SYMLINKAT" ->
                listOfNotNull(
                    tryRead(tid, args[0]),
                    tryRead(tid, args[2], args[1]),
                )

            // IOCTL is highly command-dependent and its arguments are not standard string pointers.
            // We treat it as a generic opaque operation without attempting deep pointer dereferencing.
            "IOCTL" -> emptyList()

            else -> emptyList()
        }
        return paths
    }

    context(arena: NativeArena)
    fun resolvePaths(
        tid: Tid,
        syscallName: String,
        args: List<Long>,
    ): List<String> {
        val argsArr = LongArray(args.size) { i -> args[i] }
        return resolvePaths(tid, syscallName, argsArr)
    }

    context(arena: NativeArena)
    private fun resolveCwd(tid: Tid): String? = memoryReader.resolveLink(tid, "cwd")

    context(arena: NativeArena)
    private fun resolveFdPath(tid: Tid, fd: Int): String? = memoryReader.resolveLink(tid, "fd/$fd")

    private fun isAtFdcwd(fd: Long): Boolean = fd == AT_FDCWD_VAL || fd == AT_FDCWD_UNSIGNED_VAL || fd.toInt() == AT_FDCWD_INT_VAL

    context(arena: NativeArena)
    private fun tryRead(
        tid: Tid,
        addr: Long,
        dirfd: Long = AT_FDCWD_VAL,
    ): String? {
        if (addr == 0L) return null
        val path = memoryReader.readStringFromProcess(tid, addr)
        ledger.record(SessionEvent.VmReadvResolved(System.nanoTime(), tid.value.toLong(), path != null))
        if (path == null) return null
        return if (path.startsWith("/")) {
            PathNormalizerHelper.normalizePath(path)
        } else {
            resolveRelativePath(tid, path, dirfd)
        }
    }

    context(arena: NativeArena)
    private fun resolveRelativePath(
        tid: Tid,
        path: String,
        dirfd: Long,
    ): String {
        val dirPathStr = if (isAtFdcwd(dirfd)) {
            resolveCwd(tid)
        } else if (dirfd >= 0) {
            resolveFdPath(tid, dirfd.toInt())
        } else {
            null
        }

        if (dirPathStr == null) {
            throw IllegalStateException("Failed to resolve absolute path for relative path '$path' (dirfd=$dirfd)")
        }

        val combined = if (dirPathStr.endsWith("/")) {
            dirPathStr + path
        } else {
            "$dirPathStr/$path"
        }
        return PathNormalizerHelper.normalizePath(combined)
    }
}

internal object PathNormalizerHelper {
    private val threadLocalCharBuffer = ThreadLocal.withInitial { CharArray(4096) }
    private val threadLocalIntArray = ThreadLocal.withInitial { IntArray(128) }

    fun normalizePath(path: String): String {
        if (path.isEmpty()) return path

        val chars = threadLocalCharBuffer.get()
        val stack = threadLocalIntArray.get()
        var stackSize = 0

        val isAbsolute = path.startsWith('/')
        var i = 0
        val len = path.length

        var outLen = 0
        if (isAbsolute) {
            chars[outLen++] = '/'
        }

        while (i < len) {
            while (i < len && path[i] == '/') {
                i++
            }
            if (i >= len) break

            val start = i
            while (i < len && path[i] != '/') {
                i++
            }
            val compLen = i - start

            if (compLen == 1 && path[start] == '.') {
                continue
            } else if (compLen == 2 && path[start] == '.' && path[start + 1] == '.') {
                val lastStart = if (stackSize > 0) stack[stackSize - 1] else -1
                val lastLen = if (lastStart >= 0) outLen - lastStart else 0
                val isLastDotDot = lastLen == 2 && chars[lastStart] == '.' && chars[lastStart + 1] == '.'

                if (stackSize > 0 && !isLastDotDot) {
                    stackSize--
                    val poppedStart = stack[stackSize]
                    outLen = if (poppedStart > 0 && chars[poppedStart - 1] == '/') {
                        poppedStart - 1
                    } else {
                        poppedStart
                    }
                    if (isAbsolute && outLen == 0) {
                        outLen = 1
                    }
                } else if (!isAbsolute) {
                    if (outLen > 0 && chars[outLen - 1] != '/') {
                        if (outLen + compLen + 1 > chars.size || stackSize >= stack.size) {
                            return java.nio.file.Paths.get(path).normalize().toString()
                        }
                        chars[outLen++] = '/'
                    }
                    if (stackSize >= stack.size) {
                        return java.nio.file.Paths.get(path).normalize().toString()
                    }
                    stack[stackSize++] = outLen
                    if (outLen + compLen > chars.size) {
                        return java.nio.file.Paths.get(path).normalize().toString()
                    }
                    for (k in start until i) {
                        chars[outLen + k - start] = path[k]
                    }
                    outLen += compLen
                }
            } else {
                if (outLen > 0 && chars[outLen - 1] != '/') {
                    if (outLen + compLen + 1 > chars.size || stackSize >= stack.size) {
                        return java.nio.file.Paths.get(path).normalize().toString()
                    }
                    chars[outLen++] = '/'
                }
                if (stackSize >= stack.size) {
                    return java.nio.file.Paths.get(path).normalize().toString()
                }
                stack[stackSize++] = outLen
                if (outLen + compLen > chars.size) {
                    return java.nio.file.Paths.get(path).normalize().toString()
                }
                for (k in start until i) {
                    chars[outLen + k - start] = path[k]
                }
                outLen += compLen
            }
        }

        if (outLen == 0) {
            return if (isAbsolute) "/" else "."
        }

        return String(chars, 0, outLen)
    }

    fun pathStartsWith(path: String, prefix: String): Boolean {
        if (path == prefix) return true
        if (prefix == "/") return path.startsWith("/")
        return path.startsWith(prefix) && path.length > prefix.length && path[prefix.length] == '/'
    }
}
