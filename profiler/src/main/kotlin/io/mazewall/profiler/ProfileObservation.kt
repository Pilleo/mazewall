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
 */
public sealed interface ProfileObservation {
    public val correlation: ObservationCorrelation
    public val source: ObservationSource
    public val paths: List<String>
    public val pathResolution: PathResolution

    public class Syscall private constructor(
        override val correlation: ObservationCorrelation,
        override val source: ObservationSource,
        val name: String,
        args: List<Long>,
        override val pathResolution: PathResolution,
        val openFlags: Long?,
        val contextId: Long?,
    ) : ProfileObservation {
        public constructor(
            correlation: ObservationCorrelation,
            source: ObservationSource,
            name: String,
            args: List<Long> = emptyList(),
            paths: List<String> = emptyList(),
            openFlags: Long? = null,
            contextId: Long? = null,
        ) : this(correlation, source, name, args, legacyPathResolution(name, paths), openFlags, contextId)

        val args: List<Long> = java.util.List.copyOf(args)
        override val paths: List<String> get() = pathResolution.paths

        public fun copy(
            correlation: ObservationCorrelation = this.correlation,
            source: ObservationSource = this.source,
            name: String = this.name,
            args: List<Long> = this.args,
            paths: List<String> = this.paths,
            openFlags: Long? = this.openFlags,
            contextId: Long? = this.contextId,
        ): Syscall = Syscall(correlation, source, name, args, if (name == this.name && paths == this.paths) pathResolution else legacyPathResolution(name, paths), openFlags, contextId)

        internal fun withPathResolution(resolution: PathResolution): Syscall = Syscall(correlation, source, name, args, resolution, openFlags, contextId)

        public operator fun component1(): ObservationCorrelation = correlation

        public operator fun component2(): ObservationSource = source

        public operator fun component3(): String = name

        public operator fun component4(): List<Long> = args

        public operator fun component5(): List<String> = paths

        public operator fun component6(): Long? = openFlags

        public operator fun component7(): Long? = contextId

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

    public class IoUring private constructor(
        override val correlation: ObservationCorrelation,
        override val source: ObservationSource,
        val opcode: String,
        override val pathResolution: PathResolution,
    ) : ProfileObservation {
        public constructor(
            correlation: ObservationCorrelation,
            source: ObservationSource,
            opcode: String,
            paths: List<String> = emptyList(),
        ) : this(correlation, source, opcode, legacyPathResolution(opcode, paths, if (UringOp.parse(opcode).isPathBearing()) 1 else 0))

        override val paths: List<String> get() = pathResolution.paths

        public fun copy(
            correlation: ObservationCorrelation = this.correlation,
            source: ObservationSource = this.source,
            opcode: String = this.opcode,
            paths: List<String> = this.paths,
        ): IoUring =
            IoUring(
            correlation,
                source,
                opcode,
            if (opcode == this.opcode &&
            paths == this.paths
            ) {
                pathResolution
            } else {
                legacyPathResolution(opcode, paths, if (UringOp.parse(opcode).isPathBearing()) 1 else 0)
            },
        )

        internal fun withPathResolution(resolution: PathResolution): IoUring = IoUring(correlation, source, opcode, resolution)

        public operator fun component1(): ObservationCorrelation = correlation

        public operator fun component2(): ObservationSource = source

        public operator fun component3(): String = opcode

        public operator fun component4(): List<String> = paths

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

    public class Connect private constructor(
        override val correlation: ObservationCorrelation,
        override val source: ObservationSource,
        val endpoint: NetworkEndpoint,
        override val pathResolution: PathResolution,
    ) : ProfileObservation {
        public constructor(
            correlation: ObservationCorrelation,
            source: ObservationSource,
            endpoint: NetworkEndpoint,
            paths: List<String> = emptyList(),
        ) : this(correlation, source, endpoint, legacyPathResolution("CONNECT", paths, 0))

        override val paths: List<String> get() = pathResolution.paths

        public fun copy(
            correlation: ObservationCorrelation = this.correlation,
            source: ObservationSource = this.source,
            endpoint: NetworkEndpoint = this.endpoint,
            paths: List<String> = this.paths,
        ): Connect = Connect(correlation, source, endpoint, if (paths == this.paths) pathResolution else legacyPathResolution("CONNECT", paths, 0))

        internal fun withPathResolution(resolution: PathResolution): Connect = Connect(correlation, source, endpoint, resolution)

        public operator fun component1(): ObservationCorrelation = correlation

        public operator fun component2(): ObservationSource = source

        public operator fun component3(): NetworkEndpoint = endpoint

        public operator fun component4(): List<String> = paths

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
