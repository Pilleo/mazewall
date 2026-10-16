package io.mazewall.profiler.tierE.engine

import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.native.bpf
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout

/** Kernel program identity is required even when optional runtime statistics are unavailable. */
internal object TierEBpfProgramInfoReader {
    private const val BPF_OBJ_GET_INFO_BY_FD = 15L

    fun read(
        native: RawSyscallOperations,
        fd: Int?,
    ): TierEbpfEngine.BpfProgramExecutionEvidence {
        if (fd == null) return TierEbpfEngine.BpfProgramExecutionEvidence.NotAttached
        return Arena.ofConfined().use { arena ->
            val info = arena.allocate(BpfProgramInfoLayout.SIZE, Long.SIZE_BYTES.toLong())
            val attr = arena.allocate(BpfObjGetInfoLayout.SIZE, Long.SIZE_BYTES.toLong())
            attr.set(ValueLayout.JAVA_INT, BpfObjGetInfoLayout.BPF_FD, fd)
            attr.set(ValueLayout.JAVA_INT, BpfObjGetInfoLayout.INFO_LEN, BpfProgramInfoLayout.SIZE.toInt())
            attr.set(ValueLayout.ADDRESS, BpfObjGetInfoLayout.INFO, info)
            when (val result = native.bpf(BPF_OBJ_GET_INFO_BY_FD, ConfinedSegment(attr), BpfObjGetInfoLayout.SIZE)) {
                is SyscallResult.Success -> Unit
                is SyscallResult.Error -> result.throwErrno("bpf(BPF_OBJ_GET_INFO_BY_FD, Tier E program identity)")
            }
            val available = attr.get(ValueLayout.JAVA_INT, BpfObjGetInfoLayout.INFO_LEN)
            val runCount = if (available >= BpfProgramInfoLayout.RUN_COUNT + Long.SIZE_BYTES) {
                info.get(ValueLayout.JAVA_LONG, BpfProgramInfoLayout.RUN_COUNT)
            } else {
                null
            }
            val runTime = if (available >= BpfProgramInfoLayout.RUN_TIME_NANOS + Long.SIZE_BYTES) {
                info.get(ValueLayout.JAVA_LONG, BpfProgramInfoLayout.RUN_TIME_NANOS)
            } else {
                null
            }
            val recursionMisses = if (available >= BpfProgramInfoLayout.RECURSION_MISSES + Long.SIZE_BYTES) {
                info.get(ValueLayout.JAVA_LONG, BpfProgramInfoLayout.RECURSION_MISSES)
            } else {
                null
            }
            val observed = TierEInstalledProgramIdentity.kernelInfo(
                available,
                info.get(ValueLayout.JAVA_INT, BpfProgramInfoLayout.ID),
                info.get(ValueLayout.JAVA_INT, BpfProgramInfoLayout.XLATED_PROGRAM_LENGTH),
                runCount,
                runTime,
                recursionMisses,
            )
            if (observed.runtimeUnavailable) {
                TierEbpfEngine.BpfProgramExecutionEvidence.IdentityOnly(
                    observed.programId,
                    observed.translatedInstructionBytes,
                    "kernel BPF runtime statistics are unavailable",
                    observed.recursionMisses,
                )
            } else {
                TierEbpfEngine.BpfProgramExecutionEvidence.Available(
                    observed.programId,
                    observed.translatedInstructionBytes,
                    observed.runCount,
                    observed.runTimeNanos,
                    observed.recursionMisses,
                )
            }
        }
    }
}
