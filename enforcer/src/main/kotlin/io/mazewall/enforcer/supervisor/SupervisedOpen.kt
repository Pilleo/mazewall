package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.core.OpenFlags

/**
 * Typed open/openat/openat2 so [args][2] cannot be used as flags for openat2.
 * [OpenAt2] carries [OpenHow]; do not emulate it with [open]/[openat].
 */
internal sealed interface SupervisedOpen {
    val path: String

    data class Open(override val path: String, val flags: OpenFlags) : SupervisedOpen
    data class OpenAt(
        val dirfd: Int,
        override val path: String,
        val flags: OpenFlags,
    ) : SupervisedOpen
    data class OpenAt2(
        val dirfd: Int,
        override val path: String,
        val how: OpenHow,
    ) : SupervisedOpen

    companion object {
        fun parse(
            nr: Int,
            args: LongArray,
            path: String,
            arch: Arch,
        ): SupervisedOpen? =
            when (nr) {
                arch.open -> Open(path, OpenFlags(args[1].toInt()))
                arch.openat -> OpenAt(args[0].toInt(), path, OpenFlags(args[2].toInt()))
                arch.openat2 -> null
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
