package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.syscall.Arch
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
        var openHowErrno: Int? = null
        when (nr) {
            arch.open -> path = readString(tid, args[0])
            arch.openat -> {
                dirfd = TraceeDirFd(args[0].toInt())
                path = readString(tid, args[1])
            }
            arch.openat2 -> {
                dirfd = TraceeDirFd(args[0].toInt())
                path = readString(tid, args[1])
                val decoded = decodeOpenHow(if (args.size > 3) args[3] else 0L) { length -> readBytes(tid, args[2], length) }
                openHow = decoded.how
                openHowErrno = decoded.errno
            }
            arch.connect -> args[2].toInt().takeIf { it in 1..MAX_ADDRESS_LENGTH }?.let { sockaddr = readBytes(tid, args[1], it) }
            arch.accept, arch.accept4 -> dirfd = TraceeDirFd(args[0].toInt())
            arch.execve -> path = readString(tid, args[0])
            arch.execveat -> {
                dirfd = TraceeDirFd(args[0].toInt())
                path = readString(tid, args[1])
            }
        }
        return SyscallArguments(path, sockaddr, dirfd, openHow, openHowErrno)
    }

    private data class DecodedOpenHow(
        val how: OpenHow? = null,
        val errno: Int? = null,
    )

    // Linux openat2 rejects usize < 24 and usize > PAGE_SIZE before open, and rejects a nonzero extension tail.
    // VALID_OPEN_FLAGS has no bits above 31, so a high flag bit is EINVAL rather than a narrowed open.
    private fun decodeOpenHow(
        size: Long,
        readBytes: (Int) -> ByteArray?,
    ): DecodedOpenHow = openHowSizeErrno(size)?.let { DecodedOpenHow(errno = it) } ?: decodeCompleteOpenHow(readBytes(size.toInt()))

    private fun openHowSizeErrno(size: Long): Int? =
        when {
            size < 0 || size > PAGE_SIZE -> NativeConstants.E2BIG
            size < Layouts.OPEN_HOW_SIZE -> NativeConstants.EINVAL
            else -> null
        }

    private fun decodeCompleteOpenHow(bytes: ByteArray?): DecodedOpenHow {
        val body = bytes?.takeIf { it.size >= Layouts.OPEN_HOW_SIZE }
        val tailRejected = body?.drop(Layouts.OPEN_HOW_SIZE.toInt())?.any { it != 0.toByte() } == true
        val buffer = if (body != null && !tailRejected) ByteBuffer.wrap(body).order(ByteOrder.nativeOrder()) else null
        val flags = buffer?.getLong(Layouts.OPEN_HOW_FLAGS_OFFSET.toInt()) ?: 0L
        val how =
            buffer?.let {
                OpenHow(
                    OpenFlags(flags.toInt()),
                    it.getLong(Layouts.OPEN_HOW_MODE_OFFSET.toInt()),
                    it.getLong(Layouts.OPEN_HOW_RESOLVE_OFFSET.toInt()),
                )
            }
        return when {
            tailRejected -> DecodedOpenHow(errno = NativeConstants.E2BIG)
            flags ushr FLAG_HIGH_SHIFT != 0L -> DecodedOpenHow(errno = NativeConstants.EINVAL)
            else -> DecodedOpenHow(how = how)
        }
    }

    private const val MAX_ADDRESS_LENGTH = 128
    private const val PAGE_SIZE = 4096L
    private const val FLAG_HIGH_SHIFT = 32
}
