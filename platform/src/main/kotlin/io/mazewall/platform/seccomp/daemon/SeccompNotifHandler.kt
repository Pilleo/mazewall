package io.mazewall.platform.seccomp.daemon

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.Deadline

internal enum class NotifResult {
    HANDLED,
    TERMINATE,
    PASS_THROUGH,
}

/** A normal producer boundary, distinct from transport, reply and reactor failure. */
internal enum class SeccompStreamEnd { PRODUCER_EXIT, PASS_THROUGH }

/**
 * Strategy interface for processing seccomp user notifications inside [SeccompSessionHandler].
 */
@io.mazewall.platform.lifecycle.MazewallInternal
internal interface SeccompNotifHandler {
    public fun endStream(reason: SeccompStreamEnd) = Unit

    context(arena: NativeArena) public fun beginNotification(
        notif: ManagedSegment,
        resp: ManagedSegment,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        deadline: Deadline,
    ): NotificationWork
}
