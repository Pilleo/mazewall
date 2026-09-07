package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.core.OpenFlags
import io.mazewall.core.Tid
import io.mazewall.ffi.Layouts
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes syscall arguments after the handler has supplied bounded tracee-memory reads. */
internal object SupervisorArgumentExtractor {
    fun extract(
        nr: Int,
        tid: Tid,
        args: LongArray,
        arch: Arch,
        readString: (Tid, Long) -> String?,
        readBytes: (Tid, Long, Int) -> ByteArray?,
    ): SyscallArguments {
        var path: String? = null
        var sockaddr: ByteArray? = null
        var dirfd = TraceeDirFd.CurrentWorkingDirectory
        var openHow: OpenHow? = null
        when (nr) {
            arch.open -> path = readString(tid, args[0])
            arch.openat -> {
                dirfd = TraceeDirFd(args[0].toInt())
                path = readString(tid, args[1])
            }
            arch.openat2 -> {
                dirfd = TraceeDirFd(args[0].toInt())
                path = readString(tid, args[1])
                readBytes(tid, args[2], Layouts.OPEN_HOW_SIZE.toInt())?.takeIf { it.size >= Layouts.OPEN_HOW_SIZE.toInt() }?.let { bytes ->
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
                    openHow =
                        OpenHow(
                            OpenFlags(buffer.getLong(Layouts.OPEN_HOW_FLAGS_OFFSET.toInt()).toInt()),
                            buffer.getLong(Layouts.OPEN_HOW_MODE_OFFSET.toInt()),
                            buffer.getLong(Layouts.OPEN_HOW_RESOLVE_OFFSET.toInt()),
                        )
                }
            }
            arch.connect -> args[2].toInt().takeIf { it in 1..MAX_ADDRESS_LENGTH }?.let { sockaddr = readBytes(tid, args[1], it) }
            arch.accept, arch.accept4 -> dirfd = TraceeDirFd(args[0].toInt())
            arch.execve -> path = readString(tid, args[0])
            arch.execveat -> {
                dirfd = TraceeDirFd(args[0].toInt())
                path = readString(tid, args[1])
            }
        }
        return SyscallArguments(path, sockaddr, dirfd, openHow)
    }

    private const val MAX_ADDRESS_LENGTH = 128
}
