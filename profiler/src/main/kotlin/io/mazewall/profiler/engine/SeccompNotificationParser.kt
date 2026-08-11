package io.mazewall.profiler.engine

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeShort
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.readShort

internal data class SeccompNotification(
    val id: Long,
    val pid: Int,
    val nr: Int,
    val args: LongArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SeccompNotification
        if (id != other.id) return false
        if (pid != other.pid) return false
        if (nr != other.nr) return false
        if (!args.contentEquals(other.args)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + pid
        result = 31 * result + nr
        result = 31 * result + args.contentHashCode()
        return result
    }
}

internal data class PollEvents(
    val socketRevents: Short,
    val listenerRevents: Short
)

internal interface SeccompNotificationParser {
    fun readNotif(notif: ManagedSegment): SeccompNotification
    fun readPollEvents(pollFds: ManagedSegment): PollEvents
    fun writeSocketPoll(socketPollFd: ManagedSegment, fd: Int, events: Short)
}

internal object RealSeccompNotificationParser : SeccompNotificationParser {
    override fun readNotif(notif: ManagedSegment): SeccompNotification {
        val id = notif.readLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ID_OFFSET)
        val pidVal = notif.readInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_PID_OFFSET)
        val nr = notif.readInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_NR_OFFSET)
        val args = LongArray(MAX_SYSCALL_ARGS) { i ->
            notif.readLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ARGS_OFFSET + i * 8L)
        }
        return SeccompNotification(id, pidVal, nr, args)
    }

    override fun readPollEvents(pollFds: ManagedSegment): PollEvents {
        val socketRevents = pollFds.readShort(POLLFD_REVENT_DATA_OFF)
        val listenerRevents = pollFds.readShort(POLLFD_REVENTS_OFF)
        return PollEvents(socketRevents, listenerRevents)
    }

    override fun writeSocketPoll(socketPollFd: ManagedSegment, fd: Int, events: Short) {
        socketPollFd.writeInt(POLLFD_FD_OFF, fd)
        socketPollFd.writeShort(POLLFD_EVENTS_OFF, events)
    }
}
