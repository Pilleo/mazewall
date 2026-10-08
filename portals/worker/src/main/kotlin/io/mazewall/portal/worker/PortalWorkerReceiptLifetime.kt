package io.mazewall.portal.worker

import io.mazewall.enforcer.api.InstallationReceipt
import java.util.concurrent.atomic.AtomicReference

/** The real installer receipt follows the physical owner, rather than READY or caller delivery. */
internal interface PortalWorkerReceiptLifetime {
    fun retain(receipt: InstallationReceipt)

    fun executionEnded(receipt: InstallationReceipt)

    /** In-process driver tests/embeddings own only the synchronous execution lifetime. */
    object Execution : PortalWorkerReceiptLifetime {
        override fun retain(receipt: InstallationReceipt) = Unit

        override fun executionEnded(receipt: InstallationReceipt) = receipt.close()
    }
}

/**
 * A guest may create non-daemon JVM threads. Main/request-loop return therefore does not
 * prove process exit, nor does completion of any shutdown hook. The static baseline is
 * irreversible and unsupervised: keep its actual receipt strongly rooted for the complete
 * OS process lifetime. There is deliberately no Java close/clear at shutdown; physical
 * death reclaims the process's native resources, not a fictitious Java retirement event.
 */
internal class PortalWorkerProcessReceiptLifetime : PortalWorkerReceiptLifetime {
    private val receipt = AtomicReference<InstallationReceipt?>()

    companion object {
        private val processReceipts = java.util.concurrent.ConcurrentHashMap
            .newKeySet<InstallationReceipt>()
    }

    override fun retain(receipt: InstallationReceipt) {
        check(!receipt.requestedPolicy.hasSupervisedSyscalls) { "process-lifetime receipt owner requires the unsupervised static baseline" }
        check(this.receipt.compareAndSet(null, receipt)) { "worker process already owns an installation receipt" }
        processReceipts.add(receipt)
    }

    // Neither main failure nor other shutdown work constitutes physical process end.
    override fun executionEnded(receipt: InstallationReceipt) = Unit
}
