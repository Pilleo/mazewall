package io.mazewall.profiler.engine

import io.mazewall.core.Pid
import io.mazewall.core.Tid
import java.lang.foreign.Arena

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
    context(arena: Arena)
    fun resolve(event: SyscallEvent<SyscallEventState.Raw>): SyscallEvent<SyscallEventState.Resolved> {
        val tid = event.tid
        val args = event.args

        val paths = when (event.syscallName) {
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

            else -> emptyList()
        }
        return event.resolved(paths)
    }

    context(arena: Arena)
    private fun resolveCwd(tid: Tid): String? = memoryReader.resolveLink(tid, "cwd")

    context(arena: Arena)
    private fun resolveFdPath(tid: Tid, fd: Int): String? = memoryReader.resolveLink(tid, "fd/$fd")

    private fun isAtFdcwd(fd: Long): Boolean = fd == AT_FDCWD_VAL || fd == AT_FDCWD_UNSIGNED_VAL || fd.toInt() == AT_FDCWD_INT_VAL

    context(arena: Arena)
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
            java.nio.file.Paths.get(path).normalize().toString()
        } else {
            resolveRelativePath(tid, path, dirfd)
        }
    }

    context(arena: Arena)
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

        val dirPath = java.nio.file.Paths.get(dirPathStr)
        return dirPath.resolve(path).normalize().toString()
    }
}
