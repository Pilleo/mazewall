package io.mazewall.profiler

import io.mazewall.core.Tid
import io.mazewall.profiler.engine.TraceEvent

/** Which collector produced an observation. Strategies are not interchangeable. */
public enum class ObservationSource {
    USER_NOTIF,
    STRACE,
    EBPF,
}

/** Correlation key for merging collectors (eBPF + USER_NOTIF) later. */
public data class ObservationCorrelation(
    val tgid: Int,
    val tid: Tid,
    val ktimeNs: Long? = null,
)

/**
 * Strategy-neutral kernel observation. [TraceEvent] remains the USER_NOTIF wire shape;
 * collectors translate into this type before [io.mazewall.profiler.compiler.BobCompiler].
 */
public sealed interface ProfileObservation {
    public val correlation: ObservationCorrelation
    public val source: ObservationSource
    public val paths: List<String>

    public data class Syscall(
        override val correlation: ObservationCorrelation,
        override val source: ObservationSource,
        val name: String,
        val args: List<Long> = emptyList(),
        override val paths: List<String> = emptyList(),
        val openFlags: Long? = null,
        /** Tier E semantic context; null for unattributed observations. */
        val contextId: Long? = null,
    ) : ProfileObservation

    public data class IoUring(
        override val correlation: ObservationCorrelation,
        override val source: ObservationSource,
        val opcode: String,
        override val paths: List<String> = emptyList(),
    ) : ProfileObservation

    public data class Connect(
        override val correlation: ObservationCorrelation,
        override val source: ObservationSource,
        val endpoint: NetworkEndpoint,
        override val paths: List<String> = emptyList(),
    ) : ProfileObservation

    public companion object {
        public fun fromTraceEvent(
            event: TraceEvent,
            source: ObservationSource = ObservationSource.USER_NOTIF,
            tgid: Int = 0,
        ): ProfileObservation {
            val corr = ObservationCorrelation(tgid = tgid, tid = event.tid)
            val name = event.syscallName.uppercase()
            return when (event) {
                is TraceEvent.Open ->
                    Syscall(corr, source, name, emptyList(), event.paths, event.flags)
                is TraceEvent.Exec ->
                    Syscall(corr, source, name, emptyList(), event.paths)
                is TraceEvent.FsMutation ->
                    Syscall(corr, source, name, emptyList(), event.paths)
                is TraceEvent.Socket ->
                    Syscall(corr, source, "SOCKET", listOf(event.domain.toLong(), event.type.toLong(), event.protocol.toLong()))
                is TraceEvent.Mmap ->
                    Syscall(corr, source, "MMAP", emptyList())
                is TraceEvent.Generic ->
                    Syscall(corr, source, name, event.args, event.paths, openFlagsFrom(name, event.args))
            }
        }

        private fun openFlagsFrom(name: String, args: List<Long>): Long? {
            return when (name) {
                "OPEN" -> args.getOrNull(1)
                "OPENAT" -> args.getOrNull(2)
                else -> null
            }
        }
    }
}
