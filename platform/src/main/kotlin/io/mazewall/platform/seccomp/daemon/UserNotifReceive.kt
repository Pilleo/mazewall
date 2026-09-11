package io.mazewall.platform.seccomp.daemon

import io.mazewall.LinuxNative
import io.mazewall.MazewallInternal
import io.mazewall.RawSyscallOperations
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.IoctlPayload
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.typed

/** Result of one nonblocking notification receive attempt after listener readiness. */
@MazewallInternal
public sealed interface ReceiveOutcome {
    public data object Received : ReceiveOutcome

    /** Readiness was consumed by a signal; the reactor must poll before another ioctl. */
    public data object RetryAfterPoll : ReceiveOutcome

    /** The nonblocking listener no longer had a notification available. */
    public data object NotReady : ReceiveOutcome

    public data object Closed : ReceiveOutcome

    public data class Failed(
        public val errno: Int,
    ) : ReceiveOutcome
}

/** Shared one-attempt receive classification; it never performs an immediate retry. */
@MazewallInternal
public object UserNotifReceive {
    public fun receiveOne(
        raw: RawSyscallOperations,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        notification: ManagedSegment,
    ): ReceiveOutcome =
        when (
            val result = raw.ioctl(
                listenerFd,
                IoctlCommand.SECCOMP_IOCTL_NOTIF_RECV,
                notification.typed<IoctlPayload.SeccompNotif>(),
            )
        ) {
            is LinuxNative.SyscallResult.Success -> ReceiveOutcome.Received
            is LinuxNative.SyscallResult.Error ->
                when (result.errno) {
                    NativeConstants.EINTR -> ReceiveOutcome.RetryAfterPoll
                    NativeConstants.EAGAIN -> ReceiveOutcome.NotReady
                    NativeConstants.EBADF -> ReceiveOutcome.Closed
                    else -> ReceiveOutcome.Failed(result.errno)
                }
        }
}
