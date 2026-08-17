package io.mazewall.platform.seccomp

import io.mazewall.LinuxNative
import io.mazewall.RawSyscallOperations
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.IoctlPayload
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.fill
import io.mazewall.ffi.typed
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeLong

/** Shared USER_NOTIF reply layout used by supervisor, profiler, and the daemon reactor. */
public object UserNotifReply {
    public fun encodeContinue(resp: ManagedSegment, id: Long) {
        resp.fill(0)
        resp.writeLong(Layouts.SECCOMP_NOTIF_RESP_ID_OFFSET, id)
        resp.writeLong(Layouts.SECCOMP_NOTIF_RESP_VAL_OFFSET, 0L)
        resp.writeInt(Layouts.SECCOMP_NOTIF_RESP_ERROR_OFFSET, 0)
        resp.writeInt(
            Layouts.SECCOMP_NOTIF_RESP_FLAGS_OFFSET,
            NativeConstants.SECCOMP_USER_NOTIF_FLAG_CONTINUE.toInt(),
        )
    }

    public fun encodeError(resp: ManagedSegment, id: Long, errorNr: Int) {
        resp.fill(0)
        resp.writeLong(Layouts.SECCOMP_NOTIF_RESP_ID_OFFSET, id)
        resp.writeLong(Layouts.SECCOMP_NOTIF_RESP_VAL_OFFSET, -1L)
        resp.writeInt(Layouts.SECCOMP_NOTIF_RESP_ERROR_OFFSET, -errorNr)
        resp.writeInt(Layouts.SECCOMP_NOTIF_RESP_FLAGS_OFFSET, 0)
    }

    public fun send(
        raw: RawSyscallOperations,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        resp: ManagedSegment,
    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        while (true) {
            val res = raw.ioctl(
                listenerFd,
                IoctlCommand.SECCOMP_IOCTL_NOTIF_SEND,
                resp.typed<IoctlPayload.SeccompNotifResp>(),
            )
            if (res is LinuxNative.SyscallResult.Error && res.errno == NativeConstants.EINTR) {
                continue
            }
            return res
        }
    }
}
