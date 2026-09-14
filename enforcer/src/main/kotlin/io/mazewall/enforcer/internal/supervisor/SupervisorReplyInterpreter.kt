package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.IoctlPayload
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.SeccompNotifAddFdSegment
import io.mazewall.ffi.memory.fill
import io.mazewall.ffi.memory.writeLong
import io.mazewall.ffi.typed
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.lifecycle.NewFdFlags
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.seccomp.UserNotifReply
import io.mazewall.platform.seccomp.UserNotifReplyResult
import io.mazewall.platform.seccomp.daemon.NotificationProgress
import io.mazewall.platform.seccomp.daemon.NotificationWork

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
    @Suppress("UnusedPrivateProperty") private val descriptors: TraceeDescriptorImporter,
    @Suppress("UnusedPrivateProperty") private val readOnlyNul: (Tid) -> Long? = { tid -> NativeArena.ofConfined().use { arena -> with(arena) { TraceeReadOnlyNul.find(tid) } } },
) {
    fun isNotificationValid(id: Long): Boolean =
        NativeArena.ofConfined().use { arena ->
        val buffer = arena.allocate(8L)
        buffer.writeLong(0L, id)
            repeat(MAX_IOCTL_ATTEMPTS) {
            when (val result = engine.raw.ioctl(listener, NativeConstants.SECCOMP_IOCTL_NOTIF_ID_VALID, buffer)) {
                is LinuxNative.SyscallResult.Success -> return@use true
                is LinuxNative.SyscallResult.Error -> if (result.errno != NativeConstants.EINTR) return@use false
            }
        }
        false
    }

    /** Nonterminal exec injection/rewrite precedes the reactor's terminal reply lease. */
    context(arena: NativeArena) fun prepareSecureExec(
        effect: SupervisorReplyEffect.SecureExecAndReply,
        context: SupervisorReplyContext,
        deadline: Deadline,
    ): NotificationWork {
        val errno = when (val target = effect.target) {
            is SupervisorDescriptorResult.Denied -> target.errno
            is SupervisorDescriptorResult.Opened -> target.descriptor.use { NativeConstants.EPERM }
        }
        // Register rewriting is not implemented by the JVM peer.  Do not inject an
        // executable into the tracee before the peer can consume that ownership: a
        // negative rewrite request preserves protocol framing and fails closed.
        val exchange = rewrite.begin(effect.request, -1, 0, deadline)
        return object : NotificationWork {
            context(arena: NativeArena) override fun poll(): NotificationProgress {
                exchange.poll() ?: return NotificationProgress.Await
                return NotificationProgress.Ready({
                    UserNotifReply.encodeError(context.response, context.id, errno)
                    UserNotifReply.send(engine.raw, listener, context.response)
                })
            }
        }
    }

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
                is SupervisorReplyEffect.UnsupportedAuthority -> {
                    java.util.logging.Logger
                        .getLogger(SupervisorReplyInterpreter::class.java.name)
                        .warning("Unsupported tracee authority: ${effect.authority}; notification=${context.id}; denied with EPERM")
                    sendError(context, NativeConstants.EPERM)
                }
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

    /** Admission overload consumes the reactor's terminal lease even on EINTR. */
    fun rejectOverload(context: SupervisorReplyContext): UserNotifReplyResult {
        UserNotifReply.encodeError(context.response, context.id, NativeConstants.EAGAIN)
        return UserNotifReply.send(engine.raw, listener, context.response, maxAttempts = 1)
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
        return confirmed(UserNotifReply.send(engine.raw, listener, context.response, maxAttempts = if (errno == NativeConstants.EAGAIN) 1 else 8))
    }

    private fun confirmed(result: UserNotifReplyResult): ReplyExecutionResult =
        when (result) {
            UserNotifReplyResult.Sent -> ReplyExecutionResult.ReplySucceeded
            UserNotifReplyResult.NotificationGone -> ReplyExecutionResult.ReplyFailed(IllegalStateException("Supervisor notification expired before kernel reply"), result)
            is UserNotifReplyResult.Failed -> ReplyExecutionResult.ReplyFailed(IllegalStateException("Supervisor kernel reply failed with errno=${result.errno}"), result)
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
        repeat(MAX_IOCTL_ATTEMPTS) {
            val result = engine.raw.ioctl(listener, IoctlCommand.SECCOMP_IOCTL_NOTIF_ADDFD, add.managed.typed<IoctlPayload.SeccompNotifAddFd>())
            if (result is LinuxNative.SyscallResult.Error && result.errno == NativeConstants.EINTR) return@repeat
            return when (result) {
                is LinuxNative.SyscallResult.Error -> FdInjectionResult.Failed(result.errno)
                is LinuxNative.SyscallResult.Success ->
                    if (result.value in 0..Int.MAX_VALUE.toLong()) FdInjectionResult.Injected(result.value.toInt()) else FdInjectionResult.Failed(NativeConstants.EIO)
            }
        }
        return FdInjectionResult.Failed(NativeConstants.EINTR)
    }

    context(arena: NativeArena) private fun secureExec(
        effect: SupervisorReplyEffect.SecureExecAndReply,
        context: SupervisorReplyContext,
    ): ReplyExecutionResult =
        when (val target = effect.target) {
            is SupervisorDescriptorResult.Denied -> abortExec(effect.request, context, target.errno)
            is SupervisorDescriptorResult.Opened -> target.descriptor.use { abortExec(effect.request, context, NativeConstants.EPERM) }
        }

    context(arena: NativeArena) private fun abortExec(
        request: SecureExecRequest,
        context: SupervisorReplyContext,
        errno: Int,
    ): ReplyExecutionResult {
        rewrite.request(request, -1, 0)
        return sendError(context, errno)
    }

    private companion object {
        const val MAX_IOCTL_ATTEMPTS = 8
    }
}
