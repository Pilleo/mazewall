package io.mazewall.platform.seccomp.daemon

import io.mazewall.core.Deadline
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena

public enum class NotifResult {
    HANDLED,
    TERMINATE,
    PASS_THROUGH,
}

/**
 * Strategy interface for processing seccomp user notifications inside [SeccompSessionHandler].
 */
@io.mazewall.MazewallInternal
public interface SeccompNotifHandler {
    context(arena: NativeArena) public fun beginNotification(
        notif: ManagedSegment,
        resp: ManagedSegment,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        deadline: Deadline,
    ): NotificationWork
}
