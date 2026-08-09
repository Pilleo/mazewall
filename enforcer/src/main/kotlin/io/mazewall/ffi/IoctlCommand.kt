package io.mazewall.ffi

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.ffi.memory.ManagedSegment

public sealed interface IoctlPayload {
    public interface SeccompNotif : IoctlPayload
    public interface SeccompNotifResp : IoctlPayload
    public interface SeccompNotifAddFd : IoctlPayload
}

@JvmInline
public value class TypedSegment<out P : IoctlPayload>(public val segment: ManagedSegment)

public fun <P : IoctlPayload> ManagedSegment.typed(): TypedSegment<P> = TypedSegment(this)

public class IoctlCommand<Req, Res>(public val code: Long) {
    public companion object {
        public val SECCOMP_IOCTL_NOTIF_RECV: IoctlCommand<TypedSegment<IoctlPayload.SeccompNotif>, TypedSegment<IoctlPayload.SeccompNotif>> =
            IoctlCommand(NativeConstants.SECCOMP_IOCTL_NOTIF_RECV)

        public val SECCOMP_IOCTL_NOTIF_SEND: IoctlCommand<TypedSegment<IoctlPayload.SeccompNotifResp>, TypedSegment<IoctlPayload.SeccompNotifResp>> =
            IoctlCommand(NativeConstants.SECCOMP_IOCTL_NOTIF_SEND)

        public val SECCOMP_IOCTL_NOTIF_ADDFD: IoctlCommand<TypedSegment<IoctlPayload.SeccompNotifAddFd>, TypedSegment<IoctlPayload.SeccompNotifAddFd>> =
            IoctlCommand(NativeConstants.SECCOMP_IOCTL_NOTIF_ADDFD)
    }
}
