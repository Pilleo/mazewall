package io.mazewall.enforcer.supervisor

import io.mazewall.NativeEngine
import io.mazewall.core.Deadline
import io.mazewall.core.Tid
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.seccomp.SeccompNotification
import io.mazewall.platform.seccomp.daemon.NotificationProgress
import io.mazewall.platform.seccomp.daemon.NotificationWork

/** Reactor-owned notification leases; a listener's retirement disposes all of its work. */
internal class SupervisorNotificationSession(
    private val admission: SupervisorAdmission,
    private val engine: NativeEngine,
    private val descriptors: TraceeDescriptorImporter,
    private val helpers: SocketHelperPool,
    private val preparation: SupervisorRoutePreparation,
    private val exchange: JvmVerdictExchange,
    private val replies: SupervisorReplyInterpreter,
    private val reportUncaught: (Throwable) -> Unit,
    private val authority: ((SeccompNotification, ManagedSegment, Deadline) -> NotificationWork)? = null,
) : AutoCloseable {
    private val active = mutableSetOf<NotificationWork>()
    private var closed = false

    @Suppress("TooGenericExceptionCaught") // Acquisition transaction rolls back capacity and helper ownership before rethrowing.
    fun begin(
        notification: SeccompNotification,
        response: ManagedSegment,
        deadline: Deadline,
    ): NotificationWork {
        check(!closed) { "Supervisor notification session is closed" }
        val capacity = admission.acquire(Tid(notification.pid))
            ?: return object : NotificationWork {
                context(arena: NativeArena) override fun poll(): NotificationProgress = NotificationProgress.Ready({ replies.rejectOverload(SupervisorReplyContext(notification.id, response)) })
            }
        var acquired: SupervisorRouteExecutor? = null
        try {
            val delegate = if (authority != null) {
                authority.invoke(notification, response, deadline)
            } else {
                val executor = SupervisorRouteExecutor(engine, descriptors, helpers).also { acquired = it }
                SupervisorNotificationWork(notification, response, deadline, preparation, exchange, SupervisorRouteDispatcher(executor), replies, reportUncaught)
            }
            return object : NotificationWork {
                private var retired = false

                context(arena: NativeArena) override fun poll(): NotificationProgress = delegate.poll()

                override fun close() {
                    if (retired) return
                    retired = true
                    val cleanup = SocketCleanup()
                    cleanup.attempt { delegate.close() }
                    cleanup.attempt { acquired?.cancel() }
                    cleanup.attempt { capacity.close() }
                    active -= this
                    cleanup.rethrow()
                }
            }.also { active += it }
        } catch (failure: Throwable) {
            val cleanup = SocketCleanup(failure)
            cleanup.attempt { acquired?.cancel() }
            cleanup.attempt { capacity.close() }
            cleanup.rethrow()
            throw failure
        }
    }

    override fun close() {
        closed = true
        val cleanup = SocketCleanup()
        active.toList().forEach { work -> cleanup.attempt { work.close() } }
        cleanup.rethrow()
    }
}
