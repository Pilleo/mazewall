package io.mazewall.profiler.tierE.engine

import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.native.bpf
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** Configures only declared descriptor slots before the shared observation arm is opened. */
internal object TierEPreGateAuditMap {
    private const val BPF_MAP_UPDATE_ELEM = 2L
    private const val BPF_ATTR_SIZE = 32L
    private const val KEY_POINTER_OFFSET = 8L
    private const val VALUE_POINTER_OFFSET = 16L
    private const val SHARED_ARM_KEY = 2

    private fun update(
        native: RawSyscallOperations,
        arena: Arena,
        mapFd: Int,
        key: MemorySegment,
        value: MemorySegment,
        label: String,
    ) {
        val attr = arena.allocate(BPF_ATTR_SIZE)
        attr.set(ValueLayout.JAVA_INT, 0, mapFd)
        attr.set(ValueLayout.ADDRESS, KEY_POINTER_OFFSET, key)
        attr.set(ValueLayout.ADDRESS, VALUE_POINTER_OFFSET, value)
        when (val result = native.bpf(BPF_MAP_UPDATE_ELEM, ConfinedSegment(attr), BPF_ATTR_SIZE)) {
            is SyscallResult.Success -> Unit
            is SyscallResult.Error -> result.throwErrno("bpf(BPF_MAP_UPDATE_ELEM, $label)")
        }
    }

    fun arm(
        native: RawSyscallOperations,
        mapFd: Int,
    ) {
        Arena.ofConfined().use { arena ->
            val key = arena.allocate(ValueLayout.JAVA_INT)
            key.set(ValueLayout.JAVA_INT, 0, SHARED_ARM_KEY)
            val value = arena.allocate(ValueLayout.JAVA_LONG)
            value.set(ValueLayout.JAVA_LONG, 0, 1L)
            update(native, arena, mapFd, key, value, "shared pre-gate arm")
        }
    }

    fun configure(
        native: RawSyscallOperations,
        mapFd: Int,
        descriptors: Set<Int>,
    ) {
        require(descriptors.all { it in 0 until TierEbpfEngine.BENCHMARK_AUDIT_MAX_DESCRIPTORS }) {
            "pre-gate descriptor key exceeds the benchmark audit array bound"
        }
        Arena.ofConfined().use { arena ->
            descriptors.forEach { descriptor ->
                val key = arena.allocate(ValueLayout.JAVA_INT)
                key.set(ValueLayout.JAVA_INT, 0, descriptor)
                val value = arena.allocate(TierEPreGateAuditCount.VALUE_SIZE.toLong(), Long.SIZE_BYTES.toLong())
                repeat(TierEPreGateAuditCount.VALUE_SIZE / Long.SIZE_BYTES) { index ->
                    value.set(ValueLayout.JAVA_LONG, index.toLong() * Long.SIZE_BYTES, 0L)
                }
                value.set(ValueLayout.JAVA_LONG, TierEPreGateAuditCount.DECLARED_OFFSET.toLong(), 1L)
                update(native, arena, mapFd, key, value, "pre-gate fd=$descriptor")
            }
        }
    }
}
