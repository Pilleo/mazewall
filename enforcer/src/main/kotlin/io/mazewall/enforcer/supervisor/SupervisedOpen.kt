package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.core.OpenFlags

/** A directory-fd number owned by the tracee, never by the supervisor process. */
@JvmInline
internal value class TraceeDirFd(
    val value: Int,
) {
    companion object {
        val CurrentWorkingDirectory = TraceeDirFd(-100)
    }
}

/**
 * Typed open/openat/openat2 so [args][2] cannot be used as flags for openat2.
 * [OpenAt2] carries [OpenHow]; do not emulate it with [open]/[openat].
 */
internal sealed interface SupervisedOpen {
    val path: String
    val mode: Int

    data class Open(
        override val path: String,
        val flags: OpenFlags,
        override val mode: Int = 0,
    ) : SupervisedOpen

    data class OpenAt(
        val dirfd: TraceeDirFd,
        override val path: String,
        val flags: OpenFlags,
        override val mode: Int = 0,
    ) : SupervisedOpen

    data class OpenAt2(
        val dirfd: TraceeDirFd,
        override val path: String,
        val how: OpenHow,
    ) : SupervisedOpen {
        override val mode: Int get() = how.mode.toInt()
    }

    companion object {
        fun parse(
            nr: Int,
            args: LongArray,
            path: String,
            arch: Arch,
            how: OpenHow? = null,
        ): SupervisedOpen? =
            when (nr) {
                arch.open -> {
                    if (args.size < 2) {
                        null
                    } else {
                        val flags = OpenFlags(args[1].toInt())
                        val mode = if (args.size > 2) args[2].toInt() else 0
                        Open(path, flags, mode)
                    }
                }
                arch.openat -> {
                    if (args.size < 3) {
                        null
                    } else {
                        val flags = OpenFlags(args[2].toInt())
                        val mode = if (args.size > 3) args[3].toInt() else 0
                        OpenAt(TraceeDirFd(args[0].toInt()), path, flags, mode)
                    }
                }
                arch.openat2 -> {
                    if (args.isEmpty() || how == null) {
                        null
                    } else {
                        OpenAt2(TraceeDirFd(args[0].toInt()), path, how)
                    }
                }
                else -> null
            }
    }
}

/** Linux `struct open_how` (flags, mode, resolve). */
internal data class OpenHow(
    val flags: OpenFlags,
    val mode: Long,
    val resolve: Long,
)
