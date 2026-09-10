package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.core.Deadline
import io.mazewall.core.Tid
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.seccomp.SeccompNotification
import io.mazewall.platform.seccomp.UserNotifReplyResult
import io.mazewall.platform.seccomp.daemon.ControlSocketExchange
import io.mazewall.platform.seccomp.daemon.NotifResult
import io.mazewall.platform.seccomp.daemon.NotificationProgress
import io.mazewall.platform.seccomp.daemon.NotificationWork
import java.util.concurrent.CompletableFuture

/** Reactor interpreter: only copied frames, ID_VALID, and terminal ioctls execute here. */
internal class TraceeAuthorityNotificationWork(
    notification: SeccompNotification,
    private val response: ManagedSegment,
    private val deadline: Deadline,
    roots: List<String>,
    private val session: SocketHelperPool.Session,
    private val exchange: JvmVerdictExchange,
    private val replies: SupervisorReplyInterpreter,
    private val valid: () -> Boolean,
    private val reportUncaught: (Throwable) -> Unit,
) : NotificationWork {
    private val job = TraceeAuthorityJob(notification, deadline, roots)
    private var pending: CompletableFuture<SocketHelperResult>? = session.submitAuthority(job)
    private var request: JvmVerdictRequest? = null
    private var control: ControlSocketExchange? = null
    private var handoff: SupervisorReplyHandoff? = null
    private var exec: NotificationWork? = null
    private var closed = false

    context(arena: NativeArena) override fun poll(): NotificationProgress {
        check(!closed)
        exec?.let { return it.poll() }
        if (request == null) {
            if (!job.prepared.isDone) return NotificationProgress.Await
            val snapshot = job.prepared.join()
            if (!valid()) return terminal(SupervisorReplyEffect.Error(), NotifResult.TERMINATE)
            val notification = job.notification
            val header = NotifHeader(notification.nr, Tid(notification.pid), Arch.fromAudit(notification.arch), notification.arch, snapshot.ppid, notification.args)
            val captured = JvmVerdictRequest(notification.id, header, snapshot.path, snapshot.address).also { request = it }
            if (snapshot.route == SupervisorRoute.AskJvm) {
                control = exchange.begin(captured, deadline)
            } else {
                job.decision.complete(TraceeAuthorityDecision(snapshot.route, null))
            }
            return NotificationProgress.Await
        }
        control?.let { transport ->
            val bytes = transport.poll() ?: return NotificationProgress.Await
            control = null
            if (!valid()) return terminal(SupervisorReplyEffect.Error(), NotifResult.TERMINATE)
            when (val verdict = exchange.decode(checkNotNull(request), bytes)) {
                is JvmExchangeResult.Verdict -> job.decision.complete(TraceeAuthorityDecision(verdict.route, verdict.path))
                is JvmExchangeResult.Timeout, is JvmExchangeResult.Malformed, is JvmExchangeResult.TransportFailed ->
                    return terminal(SupervisorReplyEffect.Error(), NotifResult.TERMINATE)
            }
            return NotificationProgress.Await
        }
        val future = checkNotNull(pending)
        if (!future.isDone) return NotificationProgress.Await
        val result = future.join()
        pending = null
        if (!valid()) {
            result.dispose()
            return terminal(SupervisorReplyEffect.Error(), NotifResult.TERMINATE)
        }
        val effect = when (result) {
            is SocketHelperResult.Authority -> result.effect
            is SocketHelperResult.Failed -> SupervisorReplyEffect.Error(result.errno)
            SocketHelperResult.Connected, is SocketHelperResult.Opened, is SocketHelperResult.Accepted -> {
                result.dispose()
                SupervisorReplyEffect.Recycle("Unexpected authority helper result")
            }
        }
        if (effect is SupervisorReplyEffect.SecureExecAndReply) {
            check(effect.capturedEmptyPath != null) { "Authority helper omitted secure-exec snapshot" }
            exec = replies.prepareSecureExec(effect, SupervisorReplyContext(job.notification.id, response), deadline)
            return NotificationProgress.Await
        }
        return terminal(effect)
    }

    context(arena: NativeArena) private fun terminal(
        effect: SupervisorReplyEffect,
        result: NotifResult = NotifResult.HANDLED,
    ): NotificationProgress {
        val owned = SupervisorReplyHandoff(effect).also { handoff = it }
        return NotificationProgress.Ready({
            when (val outcome = owned.transferTo(replies, SupervisorReplyContext(job.notification.id, response))) {
                ReplyExecutionResult.ReplySucceeded -> UserNotifReplyResult.Sent
                is ReplyExecutionResult.ReplyFailed -> outcome.outcome
            }
        }, result)
    }

    @Suppress("TooGenericExceptionCaught") // Future callbacks capture Throwable; late disposal must report fatal cleanup too.
    override fun close() {
        if (closed) return
        closed = true
        val cleanup = SocketCleanup()
        cleanup.attempt { handoff?.close() }
        cleanup.attempt { exec?.close() }
        pending?.whenComplete { result, _ ->
            try {
                result?.dispose()
            } catch (failure: Throwable) {
                reportUncaught(failure)
            }
        }
        cleanup.attempt(session::cancel)
        cleanup.rethrow()
    }
}
