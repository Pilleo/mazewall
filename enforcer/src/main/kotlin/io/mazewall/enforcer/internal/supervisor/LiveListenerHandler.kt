package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.seccomp.SeccompNotifications
import io.mazewall.platform.seccomp.UserNotifReplyResult
import io.mazewall.platform.seccomp.daemon.ControlSocketExchange
import io.mazewall.platform.seccomp.daemon.NotifResult
import io.mazewall.platform.seccomp.daemon.NotificationProgress
import io.mazewall.platform.seccomp.daemon.NotificationWork
import io.mazewall.platform.seccomp.daemon.SeccompNotifHandler

/** Until Live authorization is installed, every notification is denied. */
internal class LiveListenerHandler(
    private val listener: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    private val replies: SupervisorReplyInterpreter,
    private val native: NativeEngine? = null,
    private val admission: ConfinedHelperControl.LiveAdmission? = null,
) : SeccompNotifHandler {
    context(arena: NativeArena) override fun beginNotification(
        notif: ManagedSegment,
        resp: ManagedSegment,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        deadline: Deadline,
    ): NotificationWork {
        check(listenerFd === listener)
        val notification = SeccompNotifications.read(notif)
        val actualAdmission = admission
        if (actualAdmission != null && native != null) {
            val serial = actualAdmission.begin(socketFd)
            if (serial != null) return LiveNotificationWork(notification, resp, socketFd, deadline, native, actualAdmission, serial, replies)
        }
        val id = notification.id
        return object : NotificationWork {
            context(arena: NativeArena) override fun poll(): NotificationProgress =
                NotificationProgress.Ready(
                    {
                        when (val result = replies.execute(SupervisorReplyEffect.Error(), SupervisorReplyContext(id, resp))) {
                            ReplyExecutionResult.ReplySucceeded -> UserNotifReplyResult.Sent
                            is ReplyExecutionResult.ReplyFailed -> result.outcome
                        }
                    },
                    if (actualAdmission == null) NotifResult.HANDLED else NotifResult.TERMINATE,
                )

            override fun close() = Unit
        }
    }
}

internal enum class LiveObservation { ObservationMatched, ObservationDenied }

private class LiveNotificationWork(
    private val notification: io.mazewall.platform.seccomp.SeccompNotification,
    private val response: ManagedSegment,
    private val socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val deadline: Deadline,
    private val native: NativeEngine,
    private val admission: ConfinedHelperControl.LiveAdmission,
    private val serial: Long,
    private val replies: SupervisorReplyInterpreter,
) : NotificationWork {
    private var exchange: ControlSocketExchange? = null
    private var request: ByteArray? = null
    private var finished = false
    private var failure: Throwable? = null
    private var observation: LiveObservation? = null

    context(arena: NativeArena) override fun poll(): NotificationProgress {
        check(!finished)
        val cleanup = SocketCleanup()
        var progress: NotificationProgress? = null
        cleanup.attempt { progress = advance() }
        val problem = cleanup.failure
        if (problem != null) {
            admission.finish(serial, false)
            finished = true
            if (problem is Error) throw problem
            failure = problem
            return terminal(NotifResult.TERMINATE)
        }
        return checkNotNull(progress)
    }

    context(arena: NativeArena) private fun advance(): NotificationProgress {
        val current = exchange
        if (current == null) {
            val bytes = LiveObservationProtocol.request(notification, serial, ConfinedDeadline.mint(native) { (deadline.nanoTime - System.nanoTime()).coerceAtLeast(0) })
            request = bytes
            exchange = ControlSocketExchange(native.raw, native.memory, socket, bytes, LiveObservationProtocol.RESPONSE_BYTES, deadline)
            return NotificationProgress.Await
        }
        val bytes = current.poll() ?: return NotificationProgress.Await
        val matched = LiveObservationProtocol.matched(checkNotNull(request), bytes)
        return if (!admission.finish(serial, true)) {
            finished = true
            observation = LiveObservation.ObservationDenied
            terminal(NotifResult.TERMINATE)
        } else {
            observation = if (matched) LiveObservation.ObservationMatched else LiveObservation.ObservationDenied
            finished = true
            terminal(NotifResult.HANDLED)
        }
    }

    context(arena: NativeArena) private fun terminal(result: NotifResult): NotificationProgress =
        NotificationProgress.Ready(
            {
                when (val outcome = replies.execute(SupervisorReplyEffect.Error(), SupervisorReplyContext(notification.id, response))) {
                    ReplyExecutionResult.ReplySucceeded -> UserNotifReplyResult.Sent
                    is ReplyExecutionResult.ReplyFailed -> outcome.outcome
                }
            },
            result,
        )

    override fun close() {
        if (!finished) admission.finish(serial, false)
        finished = true
        failure?.let { throw it }
    }
}
