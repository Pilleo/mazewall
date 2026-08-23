package io.mazewall.enforcer.supervisor

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.platform.seccomp.UserNotifReply

/**
 * Seccomp USER_NOTIF response marshalling, extracted from [SupervisorSessionHandler]
 * (issue-20260823-171956, slice 2).
 *
 * INVARIANT (profiler/enforcer AGENTS): every received notification MUST be answered with
 * CONTINUE/SUCCESS/ERROR exactly once — a missed reply deadlocks the tracee forever.
 */
internal class SupervisorResponseWriter(
    private val engine: io.mazewall.NativeEngine,
    private val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
) {
    fun sendContinue(id: Long, resp: ManagedSegment) {
        UserNotifReply.encodeContinue(resp, id)
        UserNotifReply.send(engine.raw, listenerFd, resp)
    }

    fun sendSuccess(id: Long, value: Long, resp: ManagedSegment) {
        UserNotifReply.encodeSuccess(resp, id, value)
        UserNotifReply.send(engine.raw, listenerFd, resp)
    }

    fun sendError(id: Long, errorNr: Int, resp: ManagedSegment) {
        UserNotifReply.encodeError(resp, id, errorNr)
        UserNotifReply.send(engine.raw, listenerFd, resp)
    }
}
