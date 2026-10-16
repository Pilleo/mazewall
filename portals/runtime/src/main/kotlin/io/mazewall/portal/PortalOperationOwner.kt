package io.mazewall.portal

import io.mazewall.enforcer.api.execution.InvocationDiagnostics
import io.mazewall.enforcer.api.execution.InvocationHandle
import io.mazewall.platform.lifecycle.Deadline
import java.util.concurrent.Callable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Bounded host mapping/transport dispatch; only the child executes protected service code. */
internal class PortalOperationOwner(
    private val identity: Any,
    private val limits: PortalRuntimeLimits,
    private val admission: PortalCallAdmission = PortalCallAdmission(limits),
) {
    private val lock = Any()
    private var accepting = true
    private val live = LinkedHashMap<OwnedPortalInvocation<*>, AutoCloseable>()
    private val admitted = AtomicLong()
    private val rejected = AtomicLong()
    private val cancellationRequests = AtomicLong()
    private val delivered = AtomicLong()
    private val ended = AtomicLong()
    private val dispatch = ThreadPoolExecutor(
        limits.workers,
        limits.workers,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(limits.maxAdmissions),
        Thread
            .ofVirtual()
            .name("portal-host-dispatch-", 0)
            .inheritInheritableThreadLocals(false)
            .factory(),
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun <T> submit(action: Callable<T>): InvocationHandle<T> {
        val invocation = OwnedPortalInvocation(this, PortalInvocationRetirement(identity, Deadline.afterMillis(limits.callTimeoutMs)), action)
        try {
            synchronized(lock) {
                if (!accepting) throw PortalCallException("Portal operation admission is closed", PortalInvocationEvidence.NOT_STARTED)
                live[invocation] = admission.enter(0, 0)
                admitted.incrementAndGet()
            }
            try {
                dispatch.execute(invocation)
            } catch (rejected: RejectedExecutionException) {
                this.rejected.incrementAndGet()
                invocation.reject(PortalCallException("Portal dispatch stopped before execution", PortalInvocationEvidence.NOT_STARTED, rejected))
            }
        } catch (notAdmitted: PortalCallException) {
            rejected.incrementAndGet()
            invocation.reject(notAdmitted)
        }
        return invocation
    }

    fun finished(invocation: OwnedPortalInvocation<*>) {
        dispatch.remove(invocation)
        synchronized(lock) { live.remove(invocation) }?.close()
    }

    fun diagnostics(): InvocationDiagnostics = InvocationDiagnostics(admitted.get(), rejected.get(), cancellationRequests.get(), delivered.get(), ended.get())

    fun unfinishedInvocations(): Int = synchronized(lock) { live.size }

    fun recordCancellation() {
        cancellationRequests.incrementAndGet()
    }

    fun recordDelivery() {
        delivered.incrementAndGet()
    }

    fun recordEnd() {
        ended.incrementAndGet()
    }

    fun assertExternalClose() {
        check(PortalInvocationRetirement.deadline(identity) == null) { "cannot close a Portal runtime from its own operation mapping" }
    }

    fun stop() {
        assertExternalClose()
        val retained = synchronized(lock) {
            accepting = false
            live.keys.toList()
        }
        retained.forEach { it.requestCancellation() }
        dispatch.shutdown()
    }

    fun await(deadline: Deadline): Boolean =
        try {
        dispatch.awaitTermination(deadline.remainingMillis().toLong(), TimeUnit.MILLISECONDS)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    }
}
