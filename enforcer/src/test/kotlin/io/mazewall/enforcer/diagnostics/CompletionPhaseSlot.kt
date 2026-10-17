package io.mazewall.enforcer.diagnostics

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// Public only in test outputs so the separately compiled integration fixture can consume them.
enum class CompletionPhase {
    CREATED,
    INSTALLING,
    INSTALL_RETURNED,
    INSTALLED,
    SCOPE_CLOSED,
    RETURNING,
    COMPLETED,
    WORKER_RETIRED,
}

data class CompletionSample(
    val workerId: Long,
    val nativeTid: Int,
    val phase: CompletionPhase,
)

class CompletionPhaseSlot {
    private val worker = AtomicLong()
    private val tid = AtomicInteger()
    private val phase = AtomicInteger()

    fun bind(
        workerId: Long,
        nativeTid: Int,
    ) {
        require(workerId > 0 && nativeTid > 0)
        check(worker.compareAndSet(0, workerId))
        tid.set(nativeTid)
    }

    fun advance(next: CompletionPhase) {
        val old = phase.get()
        require(next.ordinal >= old)
        phase.set(next.ordinal)
    }

    fun read(): CompletionSample {
        val current = phase.get()
        return CompletionSample(worker.get(), tid.get(), CompletionPhase.entries[current])
    }
}
