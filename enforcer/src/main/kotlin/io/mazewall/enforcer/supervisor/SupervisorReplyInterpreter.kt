package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.NewFdFlags
import io.mazewall.core.Tid
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.IoctlPayload
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.SeccompNotifAddFdSegment
import io.mazewall.ffi.memory.fill
import io.mazewall.ffi.typed
import io.mazewall.platform.seccomp.UserNotifReply
import io.mazewall.platform.seccomp.UserNotifReplyResult

internal sealed interface FdInjectionResult {
    data class Injected(
        val fd: Int,
    ) : FdInjectionResult

    data class Failed(
        val errno: Int,
    ) : FdInjectionResult
}

/** Authoritative terminal kernel operations. Failure never escapes into another reply attempt. */
internal class SupervisorReplyInterpreter(
    private val engine: NativeEngine,
    private val listener: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    private val rewrite: SupervisorExecRewriteExchange,
    private val descriptors: TraceeDescriptorImporter,
    private val readOnlyNul: (Tid) -> Long? = { tid -> NativeArena.ofConfined().use { arena -> with(arena) { TraceeReadOnlyNul.find(tid) } } },
) {
    context(arena: NativeArena) fun execute(
        effect: SupervisorReplyEffect,
        context: SupervisorReplyContext,
    ): ReplyExecutionResult =
        try {
            when (effect) {
                SupervisorReplyEffect.Continue -> sendContinue(context)
                is SupervisorReplyEffect.Value -> {
                    UserNotifReply.encodeSuccess(context.response, context.id, effect.value)
                    confirmed(UserNotifReply.send(engine.raw, listener, context.response))
                }
                is SupervisorReplyEffect.Error -> sendError(context, effect.errno)
                is SupervisorReplyEffect.AddFdAndReply -> effect.descriptor.use { injectReply(context, it, effect.flags) }
                is SupervisorReplyEffect.AcceptedSocket -> acceptedReply(effect, context)
                is SupervisorReplyEffect.SecureExecAndReply -> secureExec(effect, context)
                is SupervisorReplyEffect.Recycle -> ReplyExecutionResult.ReplyFailed(IllegalStateException(effect.reason))
            }
        } catch (expectedReplyFailure: Exception) {
            ReplyExecutionResult.ReplyFailed(expectedReplyFailure)
        }

    context(arena: NativeArena) private fun acceptedReply(
        effect: SupervisorReplyEffect.AcceptedSocket,
        context: SupervisorReplyContext,
    ): ReplyExecutionResult =
        effect.descriptor.use { descriptor ->
            val snapshot = effect.address
            val address = arena.allocate(maxOf(1, snapshot.size).toLong())
            io.mazewall.ffi.memory.ManagedSegment
                .copy(snapshot, 0, address, 0, snapshot.size)
            if (SupervisorPeerAddress().copy(effect.request, address, effect.length)) {
                injectReply(context, descriptor, NewFdFlags.forAccept(effect.request.flags))
            } else {
                sendError(context, NativeConstants.EFAULT)
            }
        }

    context(arena: NativeArena) private fun injectReply(
        context: SupervisorReplyContext,
        descriptor: SupervisorOwnedFd,
        flags: NewFdFlags,
    ): ReplyExecutionResult =
        when (val result = inject(context.id, descriptor, flags, NativeConstants.SECCOMP_ADDFD_FLAG_SEND.toInt())) {
            is FdInjectionResult.Injected -> ReplyExecutionResult.ReplySucceeded
            is FdInjectionResult.Failed -> ReplyExecutionResult.ReplyFailed(IllegalStateException("Supervisor injection failed with errno=${result.errno}"))
        }

    private fun sendContinue(context: SupervisorReplyContext): ReplyExecutionResult {
        UserNotifReply.encodeContinue(context.response, context.id)
        return confirmed(UserNotifReply.send(engine.raw, listener, context.response))
    }

    private fun sendError(
        context: SupervisorReplyContext,
        errno: Int,
    ): ReplyExecutionResult {
        UserNotifReply.encodeError(context.response, context.id, errno)
        return confirmed(UserNotifReply.send(engine.raw, listener, context.response))
    }

    private fun confirmed(result: UserNotifReplyResult): ReplyExecutionResult =
        when (result) {
            UserNotifReplyResult.Sent -> ReplyExecutionResult.ReplySucceeded
            UserNotifReplyResult.NotificationGone -> ReplyExecutionResult.ReplyFailed(IllegalStateException("Supervisor notification expired before kernel reply"))
            is UserNotifReplyResult.Failed -> ReplyExecutionResult.ReplyFailed(IllegalStateException("Supervisor kernel reply failed with errno=${result.errno}"))
        }

    context(arena: NativeArena) private fun inject(
        id: Long,
        descriptor: SupervisorOwnedFd,
        flags: NewFdFlags,
        operation: Int,
    ): FdInjectionResult {
        val add = SeccompNotifAddFdSegment.of(arena.allocate(Layouts.SECCOMP_NOTIF_ADDFD))
        add.managed.fill(0)
        add.setId(id)
        add.setFlags(operation)
        add.setSrcfd(descriptor.handle.value)
        add.setNewfdFlags(flags.value)
        while (true) {
            val result = engine.raw.ioctl(listener, IoctlCommand.SECCOMP_IOCTL_NOTIF_ADDFD, add.managed.typed<IoctlPayload.SeccompNotifAddFd>())
            if (result is LinuxNative.SyscallResult.Error && result.errno == NativeConstants.EINTR) continue
            return when (result) {
                is LinuxNative.SyscallResult.Error -> FdInjectionResult.Failed(result.errno)
                is LinuxNative.SyscallResult.Success ->
                    if (result.value in 0..Int.MAX_VALUE.toLong()) FdInjectionResult.Injected(result.value.toInt()) else FdInjectionResult.Failed(NativeConstants.EIO)
            }
        }
    }

    context(arena: NativeArena) private fun secureExec(
        effect: SupervisorReplyEffect.SecureExecAndReply,
        context: SupervisorReplyContext,
    ): ReplyExecutionResult =
        when (val target = effect.target) {
            is SupervisorDescriptorResult.Denied -> abortExec(effect.request, context, target.errno)
            is SupervisorDescriptorResult.Opened -> target.descriptor.use {
                when (val injected = inject(context.id, it, NewFdFlags.forExec(), 0)) {
                    is FdInjectionResult.Failed -> abortExec(effect.request, context, NativeConstants.EPERM)
                    is FdInjectionResult.Injected -> completeExec(effect.request, injected.fd, context)
                }
            }
        }

    context(arena: NativeArena) private fun abortExec(
        request: SecureExecRequest,
        context: SupervisorReplyContext,
        errno: Int,
    ): ReplyExecutionResult {
        rewrite.request(request, -1, 0)
        return sendError(context, errno)
    }

    context(arena: NativeArena) private fun completeExec(
        request: SecureExecRequest,
        injected: Int,
        context: SupervisorReplyContext,
    ): ReplyExecutionResult {
        val nul = readOnlyNul(request.tid)
        if (nul != null && rewrite.request(request, injected, nul)) return sendContinue(context)
        // pidfd_getfd imports a LOCAL reference, never ownership of the remote table entry.
        // Closing it does not close the tracee descriptor; that remains until tracee teardown.
        when (val imported = descriptors.import(request.tid, injected)) {
            is SupervisorDescriptorResult.Denied -> Unit
            is SupervisorDescriptorResult.Opened -> imported.descriptor.close()
        }
        return sendError(context, NativeConstants.EPERM)
    }
}
