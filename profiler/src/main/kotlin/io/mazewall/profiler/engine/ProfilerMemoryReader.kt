package io.mazewall.profiler.engine

import io.mazewall.core.Tid
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.TraceeMemoryReader

/**
 * Interface for reading memory and resolving paths from a tracee process or thread.
 */
public interface ProfilerMemoryReader : TraceeMemoryReader {
    context(arena: NativeArena)
    public fun readStringFromProcess(
        tid: Tid,
        remoteAddr: Long,
        maxLen: Int = 4096,
    ): String? = try {
        readString(tid, remoteAddr, maxLen, warnOnEperm = true)
    } catch (e: IllegalStateException) {
        throw io.mazewall.enforcer.api.ContainmentViolationException(e.message ?: "Unknown containment violation", e)
    }

    context(arena: NativeArena)
    override fun readString(
        tid: Tid,
        remoteAddr: Long,
        maxLen: Int,
        warnOnEperm: Boolean
    ): String? = TraceeMemoryReader.Real.readString(tid, remoteAddr, maxLen, warnOnEperm)

    context(arena: NativeArena)
    override fun readBytes(
        tid: Tid,
        remoteAddr: Long,
        len: Int,
        warnOnEperm: Boolean
    ): ByteArray? = TraceeMemoryReader.readBytes(tid, remoteAddr, len, warnOnEperm)

    context(arena: NativeArena)
    override fun resolveLink(
        tid: Tid,
        link: String
    ): String? = TraceeMemoryReader.resolveLink(tid, link)
}


/**
 * Real implementation of [ProfilerMemoryReader] using process_vm_readv and readlink.
 */
public object RealMemoryReader : ProfilerMemoryReader, TraceeMemoryReader by TraceeMemoryReader.Real

