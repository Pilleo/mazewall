package io.mazewall.ffi.memory

import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.core.Tid

/**
 * Shared utility for reading memory from remote processes/threads using process_vm_readv.
 */
public object SupervisorProcessMemoryReader : TraceeMemoryReader by TraceeMemoryReader.Real {
    context(arena: NativeArena)
    public override fun readString(
        tid: Tid,
        remoteAddr: Long,
        maxLen: Int,
    ): String? {
        return try {
            TraceeMemoryReader.readString(tid, remoteAddr, maxLen)
        } catch (e: IllegalStateException) {
            throw ContainmentViolationException(e.message ?: "Unknown containment violation", e)
        }
    }
}

