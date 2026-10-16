package io.mazewall.profiler.tierE.engine

import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.native.asBpf
import io.mazewall.platform.native.openDescriptor
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout

/** Owns only kernel-validated BPF map descriptors; the engine retains and closes them. */
internal object TierEBpfMapAllocator {
    private const val BPF_MAP_CREATE = 0L
    private const val ATTR_SIZE = 72L
    private const val TYPE_OFFSET = 0L
    private const val KEY_SIZE_OFFSET = 4L
    private const val VALUE_SIZE_OFFSET = 8L
    private const val MAX_ENTRIES_OFFSET = 12L

    fun create(
        native: RawSyscallOperations,
        type: Int,
        keySize: Int,
        valueSize: Int,
        maxEntries: Int,
        mapFlags: Int,
        btfFd: Int,
        btfKeyTypeId: Int,
        btfValueTypeId: Int,
    ): FileDescriptor<FileDescriptorRole.Bpf, FdState.Open, FdOwnership.Owned> =
        Arena.ofConfined().use { arena ->
            val attr = arena.allocate(ATTR_SIZE)
            attr.set(ValueLayout.JAVA_INT, TYPE_OFFSET, type)
            attr.set(ValueLayout.JAVA_INT, KEY_SIZE_OFFSET, keySize)
            attr.set(ValueLayout.JAVA_INT, VALUE_SIZE_OFFSET, valueSize)
            attr.set(ValueLayout.JAVA_INT, MAX_ENTRIES_OFFSET, maxEntries)
            attr.set(ValueLayout.JAVA_INT, BpfMapCreateLayout.MAP_FLAGS, mapFlags)
            attr.set(ValueLayout.JAVA_INT, BpfMapCreateLayout.BTF_FD, btfFd)
            attr.set(ValueLayout.JAVA_INT, BpfMapCreateLayout.BTF_KEY_TYPE_ID, btfKeyTypeId)
            attr.set(ValueLayout.JAVA_INT, BpfMapCreateLayout.BTF_VALUE_TYPE_ID, btfValueTypeId)
            native.asBpf().openDescriptor(BPF_MAP_CREATE, ConfinedSegment(attr), ATTR_SIZE, "bpf(BPF_MAP_CREATE)")
        }
}
