package io.mazewall.profiler.internal.engine
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.TraceeMemoryReader
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.profiler.api.PathResolution
import io.mazewall.profiler.api.PathResolutionCause
import io.mazewall.profiler.api.PathResolutionFailure
import io.mazewall.profiler.api.SyscallEvent
import io.mazewall.profiler.api.SyscallEventState
import io.mazewall.profiler.api.combinePathResolution

/**
 * Interface for reading memory and resolving paths from a tracee process or thread.
 */
internal interface ProfilerMemoryReader : TraceeMemoryReader {
    /** Adds typed evidence while honoring existing custom string-reader implementations. */
    context(arena: NativeArena) public fun readPathString(
        tid: Tid,
        remoteAddr: Long,
        maxLen: Int = 4096,
    ): PathStringRead =
        try {
            readStringFromProcess(tid, remoteAddr, maxLen)?.let(PathStringRead::Text) ?: PathStringRead.Missing
        } catch (failure: io.mazewall.enforcer.api.ContainmentViolationException) {
            val cause = failure.cause
            if (cause is TraceeMemoryReader.TruncatedStringException) {
                PathStringRead.Truncated(cause.preview, cause.bytesRead)
            } else {
                PathStringRead.Denied(PathResolutionCause.from(cause ?: failure))
            }
        }

    context(arena: NativeArena) public fun readStringFromProcess(
        tid: Tid,
        remoteAddr: Long,
        maxLen: Int = 4096,
    ): String? =
        try {
        readString(tid, remoteAddr, maxLen)
    } catch (cancellation: java.util.concurrent.CancellationException) {
        throw cancellation
    } catch (e: IllegalStateException) {
        throw io.mazewall.enforcer.api
            .ContainmentViolationException(e.message ?: "Unknown containment violation", e)
    }

    context(arena: NativeArena) override fun readString(
        tid: Tid,
        remoteAddr: Long,
        maxLen: Int,
    ): String? = with(TraceeMemoryReader.real()) { readString(tid, remoteAddr, maxLen) }

    context(arena: NativeArena) override fun readBytes(
        tid: Tid,
        remoteAddr: Long,
        len: Int,
    ): ByteArray? = with(TraceeMemoryReader.real()) { readBytes(tid, remoteAddr, len) }

    context(arena: NativeArena) override fun resolveLink(
        tid: Tid,
        link: String,
    ): String? = with(TraceeMemoryReader.real()) { resolveLink(tid, link) }
}

/**
 * Real implementation of [ProfilerMemoryReader] using process_vm_readv and readlink.
 */
internal object RealMemoryReader : ProfilerMemoryReader
