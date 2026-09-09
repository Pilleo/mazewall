package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.fill
import io.mazewall.platform.seccomp.SeccompNotifications
import io.mazewall.platform.seccomp.daemon.LoopAction
import io.mazewall.platform.seccomp.daemon.NotifResult
import io.mazewall.platform.seccomp.daemon.SeccompNotifHandler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/** Coordinates one reducer/effect lifecycle per notification, including deferred socket replies. */
internal class SupervisorSessionHandler(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    engine: NativeEngine = LinuxNative,
    socketManager: SocketManager = RealSocketManager,
    private val preparation: SupervisorRoutePreparation = SupervisorRoutePreparation(),
) : SeccompNotifHandler {
    private val reader = NotificationReader(engine, logger)
    private val exchange = JvmVerdictExchange(engine, socketFd, socketManager)
    private val descriptors = TraceeDescriptorImporter(engine)
    private val routes = SupervisorRouteDispatcher(SupervisorRouteExecutor(engine, descriptors))
    private val replies = SupervisorReplyInterpreter(engine, listenerFd, SupervisorExecRewriteExchange(engine, socketFd), descriptors)
    private val terminated = AtomicBoolean(false)

    context(arena: NativeArena) override fun processNotification(
        notif: ManagedSegment,
        resp: ManagedSegment,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ): NotifResult = if (processNotification(notif, resp)) NotifResult.HANDLED else NotifResult.TERMINATE

    fun processNotification(
        notif: ManagedSegment,
        resp: ManagedSegment,
    ): Boolean =
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                if (terminated.get()) return@with false
                val notification = SeccompNotifications.read(notif)
                val resolving = SupervisorSessionMachine.evaluate(SupervisorSessionState.AwaitingNotification, SupervisorSessionEvent.NotificationReceived)
                val prepared = try {
                    preparation.prepare(notification, resp)
                } catch (expectedPreparationFailure: Exception) {
                    logger.log(Level.WARNING, "Unable to prepare notification ${notification.id}", expectedPreparationFailure)
                    return@with finish(SupervisorReplyEffect.Error(), SupervisorReplyContext(notification.id, resp))
                }
                val transition = SupervisorSessionMachine.evaluate(resolving.state, SupervisorSessionEvent.PathResolved(prepared.route))
                try {
                    interpret(transition, prepared.context)
                } catch (expectedExecutionFailure: Exception) {
                    logger.log(Level.WARNING, "Notification execution failed for ${notification.id}", expectedExecutionFailure)
                    if (terminated.get()) false else failClosed(prepared.context, transition.state, SupervisorSessionEvent.Failed)
                }
            }
        }

    context(arena: NativeArena) private fun interpret(
        transition: SupervisorSessionTransition,
        context: SupervisorRouteContext,
    ): Boolean =
        when (val effect = transition.effect) {
            is SupervisorSessionEffect.ExecuteRoute -> completeRoute(routes.execute(effect.route, context, null), context)
            SupervisorSessionEffect.RequestJvmVerdict -> {
                if (exchange.send(context.request)) {
                    val awaiting = SupervisorSessionMachine.evaluate(transition.state, SupervisorSessionEvent.JvmRequestSent)
                    readAndHandleJvmResponse(context, awaiting.state)
                } else {
                    failClosed(context, transition.state, SupervisorSessionEvent.JvmRequestFailed)
                }
            }
            is SupervisorSessionEffect.Abort -> finish(SupervisorReplyEffect.Error(effect.errno), SupervisorReplyContext(context.request.id, context.response))
            is SupervisorSessionEffect.ProtocolViolation -> finish(
                SupervisorReplyEffect.Recycle("Invalid notification transition: $effect"),
                SupervisorReplyContext(context.request.id, context.response),
            )
            SupervisorSessionEffect.PostTerminalObservation -> false
            SupervisorSessionEffect.ResolvePath, null -> false
        }

    context(arena: NativeArena) internal fun readAndHandleJvmResponse(
        context: SupervisorRouteContext,
        state: SupervisorSessionState = SupervisorSessionState.AwaitingJvmVerdict,
    ): Boolean =
        when (val result = exchange.receive(context.request)) {
            is JvmExchangeResult.Verdict -> {
                val transition = SupervisorSessionMachine.evaluate(state, SupervisorSessionEvent.JvmVerdictReceived(result.route))
                when (val effect = transition.effect) {
                    is SupervisorSessionEffect.ExecuteRoute -> completeRoute(routes.execute(effect.route, context, result.path), context)
                    is SupervisorSessionEffect.Abort -> finish(SupervisorReplyEffect.Error(effect.errno), SupervisorReplyContext(context.request.id, context.response))
                    is SupervisorSessionEffect.ProtocolViolation -> finish(
                        SupervisorReplyEffect.Recycle("Invalid verdict transition: $effect"),
                        SupervisorReplyContext(context.request.id, context.response),
                    )
                    SupervisorSessionEffect.PostTerminalObservation, SupervisorSessionEffect.RequestJvmVerdict,
                    SupervisorSessionEffect.ResolvePath, null,
                    -> false
                }
            }
            is JvmExchangeResult.Timeout, is JvmExchangeResult.Malformed, is JvmExchangeResult.TransportFailed -> {
                logger.log(Level.WARNING, "Unusable JVM exchange: $result", result.cleanupFailure)
                failClosed(context, state, SupervisorSessionEvent.Failed)
            }
        }

    context(arena: NativeArena) private fun failClosed(
        context: SupervisorRouteContext,
        state: SupervisorSessionState,
        event: SupervisorSessionEvent,
    ): Boolean {
        val transition = SupervisorSessionMachine.evaluate(state, event)
        when (val effect = transition.effect) {
            is SupervisorSessionEffect.Abort -> finish(SupervisorReplyEffect.Error(effect.errno), SupervisorReplyContext(context.request.id, context.response))
            is SupervisorSessionEffect.ExecuteRoute, is SupervisorSessionEffect.ProtocolViolation,
            SupervisorSessionEffect.PostTerminalObservation, SupervisorSessionEffect.RequestJvmVerdict,
            SupervisorSessionEffect.ResolvePath, null,
            -> Unit
        }
        recycle()
        return false
    }

    context(arena: NativeArena) private fun completeRoute(
        result: CompletableFuture<SupervisorReplyEffect>,
        context: SupervisorRouteContext,
    ): Boolean {
        val id = context.request.id
        val caller = Thread.currentThread()
        var callerFatal: Throwable? = null
        val completion = result.handle { effect, failure ->
            try {
                if (failure != null) {
                    completeReply(id, ReplyExecutionResult.ReplyFailed(failure))
                } else {
                    NativeArena.ofConfined().use { workerArena ->
                        with(workerArena) {
                            finish(effect, SupervisorReplyContext(id, workerArena.allocate(Layouts.SECCOMP_NOTIF_RESP)))
                        }
                    }
                }
            } catch (expectedCompletionFailure: Exception) {
                recordTerminalFailure(id, expectedCompletionFailure)
                false
            } catch (expectedFatalFailure: Throwable) {
                recordTerminalFailure(id, expectedFatalFailure)
                // Future callbacks capture throws. Propagate outside the callback on the
                // caller, or explicitly deliver uncaught failure before this worker exits.
                if (Thread.currentThread() === caller) callerFatal = expectedFatalFailure else reportUncaught(expectedFatalFailure)
                false
            }
        }
        callerFatal?.let { throw it }
        // Accepted async work remains ExecutingReply; only finish can produce Replied.
        return completion.getNow(true)
    }

    context(arena: NativeArena) private fun finish(
        effect: SupervisorReplyEffect,
        context: SupervisorReplyContext,
    ): Boolean =
        try {
            completeReply(context.id, replies.execute(effect, context))
        } catch (expectedTerminalFailure: Throwable) {
            recordTerminalFailure(context.id, expectedTerminalFailure)
            throw expectedTerminalFailure
        }

    private fun recordTerminalFailure(
        id: Long,
        failure: Throwable,
    ) {
        try {
            completeReply(id, ReplyExecutionResult.ReplyFailed(failure))
        } catch (expectedShutdownFailure: Throwable) {
            if (expectedShutdownFailure !== failure) failure.addSuppressed(expectedShutdownFailure)
        }
    }

    private fun reportUncaught(failure: Throwable) {
        val worker = Thread.currentThread()
        try {
            worker.uncaughtExceptionHandler.uncaughtException(worker, failure)
        } catch (expectedHandlerFailure: Throwable) {
            // Match JVM uncaught-handler isolation, while retaining secondary evidence.
            if (expectedHandlerFailure !== failure) failure.addSuppressed(expectedHandlerFailure)
        }
    }

    private fun completeReply(
        id: Long,
        result: ReplyExecutionResult,
    ): Boolean {
        val event = when (result) {
            ReplyExecutionResult.ReplySucceeded -> SupervisorSessionEvent.ReplySucceeded
            is ReplyExecutionResult.ReplyFailed -> SupervisorSessionEvent.ReplyFailed
        }
        val state = SupervisorSessionMachine.evaluate(SupervisorSessionState.ExecutingReply, event).state
        if (state == SupervisorSessionState.Terminated) recycle()
        if (result is ReplyExecutionResult.ReplyFailed) logger.log(Level.WARNING, "Terminal reply failed for notification $id", result.cause)
        return state == SupervisorSessionState.Replied
    }

    private fun recycle() {
        if (!terminated.compareAndSet(false, true)) return
        try {
            exchange.close()
        } catch (expectedCloseFailure: Exception) {
            logger.log(Level.WARNING, "Unable to close supervisor control socket", expectedCloseFailure)
        }
    }

    context(arena: NativeArena) fun handleActiveListener(
        pollFds: ManagedSegment,
        notif: ManagedSegment,
        resp: ManagedSegment,
    ): LoopAction {
        if (terminated.get()) return LoopAction.Break
        val control = PollFdSegment.of(pollFds.asSlice(Layouts.POLLFD_SIZE, Layouts.POLLFD_SIZE))
        val shutdown = NativeConstants.POLLIN.toInt() or NativeConstants.POLLERR.toInt() or NativeConstants.POLLHUP.toInt() or NativeConstants.POLLNVAL.toInt()
        if (control.getRevents().toInt() and shutdown != 0) return LoopAction.Shutdown
        val listener = PollFdSegment.of(pollFds.asSlice(0, Layouts.POLLFD_SIZE))
        return if (listener.getRevents().toInt() and NativeConstants.POLLIN.toInt() != 0) {
            notif.fill(0)
            if (!reader.recvNotification(listenerFd, notif) || !processNotification(notif, resp)) LoopAction.Break else LoopAction.Continue
        } else {
            LoopAction.Continue
        }
    }

    private companion object {
        val logger: Logger = Logger.getLogger(SupervisorSessionHandler::class.java.name)
    }
}
