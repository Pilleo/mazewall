package io.mazewall.profiler

import io.mazewall.core.Tid
import io.mazewall.profiler.engine.PathResolution
import io.mazewall.profiler.engine.SyscallEvent
import io.mazewall.profiler.engine.SyscallEventState
import io.mazewall.profiler.engine.TraceEvent
import io.mazewall.profiler.engine.legacyPathResolution

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
 *
 * The original data-class constructors and generated copies retain caller-supplied lists.
 * Event factories and [ProfilingResult] publish immutable snapshots. Generated copies preserve
 * typed evidence while retaining the paths projection and its syscall name or io_uring opcode;
 * explicitly replacing paths with an ordinary list uses the legacy resolution adapter.
 */
public sealed interface ProfileObservation {
    public val correlation: ObservationCorrelation
    public val source: ObservationSource
    public val paths: List<String>
    public val pathResolution: PathResolution

    public data class Syscall(
        override val correlation: ObservationCorrelation,
        override val source: ObservationSource,
        val name: String,
        val args: List<Long> = emptyList(),
        override val paths: List<String> = emptyList(),
        val openFlags: Long? = null,
        /** Tier E semantic context; null for unattributed observations. */
        val contextId: Long? = null,
    ) : ProfileObservation {
        override val pathResolution: PathResolution
            get() = paths.resolutionFor("syscall:$name") ?: legacyPathResolution(name, paths)

        internal fun withPathResolution(resolution: PathResolution): Syscall = copy(args = java.util.List.copyOf(args), paths = ObservationPaths("syscall:$name", resolution))

        override fun equals(other: Any?): Boolean =
            other is Syscall &&
                correlation == other.correlation &&
                source == other.source &&
                name == other.name &&
                args == other.args &&
                pathResolution == other.pathResolution &&
                openFlags == other.openFlags &&
                contextId == other.contextId

        override fun hashCode(): Int {
            var result = correlation.hashCode()
            result = 31 * result + source.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + args.hashCode()
            result = 31 * result + pathResolution.hashCode()
            result = 31 * result + (openFlags?.hashCode() ?: 0)
            result = 31 * result + (contextId?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "Syscall(correlation=$correlation, source=$source, name=$name, args=$args, paths=$paths, openFlags=$openFlags, contextId=$contextId, pathResolution=$pathResolution)"
    }

    public data class IoUring(
        override val correlation: ObservationCorrelation,
        override val source: ObservationSource,
        val opcode: String,
        override val paths: List<String> = emptyList(),
    ) : ProfileObservation {
        override val pathResolution: PathResolution
            get() = paths.resolutionFor("uring:$opcode") ?: legacyPathResolution(opcode, paths, if (UringOp.parse(opcode).isPathBearing()) 1 else 0)

        internal fun withPathResolution(resolution: PathResolution): IoUring = copy(paths = ObservationPaths("uring:$opcode", resolution))

        override fun equals(other: Any?): Boolean = other is IoUring && correlation == other.correlation && source == other.source && opcode == other.opcode && pathResolution == other.pathResolution

        override fun hashCode(): Int {
            var result = correlation.hashCode()
            result = 31 * result + source.hashCode()
            result = 31 * result + opcode.hashCode()
            result = 31 * result + pathResolution.hashCode()
            return result
        }

        override fun toString(): String = "IoUring(correlation=$correlation, source=$source, opcode=$opcode, paths=$paths, pathResolution=$pathResolution)"
    }

    public data class Connect(
        override val correlation: ObservationCorrelation,
        override val source: ObservationSource,
        val endpoint: NetworkEndpoint,
        override val paths: List<String> = emptyList(),
    ) : ProfileObservation {
        override val pathResolution: PathResolution
            get() = paths.resolutionFor("connect") ?: legacyPathResolution("CONNECT", paths, 0)

        internal fun withPathResolution(resolution: PathResolution): Connect = copy(paths = ObservationPaths("connect", resolution))

        override fun equals(other: Any?): Boolean =
            other is Connect && correlation == other.correlation && source == other.source && endpoint == other.endpoint && pathResolution == other.pathResolution

        override fun hashCode(): Int {
            var result = correlation.hashCode()
            result = 31 * result + source.hashCode()
            result = 31 * result + endpoint.hashCode()
            result = 31 * result + pathResolution.hashCode()
            return result
        }

        override fun toString(): String = "Connect(correlation=$correlation, source=$source, endpoint=$endpoint, paths=$paths, pathResolution=$pathResolution)"
    }

    public companion object {
        public fun fromSyscallEvent(
            event: SyscallEvent<SyscallEventState.Resolved>,
            source: ObservationSource = ObservationSource.USER_NOTIF,
            tgid: Int = 0,
        ): ProfileObservation = fromTraceEvent(TraceEvent.fromSyscallEvent(event), source, tgid)

        public fun fromTraceEvent(
            event: TraceEvent,
            source: ObservationSource = ObservationSource.USER_NOTIF,
            tgid: Int = 0,
        ): ProfileObservation {
            val corr = ObservationCorrelation(tgid = tgid, tid = event.tid)
            val name = event.syscallName.uppercase()
            val observation = when (event) {
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
            return observation.withPathResolution(event.pathResolution)
        }

        private fun openFlagsFrom(
            name: String,
            args: List<Long>,
        ): Long? {
            return when (name) {
                "OPEN" -> args.getOrNull(1)
                "OPENAT" -> args.getOrNull(2)
                else -> null
            }
        }
    }
}

/**
 * An immutable evidence record carried by the original data-class paths component.
 * Generated copies retain it unless callers replace paths or change the operation.
 * List equality stays structural; observation equality explicitly includes the resolution.
 */
private class ObservationPaths(
    val operation: String,
    val resolution: PathResolution,
) : java.util.AbstractList<String>() {
    override val size: Int get() = resolution.paths.size

    override fun get(index: Int): String = resolution.paths[index]
}

private fun List<String>.resolutionFor(operation: String): PathResolution? = (this as? ObservationPaths)?.takeIf { it.operation == operation }?.resolution
