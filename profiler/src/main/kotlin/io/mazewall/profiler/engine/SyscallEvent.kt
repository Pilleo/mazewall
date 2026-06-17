package io.mazewall.profiler.engine

import io.mazewall.core.Pid

/**
 * States for the syscall event resolution pipeline.
 */
public sealed interface SyscallEventState {
    /** Raw registers captured from the kernel. */
    public interface Raw : SyscallEventState

    /** Path arguments resolved from tracee memory. */
    public interface Resolved : SyscallEventState
}

/**
 * A type-safe event representing a trapped system call in various stages of resolution.
 */
public data class SyscallEvent<out S : SyscallEventState>(
    val pid: Pid,
    val syscallName: String,
    val args: LongArray,
    val paths: List<String> = emptyList(),
    val stackTrace: List<String>? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SyscallEvent<*>) return false
        if (pid != other.pid) return false
        if (syscallName != other.syscallName) return false
        if (!args.contentEquals(other.args)) return false
        if (paths != other.paths) return false
        if (stackTrace != other.stackTrace) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pid.hashCode()
        result = 31 * result + syscallName.hashCode()
        result = 31 * result + args.contentHashCode()
        result = 31 * result + paths.hashCode()
        result = 31 * result + (stackTrace?.hashCode() ?: 0)
        return result
    }

    /**
     * Converts this resolved event to the legacy TraceEvent format for wire-compatibility.
     */
    fun toTraceEvent(): TraceEvent {
        return TraceEvent(pid.value, syscallName, args, paths, stackTrace)
    }

    public companion object {
        /**
         * Wraps a legacy TraceEvent into a type-safe Resolved SyscallEvent.
         */
        fun fromTraceEvent(event: TraceEvent): SyscallEvent<SyscallEventState.Resolved> {
            return SyscallEvent(
                pid = Pid(event.pid),
                syscallName = event.syscallName,
                args = event.args,
                paths = event.paths,
                stackTrace = event.stackTrace
            )
        }
    }
}

/**
 * Transitions a raw event to a resolved state by attaching the resolved paths.
 */
internal fun SyscallEvent<SyscallEventState.Raw>.resolved(paths: List<String>): SyscallEvent<SyscallEventState.Resolved> =
    SyscallEvent(pid, syscallName, args, paths, stackTrace)
