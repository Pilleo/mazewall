package io.mazewall.ffi.memory

import io.mazewall.core.Tid
import io.mazewall.enforcer.api.ContainmentViolationException

/**
 * Shared utility for reading memory from remote processes/threads using process_vm_readv.
 */
public object SupervisorProcessMemoryReader : TraceeMemoryReader by TraceeMemoryReader.real() {
    context(arena: NativeArena) public override fun readString(
        tid: Tid,
        remoteAddr: Long,
        maxLen: Int,
    ): String? {
        return try {
            with(TraceeMemoryReader.real()) {
                readString(tid, remoteAddr, maxLen)
            }
        } catch (e: IllegalStateException) {
            throw ContainmentViolationException(e.message ?: "Unknown containment violation", e)
        }
    }
}
