package io.mazewall.profiler.collector

import io.mazewall.core.Tid
import io.mazewall.profiler.ObservationCorrelation
import io.mazewall.profiler.ObservationSource
import io.mazewall.profiler.ProfileObservation

/**
 * Live eBPF collector backed by the Tier E uprobe → task-storage pipeline.
 *
 * Consumes attributed syscall events from the Tier E daemon's ring buffer
 * and translates them into [ProfileObservation.Syscall] objects with
 * context enrichment. Implements strategy-neutral [ProfileCollector];
 * never compiles policies (invariant 8). Drop accounting flows into
 * [CollectorDrain.droppedEvents]; any drop marks drain incomplete.
 *
 * Events are fed via [onEvent] from the Tier E daemon's consumer thread.
 */
public class LiveEbpfCollector : ProfileCollector {
    override val source: ObservationSource = ObservationSource.EBPF

    private val observations = mutableListOf<ProfileObservation.Syscall>()
    private var _droppedEvents: Int = 0
    private var started = false
    private var drainComplete = true
    private val lock = Any()

    /**
     * Records an attributed event from the Tier E ring buffer.
     * Called by the daemon's consumer thread for each committed record.
     */
    public fun onEvent(
        tgid: Int,
        tid: Int,
        syscallNr: Int,
        contextId: Long,
        ktimeNs: Long,
    ) {
        synchronized(lock) {
            val corr = ObservationCorrelation(
                tgid = tgid,
                tid = Tid(tid),
                ktimeNs = ktimeNs,
            )
            observations.add(
                ProfileObservation.Syscall(
                    correlation = corr,
                    source = ObservationSource.EBPF,
                    name = syscallName(syscallNr),
                    contextId = contextId,
                ),
            )
        }
    }

    /** Records a ring-buffer drop (producer-side reservation failure). */
    public fun onDrop() {
        synchronized(lock) {
            _droppedEvents++
            drainComplete = false
        }
    }

    override fun start() {
        started = true
    }

    override fun drain(): CollectorDrain = synchronized(lock) {
        return CollectorDrain(
            observations = observations.toList(),
            droppedEvents = _droppedEvents,
            drainComplete = drainComplete,
        )
    }

    override fun close() {
        synchronized(lock) {
            observations.clear()
            _droppedEvents = 0
            drainComplete = true
        }
    }

    public companion object {
        public fun syscallName(nr: Int): String = when (nr) {
            0 -> "READ"
            1 -> "WRITE"
            3 -> "CLOSE"
            39 -> "GETPID"
            56 -> "CLONE"
            202 -> "FUTEX"
            230 -> "CLOCK_NANOSLEEP"
            232 -> "EPOLL_WAIT"
            257 -> "OPENAT"
            else -> "SYSCALL_$nr"
        }
    }
}
