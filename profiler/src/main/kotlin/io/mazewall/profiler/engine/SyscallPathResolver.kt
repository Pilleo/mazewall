package io.mazewall.profiler.engine

import io.mazewall.core.Pid
import io.mazewall.core.Tid

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
                listOfNotNull(tryRead(tid, args[1], args[0])) // dirfd=0, path=1

            "RENAMEAT", "RENAMEAT2", "LINKAT" ->
                listOfNotNull(
                    tryRead(tid, args[1], args[0]), // olddirfd=0, oldpath=1
                    tryRead(tid, args[3], args[2]), // newdirfd=2, newpath=3
                )

            "SYMLINKAT" ->
                listOfNotNull(
                    tryRead(tid, args[0]), // target (oldpath) is relative to CWD
                    tryRead(tid, args[2], args[1]), // linkpath (newpath) is relative to newdirfd
                )

            else -> emptyList()
        }
        return event.resolved(paths)
    }

    private fun resolveCwd(tid: Tid): String? = memoryReader.resolveLink(tid, "cwd")

    private fun resolveFdPath(tid: Tid, fd: Int): String? = memoryReader.resolveLink(tid, "fd/$fd")

    private fun isAtFdcwd(fd: Long): Boolean = fd == AT_FDCWD_VAL || fd == AT_FDCWD_UNSIGNED_VAL || fd.toInt() == AT_FDCWD_INT_VAL

    private fun tryRead(
        tid: Tid,
        addr: Long,
        dirfd: Long = AT_FDCWD_VAL,
    ): String? {
        if (addr == 0L) return null
        val path = memoryReader.readStringFromProcess(tid, addr)
        ledger.record(SessionEvent.VmReadvResolved(System.nanoTime(), tid.value.toLong(), path != null))
        if (path == null) return null
        return if (path.startsWith("/")) path else resolveRelativePath(tid, path, dirfd)
    }

    private fun resolveRelativePath(
        tid: Tid,
        path: String,
        dirfd: Long,
    ): String {
        val dirPath = if (isAtFdcwd(dirfd)) {
            resolveCwd(tid)
        } else if (dirfd >= 0) {
            resolveFdPath(tid, dirfd.toInt())
        } else {
            null
        }

        if (dirPath == null) {
            throw IllegalStateException("Failed to resolve absolute path for relative path '$path' (dirfd=$dirfd)")
        }

        return if (dirPath.endsWith("/")) "$dirPath$path" else "$dirPath/$path"
    }
}
