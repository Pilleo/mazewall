package io.mazewall.enforcer.supervisor

import io.mazewall.core.Deadline
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.seccomp.UserNotifReplyResult
import io.mazewall.platform.seccomp.daemon.ControlSocketExchange
import io.mazewall.platform.seccomp.daemon.NotifResult
import io.mazewall.platform.seccomp.daemon.NotificationProgress
import io.mazewall.platform.seccomp.daemon.NotificationWork
import java.util.concurrent.CompletableFuture

/** Existing routing decisions, with IPC and helper completion advanced only by the reactor. */
internal class SupervisorNotificationWork(
    private val notification: io.mazewall.platform.seccomp.SeccompNotification,
    private val response: ManagedSegment,
    private val deadline: Deadline,
    private val preparation: SupervisorRoutePreparation,
    private val exchange: JvmVerdictExchange,
    private val routes: SupervisorRouteDispatcher,
    private val replies: SupervisorReplyInterpreter,
    private val reportUncaught: (Throwable) -> Unit,
) : NotificationWork {
    private var prepared: PreparedSupervisorRoute? = null
    private var control: ControlSocketExchange? = null
    private var future: CompletableFuture<SupervisorReplyEffect>? = null
    private var handoff: SupervisorReplyHandoff? = null
    private var execWork: NotificationWork? = null
    private var closed = false

    context(arena: NativeArena) override fun poll(): NotificationProgress {
        check(!closed)
        execWork?.let { return it.poll() }
        var route = prepared
        if (route == null) {
            route = preparation.prepare(notification, response)
            prepared = route
            if (route.route == SupervisorRoute.AskJvm) {
                control = exchange.begin(route.context.request, deadline)
            } else {
                future = routes.execute(route.route, route.context, null)
            }
            return NotificationProgress.Await
        }
        control?.let { transport ->
            val bytes = transport.poll() ?: return NotificationProgress.Await
            control = null
            when (val verdict = exchange.decode(route.context.request, bytes)) {
                is JvmExchangeResult.Verdict -> future = routes.execute(verdict.route, route.context, verdict.path)
                is JvmExchangeResult.Timeout, is JvmExchangeResult.Malformed, is JvmExchangeResult.TransportFailed -> {
                    return terminal(SupervisorReplyEffect.Error(), NotifResult.TERMINATE)
                }
            }
            return NotificationProgress.Await
        }
        val pending = checkNotNull(future)
        if (!pending.isDone) return NotificationProgress.Await
        val effect = pending.join()
        future = null
        if (effect is SupervisorReplyEffect.SecureExecAndReply) {
            val work = replies.prepareSecureExec(effect, SupervisorReplyContext(notification.id, response), deadline)
            execWork = work
            return NotificationProgress.Await
        }
        return terminal(effect)
    }

    context(arena: NativeArena) private fun terminal(
        effect: SupervisorReplyEffect,
        result: NotifResult = NotifResult.HANDLED,
    ): NotificationProgress {
        val owned = SupervisorReplyHandoff(effect)
        handoff = owned
        return NotificationProgress.Ready(
            reply = {
                val outcome = owned.transferTo(replies, SupervisorReplyContext(notification.id, response))
                when (outcome) {
                    ReplyExecutionResult.ReplySucceeded -> UserNotifReplyResult.Sent
                    is ReplyExecutionResult.ReplyFailed -> outcome.outcome
                }
            },
            result = result,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        handoff?.close()
        execWork?.close()
        // Helpers own native execution. A late result has disposal authority only.
        future?.whenComplete { effect, _ ->
            try {
                if (effect != null) SupervisorReplyHandoff(effect).close()
            } catch (expectedDisposalFailure: Throwable) {
                // CompletableFuture captures callback throws: report late cleanup explicitly.
                reportUncaught(expectedDisposalFailure)
            }
        }
    }
}
