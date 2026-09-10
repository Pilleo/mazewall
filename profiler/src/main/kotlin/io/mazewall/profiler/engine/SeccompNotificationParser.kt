package io.mazewall.profiler.engine

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.readShort
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeShort

internal typealias SeccompNotification = io.mazewall.platform.seccomp.SeccompNotification

internal data class PollEvents(
    val socketRevents: Short,
    val listenerRevents: Short,
)

internal interface SeccompNotificationParser {
    fun readNotif(notif: ManagedSegment): SeccompNotification

    fun readPollEvents(pollFds: ManagedSegment): PollEvents

    fun writeSocketPoll(
        socketPollFd: ManagedSegment,
        fd: Int,
        events: Short,
    )
}

internal object RealSeccompNotificationParser : SeccompNotificationParser {
    override fun readNotif(notif: ManagedSegment): SeccompNotification {
        return io.mazewall.platform.seccomp.SeccompNotifications
            .read(notif)
    }

    override fun readPollEvents(pollFds: ManagedSegment): PollEvents {
        val socketRevents = pollFds.readShort(POLLFD_REVENT_DATA_OFF)
        val listenerRevents = pollFds.readShort(POLLFD_REVENTS_OFF)
        return PollEvents(socketRevents, listenerRevents)
    }

    override fun writeSocketPoll(
        socketPollFd: ManagedSegment,
        fd: Int,
        events: Short,
    ) {
        socketPollFd.writeInt(POLLFD_FD_OFF, fd)
        socketPollFd.writeShort(POLLFD_EVENTS_OFF, events)
    }
}
