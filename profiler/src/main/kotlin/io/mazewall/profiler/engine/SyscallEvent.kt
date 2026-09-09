package io.mazewall.profiler.engine

import io.mazewall.core.Tid

/**
 * States for the syscall event resolution pipeline.
 */
public sealed interface SyscallEventState {
    /** Raw registers captured from the kernel. */
    public interface Raw : SyscallEventState

    /** Arguments resolved into human-readable strings (e.g. paths). */
    public interface Resolved : SyscallEventState
}

/**
 * A type-safe event representing a trapped system call in various stages of resolution.
 *
 * This class ensures that diagnostic events carry strongly-typed identifiers ([Tid])
 * and maintain immutability for the captured system call arguments and paths.
 * The original public primary constructor retains its named/default arguments. [fromResolution]
 * carries typed evidence through an immutable [paths] projection; passing that projection to
 * the same syscall preserves the evidence. An ordinary path list or a different syscall name
 * selects the conservative legacy adapter. All caller-supplied collections are snapshotted.
 *
 * ### ⚠️ FFM Memory Safety & Lifetime Invariant:
 * To prevent memory segment lifetime leaks, high GC pressure, and native memory leaks,
 * all data from foreign function and memory (FFM) `MemorySegment` objects (such as path strings
 * or raw register structs) **must be fully materialized into JVM heap objects** (e.g., standard
 * [String], [LongArray], [List], etc.) before crossing the [SyscallEvent] boundary into the compiler or logger.
 * No `MemorySegment` references, slices, or views of off-heap memory may escape or be stored within
 * any [SyscallEvent] instance.
 *
 * @param S The resolution state of the event (e.g., [SyscallEventState.Raw], [SyscallEventState.Resolved]).
 * @property tid The Thread ID (LWP) of the process that triggered the system call.
 * @property syscallName The mnemonic name of the system call (e.g., "OPENAT").
 * @property args The raw register arguments captured from the kernel.
 * @property paths The list of resolved filesystem paths extracted from the tracee's memory.
 * @property stackTrace The captured JVM stack trace of the triggering thread, if available.
 */
public class SyscallEvent<out S : SyscallEventState>(
    val tid: Tid,
    val syscallName: String,
    args: List<Long>,
    paths: List<String> = emptyList(),
    stackTrace: List<String>? = null,
) {
    val args: List<Long> = java.util.List.copyOf(args)
    val pathResolution: PathResolution =
        if (paths is SyscallPaths && paths.syscallName == syscallName) paths.resolution else legacyPathResolution(syscallName, paths)
    val paths: List<String> = SyscallPaths(syscallName, pathResolution)
    val stackTrace: List<String>? = stackTrace?.let { java.util.List.copyOf(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SyscallEvent<*>) return false
        if (tid != other.tid) return false
        if (syscallName != other.syscallName) return false
        if (args != other.args) return false
        if (pathResolution != other.pathResolution) return false
        if (stackTrace != other.stackTrace) return false
        return true
    }

    override fun hashCode(): Int {
        var result = tid.hashCode()
        result = 31 * result + syscallName.hashCode()
        result = 31 * result + args.hashCode()
        result = 31 * result + pathResolution.hashCode()
        result = 31 * result + (stackTrace?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "SyscallEvent(tid=$tid, syscallName=$syscallName, args=$args, paths=$paths, stackTrace=$stackTrace, pathResolution=$pathResolution)"
    }

    public companion object {
        public fun <S : SyscallEventState> fromResolution(
            tid: Tid,
            syscallName: String,
            args: List<Long>,
            pathResolution: PathResolution,
            stackTrace: List<String>? = null,
        ): SyscallEvent<S> = SyscallEvent(tid, syscallName, args, SyscallPaths(syscallName, pathResolution), stackTrace)
    }
}

/** Ordinary immutable list semantics with heap-only evidence bound to its operation. */
private class SyscallPaths(
    val syscallName: String,
    val resolution: PathResolution,
) : java.util.AbstractList<String>() {
    override val size: Int get() = resolution.paths.size

    override fun get(index: Int): String = resolution.paths[index]
}

/**
 * Extension function to safely transition a raw syscall event to a resolved state by attaching the resolved paths.
 */
internal fun SyscallEvent<SyscallEventState.Raw>.resolved(paths: List<String>): SyscallEvent<SyscallEventState.Resolved> = SyscallEvent(tid, syscallName, args, paths, stackTrace)

internal fun SyscallEvent<SyscallEventState.Raw>.resolved(resolution: PathResolution): SyscallEvent<SyscallEventState.Resolved> =
    SyscallEvent.fromResolution(tid, syscallName, args, resolution, stackTrace)
