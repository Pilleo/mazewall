package io.mazewall.platform.seccomp

import io.mazewall.MazewallInternal
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.readLong

@MazewallInternal
public data class SeccompNotification(
    val id: Long,
    val pid: Int,
    val arch: Int,
    val nr: Int,
    val args: LongArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SeccompNotification) return false
        return id == other.id &&
            pid == other.pid &&
            arch == other.arch &&
            nr == other.nr &&
            args.contentEquals(other.args)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + pid
        result = 31 * result + arch
        result = 31 * result + nr
        result = 31 * result + args.contentHashCode()
        return result
    }
}

@MazewallInternal
public object SeccompNotifications {
    public const val ARG_COUNT: Int = 6

    public fun read(notif: ManagedSegment): SeccompNotification {
        val args = LongArray(ARG_COUNT) { i ->
            notif.readLong(Layouts.SECCOMP_NOTIF_ARGS_OFFSET + i * 8L)
        }
        return SeccompNotification(
            id = notif.readLong(Layouts.SECCOMP_NOTIF_ID_OFFSET),
            pid = notif.readInt(Layouts.SECCOMP_NOTIF_PID_OFFSET),
            arch = notif.readInt(Layouts.SECCOMP_NOTIF_ARCH_OFFSET),
            nr = notif.readInt(Layouts.SECCOMP_NOTIF_NR_OFFSET),
            args = args,
        )
    }
}
