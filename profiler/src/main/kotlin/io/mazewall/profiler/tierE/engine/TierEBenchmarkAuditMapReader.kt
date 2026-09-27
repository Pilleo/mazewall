package io.mazewall.profiler.tierE.engine

import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.native.bpf
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** Reads borrowed diagnostic BPF maps; the owning engine retains all file descriptors. */
internal object TierEBenchmarkAuditMapReader {
    private const val BPF_MAP_LOOKUP_ELEM = 1L
    private const val BPF_ATTR_SIZE = 24L
    private const val KEY_POINTER_OFFSET = 8L
    private const val VALUE_POINTER_OFFSET = 16L

    private fun lookup(
        native: RawSyscallOperations,
        arena: Arena,
        mapFd: Int,
        index: Int,
        size: Int,
    ): MemorySegment {
        val key = arena.allocate(ValueLayout.JAVA_INT)
        key.set(ValueLayout.JAVA_INT, 0, index)
        val value = arena.allocate(size.toLong(), Long.SIZE_BYTES.toLong())
        val attr = arena.allocate(BPF_ATTR_SIZE)
        attr.set(ValueLayout.JAVA_INT, 0, mapFd)
        attr.set(ValueLayout.ADDRESS, KEY_POINTER_OFFSET, key)
        attr.set(ValueLayout.ADDRESS, VALUE_POINTER_OFFSET, value)
        when (val result = native.bpf(BPF_MAP_LOOKUP_ELEM, ConfinedSegment(attr), BPF_ATTR_SIZE)) {
            is SyscallResult.Success -> Unit
            is SyscallResult.Error -> result.throwErrno("bpf(BPF_MAP_LOOKUP_ELEM, benchmark audit key=$index)")
        }
        return value
    }

    fun readEarlyTargetOpenat(
        native: RawSyscallOperations,
        mapFd: Int,
    ): TierEbpfEngine.EarlyTargetOpenatCounts =
        Arena.ofConfined().use { arena ->
            val main = lookup(native, arena, mapFd, 0, Long.SIZE_BYTES).get(ValueLayout.JAVA_LONG, 0)
            val raw = lookup(native, arena, mapFd, 1, Long.SIZE_BYTES).get(ValueLayout.JAVA_LONG, 0)
            TierEbpfEngine.EarlyTargetOpenatCounts(main = main, raw = raw)
        }

    fun readRaw(
        native: RawSyscallOperations,
        mapFd: Int,
        descriptors: Set<Int>,
    ): Map<Int, TierEbpfEngine.RawSysEnterAuditCount> =
        Arena.ofConfined().use { arena ->
        descriptors.associateWith { descriptor ->
            val value = lookup(native, arena, mapFd, descriptor, TierEbpfEngine.RawSysEnterAuditCount.VALUE_SIZE)
            val offset = TierEbpfEngine.RawSysEnterAuditCount
            TierEbpfEngine.RawSysEnterAuditCount(
                openat = value.get(ValueLayout.JAVA_LONG, offset.OPENAT_OFFSET.toLong()),
                openat2 = value.get(ValueLayout.JAVA_LONG, offset.OPENAT2_OFFSET.toLong()),
                preSession = value.get(ValueLayout.JAVA_LONG, offset.PRE_SESSION_OFFSET.toLong()),
                noSession = value.get(ValueLayout.JAVA_LONG, offset.NO_SESSION_OFFSET.toLong()),
                wrongTarget = value.get(ValueLayout.JAVA_LONG, offset.WRONG_TARGET_OFFSET.toLong()),
            )
        }
    }

    fun readRawCpu(
        native: RawSyscallOperations,
        mapFd: Int,
        cpus: List<Int>,
    ): Map<Int, Long> =
        Arena.ofConfined().use { arena ->
        cpus
            .associateWith { cpu ->
            lookup(native, arena, mapFd, cpu, Long.SIZE_BYTES).get(ValueLayout.JAVA_LONG, 0)
        }.filterValues { it != 0L }
    }

    fun readExact(
        native: RawSyscallOperations,
        mapFd: Int,
        descriptors: Set<Int>,
    ): Map<Int, TierEbpfEngine.ExactAdmissionAuditCount> =
        Arena.ofConfined().use { arena ->
        descriptors.associateWith { descriptor ->
            val value = lookup(native, arena, mapFd, descriptor, TierEbpfEngine.ExactAdmissionAuditCount.VALUE_SIZE)
            val offset = TierEbpfEngine.ExactAdmissionAuditCount
            TierEbpfEngine.ExactAdmissionAuditCount(
                handlerEntered = value.get(ValueLayout.JAVA_LONG, offset.HANDLER_ENTERED_OFFSET.toLong()),
                sessionMatched = value.get(ValueLayout.JAVA_LONG, offset.SESSION_MATCHED_OFFSET.toLong()),
                inFlightAdmitted = value.get(ValueLayout.JAVA_LONG, offset.IN_FLIGHT_ADMITTED_OFFSET.toLong()),
                markerAttached = value.get(ValueLayout.JAVA_LONG, offset.MARKER_ATTACHED_OFFSET.toLong()),
                taskStorageAvailable = value.get(ValueLayout.JAVA_LONG, offset.TASK_STORAGE_AVAILABLE_OFFSET.toLong()),
                activeInvocation = value.get(ValueLayout.JAVA_LONG, offset.ACTIVE_INVOCATION_OFFSET.toLong()),
                generationValidated = value.get(ValueLayout.JAVA_LONG, offset.GENERATION_VALIDATED_OFFSET.toLong()),
                ringReserved = value.get(ValueLayout.JAVA_LONG, offset.RING_RESERVED_OFFSET.toLong()),
                submitted = value.get(ValueLayout.JAVA_LONG, offset.SUBMITTED_OFFSET.toLong()),
                sessionUnavailable = value.get(ValueLayout.JAVA_LONG, offset.SESSION_UNAVAILABLE_OFFSET.toLong()),
                wrongTarget = value.get(ValueLayout.JAVA_LONG, offset.WRONG_TARGET_OFFSET.toLong()),
                inFlightUnavailable = value.get(ValueLayout.JAVA_LONG, offset.IN_FLIGHT_UNAVAILABLE_OFFSET.toLong()),
                markerDetached = value.get(ValueLayout.JAVA_LONG, offset.MARKER_DETACHED_OFFSET.toLong()),
                markerStateUnavailable = value.get(ValueLayout.JAVA_LONG, offset.MARKER_STATE_UNAVAILABLE_OFFSET.toLong()),
                taskStorageUnavailable = value.get(ValueLayout.JAVA_LONG, offset.TASK_STORAGE_UNAVAILABLE_OFFSET.toLong()),
                inactiveInvocation = value.get(ValueLayout.JAVA_LONG, offset.INACTIVE_INVOCATION_OFFSET.toLong()),
                staleMarkerGeneration = value.get(ValueLayout.JAVA_LONG, offset.STALE_MARKER_GENERATION_OFFSET.toLong()),
                ringReservationFailed = value.get(ValueLayout.JAVA_LONG, offset.RING_RESERVATION_FAILED_OFFSET.toLong()),
                low32HandlerEntered = value.get(ValueLayout.JAVA_LONG, offset.LOW32_HANDLER_ENTERED_OFFSET.toLong()),
            )
        }
    }
}
