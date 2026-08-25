package io.mazewall.profiler

import java.util.Locale

/**
 * Parsed `IORING_OP_*` token. Classification must go through [parse] / [FsEffect.ofUring],
 * never `String.contains("OPENAT")`.
 */
public sealed interface UringOp {
    public data object Open : UringOp
    public data object Write : UringOp
    public data object Unlink : UringOp
    public data object Rename : UringOp
    public data object Mkdir : UringOp
    public data object Rmdir : UringOp
    public data object Truncate : UringOp
    public data object Link : UringOp
    public data object Sync : UringOp
    public data object CloseDirect : UringOp
    public data object Network : UringOp
    public data class Unknown(val raw: String) : UringOp

    public fun isPathBearing(): Boolean =
        when (this) {
            Open, Write, Unlink, Rename, Mkdir, Rmdir, Truncate, Link -> true
            Sync, CloseDirect, Network, is Unknown -> false
        }

    public fun isFilesystemMutation(): Boolean =
        when (this) {
            Write, Unlink, Rename, Mkdir, Rmdir, Truncate, Link, Sync, CloseDirect -> true
            Open, Network, is Unknown -> false
        }

    public companion object {
        public fun parse(opcode: String): UringOp {
            val raw = opcode.uppercase(Locale.US)
            val token = raw.removePrefix("IORING_OP_")
            return when {
                token.startsWith("OPEN") -> Open
                token.startsWith("WRITE") -> Write
                token.startsWith("UNLINK") -> Unlink
                token.startsWith("RENAME") -> Rename
                token.startsWith("MKDIR") -> Mkdir
                token.startsWith("RMDIR") -> Rmdir
                token.startsWith("TRUNCATE") -> Truncate
                token == "LINK" || token == "SYMLINK" || token.endsWith("LINKAT") -> Link
                token.contains("FSYNC") || token == "SYNC" || token.endsWith("_SYNC") -> Sync
                token.contains("CLOSE") && token.contains("DIRECT") -> CloseDirect
                token.startsWith("CONNECT") ||
                    token.startsWith("SEND") ||
                    token.startsWith("RECV") ||
                    token.startsWith("ACCEPT") -> Network
                else -> Unknown(raw)
            }
        }
    }
}

/**
 * Landlock consequence of an io_uring op. [UnknownOpenMode] is not a write.
 */
public sealed interface FsEffect {
    public val paths: List<String>

    public data class Read(override val paths: List<String>) : FsEffect
    public data class Write(override val paths: List<String>) : FsEffect
    public data class UnknownOpenMode(override val paths: List<String>) : FsEffect
    public data class Unenforceable(override val paths: List<String>) : FsEffect

    public companion object {
        public fun ofUring(op: UringOp, paths: List<String>): FsEffect =
            when {
                op is UringOp.Open -> UnknownOpenMode(paths)
                op.isFilesystemMutation() -> Write(paths)
                op is UringOp.Network -> Unenforceable(paths)
                else -> Read(paths)
            }
    }
}
