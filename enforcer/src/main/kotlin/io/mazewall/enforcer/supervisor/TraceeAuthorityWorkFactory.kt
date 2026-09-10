package io.mazewall.enforcer.supervisor

import io.mazewall.core.Deadline
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.platform.seccomp.SeccompNotification
import io.mazewall.platform.seccomp.daemon.NotificationWork

/** Listener-bound authority jobs share daemon capacity and retain only that listener's reply interpreter. */
internal class TraceeAuthorityWorkFactory(
    private val helpers: SocketHelperPool,
    private val roots: List<String>,
    private val exchange: JvmVerdictExchange,
    private val replies: SupervisorReplyInterpreter,
    private val reportUncaught: (Throwable) -> Unit,
) {
    @Suppress("TooGenericExceptionCaught") // Roll back the request session on any construction failure, then rethrow.
    fun begin(
        notification: SeccompNotification,
        response: ManagedSegment,
        deadline: Deadline,
    ): NotificationWork {
        val session = helpers.session()
        try {
            return TraceeAuthorityNotificationWork(
                notification,
                response,
                deadline,
                roots,
                session,
                exchange,
                replies,
                { replies.isNotificationValid(notification.id) },
                reportUncaught,
            )
        } catch (failure: Throwable) {
            val cleanup = SocketCleanup(failure)
            cleanup.attempt(session::cancel)
            cleanup.rethrow()
            throw failure
        }
    }
}
