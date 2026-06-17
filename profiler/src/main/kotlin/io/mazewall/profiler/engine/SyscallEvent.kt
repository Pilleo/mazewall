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
 *
 * @param S The resolution state of the event (e.g., [SyscallEventState.Raw], [SyscallEventState.Resolved]).
 * @property tid The Thread ID (LWP) of the process that triggered the system call.
 * @property syscallName The mnemonic name of the system call (e.g., "OPENAT").
 * @property args The raw register arguments captured from the kernel.
 * @property paths The list of resolved filesystem paths extracted from the tracee's memory.
 * @property stackTrace The captured JVM stack trace of the triggering thread, if available.
 */
public data class SyscallEvent<out S : SyscallEventState>(
    val tid: Tid,
    val syscallName: String,
    val args: LongArray,
    val paths: List<String> = emptySet<String>().toList(),
    val stackTrace: List<String>? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SyscallEvent<*>) return false
        if (tid != other.tid) return false
        if (syscallName != other.syscallName) return false
        if (!args.contentEquals(other.args)) return false
        if (paths != other.paths) return false
        if (stackTrace != other.stackTrace) return false
        return true
    }

    override fun hashCode(): Int {
        var result = tid.hashCode()
        result = 31 * result + syscallName.hashCode()
        result = 31 * result + args.contentHashCode()
        result = 31 * result + paths.hashCode()
        result = 31 * result + (stackTrace?.hashCode() ?: 0)
        return result
    }
}

/**
 * Extension function to safely transition a raw syscall event to a resolved state by attaching the resolved paths.
 */
internal fun SyscallEvent<SyscallEventState.Raw>.resolved(paths: List<String>): SyscallEvent<SyscallEventState.Resolved> =
    SyscallEvent(tid, syscallName, args, paths, stackTrace)
