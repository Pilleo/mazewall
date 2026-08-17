package io.mazewall.profiler.collector

import io.mazewall.profiler.IoUringVisibility
import io.mazewall.profiler.ObservationSource
import io.mazewall.profiler.ProfileObservation

/** Drain of one collector. Sessions merge drains before [io.mazewall.profiler.compiler.BobCompiler]. */
public data class CollectorDrain(
    val observations: List<ProfileObservation>,
    val droppedEvents: Int = 0,
    val drainComplete: Boolean = true,
    val ioUring: IoUringVisibility = IoUringVisibility.UNSEEN,
)

/**
 * Strategy-neutral collector. Implementations must not compile policies themselves.
 */
public interface ProfileCollector : AutoCloseable {
    public val source: ObservationSource
    public fun start()
    public fun drain(): CollectorDrain
    override fun close()
}

/** Merge collector drains. Later sources win on identical correlation+payload; dropped counts add. */
public object ObservationMerger {
    public fun merge(drains: List<CollectorDrain>): CollectorDrain {
        val seen = LinkedHashSet<String>()
        val observations = mutableListOf<ProfileObservation>()
        var dropped = 0
        var drainComplete = true
        var ioUring = IoUringVisibility.UNSEEN
        for (drain in drains) {
            dropped += drain.droppedEvents
            drainComplete = drainComplete && drain.drainComplete
            ioUring = rank(ioUring, drain.ioUring)
            for (obs in drain.observations) {
                val key = keyOf(obs)
                if (seen.add(key)) {
                    observations.add(obs)
                }
            }
        }
        return CollectorDrain(observations, dropped, drainComplete, ioUring)
    }

    private fun keyOf(obs: ProfileObservation): String {
        val corr = "${obs.correlation.tgid}:${obs.correlation.tid.value}:${obs.correlation.ktimeNs}"
        return when (obs) {
            is ProfileObservation.Syscall ->
                "$corr|syscall|${obs.name}|${obs.paths}|${obs.openFlags}"
            is ProfileObservation.IoUring ->
                "$corr|uring|${obs.opcode}|${obs.paths}"
            is ProfileObservation.Connect ->
                "$corr|connect|${obs.endpoint}"
        }
    }

    private fun rank(a: IoUringVisibility, b: IoUringVisibility): IoUringVisibility {
        val order = listOf(
            IoUringVisibility.UNSEEN,
            IoUringVisibility.BLOCKED,
            IoUringVisibility.DISABLED_FOR_HYBRID,
            IoUringVisibility.BLIND,
            IoUringVisibility.OBSERVED,
        )
        return if (order.indexOf(b) > order.indexOf(a)) b else a
    }
}
