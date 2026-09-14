package io.mazewall.ffi.memory

import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.platform.lifecycle.Tid

/**
 * Shared utility for reading memory from remote processes/threads using process_vm_readv.
 */
internal object SupervisorProcessMemoryReader : TraceeMemoryReader {
    /** Resolve the native adapters at operation start so engine replacement cannot leave a stale delegate. */
    private fun reader(): TraceeMemoryReader = TraceeMemoryReader.real()

    context(arena: NativeArena) public override fun readString(
        tid: Tid,
        remoteAddr: Long,
        maxLen: Int,
    ): String? {
        return try {
            with(reader()) {
                readString(tid, remoteAddr, maxLen)
            }
        } catch (e: IllegalStateException) {
            throw ContainmentViolationException(e.message ?: "Unknown containment violation", e)
        }
    }

    context(arena: NativeArena) public override fun readBytes(
        tid: Tid,
        remoteAddr: Long,
        len: Int,
    ): ByteArray? =
        try {
            with(reader()) { readBytes(tid, remoteAddr, len) }
        } catch (e: IllegalStateException) {
            throw ContainmentViolationException(e.message ?: "Unknown containment violation", e)
        }

    context(arena: NativeArena) public override fun resolveLink(
        tid: Tid,
        link: String,
    ): String? =
        try {
            with(reader()) { resolveLink(tid, link) }
        } catch (e: IllegalStateException) {
            throw ContainmentViolationException(e.message ?: "Unknown containment violation", e)
        }
}
