package io.mazewall.portal

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Logical admission spans checkout and RPC delivery; physical worker retirement stays broker-owned. */
internal class PortalCallAdmission(
    private val limits: PortalRuntimeLimits,
) {
    private val capacity = Semaphore(limits.maxAdmissions)

    fun enter(
        payloadBytes: Int,
        capabilities: Int,
    ): AutoCloseable {
        if (payloadBytes !in 0..limits.maxRequestBytes || capabilities !in 0..limits.maxCapabilities) {
            throw PortalCallException("Portal request exceeds configured payload/capability bounds", PortalInvocationEvidence.NOT_STARTED)
        }
        if (!capacity.tryAcquire()) throw PortalCallException("Portal admission limit reached", PortalInvocationEvidence.NOT_STARTED)
        val released = AtomicBoolean()
        return AutoCloseable { if (released.compareAndSet(false, true)) capacity.release() }
    }
}
