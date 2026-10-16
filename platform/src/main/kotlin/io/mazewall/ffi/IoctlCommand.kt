package io.mazewall.ffi

import io.mazewall.ffi.memory.ManagedSegment

internal sealed interface IoctlPayload {
    public interface SeccompNotif : IoctlPayload

    public interface SeccompNotifResp : IoctlPayload

    public interface SeccompNotifAddFd : IoctlPayload
}

@JvmInline
internal value class TypedSegment<out P : IoctlPayload>(
    public val segment: ManagedSegment,
)

internal fun <P : IoctlPayload> ManagedSegment.typed(): TypedSegment<P> = TypedSegment(this)

internal class IoctlCommand<Req, Res>(
    public val code: Long,
) {
    public companion object {
        public val SECCOMP_IOCTL_NOTIF_RECV: IoctlCommand<TypedSegment<IoctlPayload.SeccompNotif>, TypedSegment<IoctlPayload.SeccompNotif>> =
            IoctlCommand(NativeConstants.SECCOMP_IOCTL_NOTIF_RECV)

        public val SECCOMP_IOCTL_NOTIF_SEND: IoctlCommand<TypedSegment<IoctlPayload.SeccompNotifResp>, TypedSegment<IoctlPayload.SeccompNotifResp>> =
            IoctlCommand(NativeConstants.SECCOMP_IOCTL_NOTIF_SEND)

        public val SECCOMP_IOCTL_NOTIF_ADDFD: IoctlCommand<TypedSegment<IoctlPayload.SeccompNotifAddFd>, TypedSegment<IoctlPayload.SeccompNotifAddFd>> =
            IoctlCommand(NativeConstants.SECCOMP_IOCTL_NOTIF_ADDFD)
    }
}
