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
 * prove process exit. Register before acquisition and retain the exact receipt until JVM
 * shutdown, including startup rejection. Kernel restrictions survive receipt retirement.
 */
internal class PortalWorkerProcessReceiptLifetime(
    registerShutdown: (Thread) -> Unit = { Runtime.getRuntime().addShutdownHook(it) },
) : PortalWorkerReceiptLifetime {
    private val receipt = AtomicReference<InstallationReceipt?>()

    init {
        registerShutdown(
            Thread.ofPlatform().name("portal-worker-receipt-retirement").unstarted {
            receipt.getAndSet(null)?.close()
        },
        )
    }

    override fun retain(receipt: InstallationReceipt) {
        check(this.receipt.compareAndSet(null, receipt)) { "worker process already owns an installation receipt" }
    }

    // The shutdown hook retains ownership even if the main loop or guest initialization fails.
    override fun executionEnded(receipt: InstallationReceipt) = Unit
}
