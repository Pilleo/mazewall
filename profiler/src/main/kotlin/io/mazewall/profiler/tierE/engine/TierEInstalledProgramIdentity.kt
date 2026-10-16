package io.mazewall.profiler.tierE.engine

import io.mazewall.profiler.internal.attribution.TierESysEnterAttachmentBackend
import java.lang.foreign.MemorySegment
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.HexFormat

/** Snapshot of the exact map-FD-substituted instruction bytes submitted to BPF_PROG_LOAD. */
internal data class TierEInstalledProgramIdentity(
    val programName: String,
    val backend: TierESysEnterAttachmentBackend,
    val instructionCount: Int,
    val lookupHelperCalls: Int,
    val updateHelperCalls: Int,
    val sha256: String,
) {
    internal data class KernelInfo(
        val programId: Int,
        val translatedInstructionBytes: Int,
        val runCount: Long?,
        val runTimeNanos: Long?,
        val recursionMisses: Long?,
    ) {
        val runtimeUnavailable: Boolean get() = runCount == null || runTimeNanos == null
    }

    internal companion object {
        private const val INSN_BYTES = 8L
        private const val OPCODE_MASK = 0xff
        private const val IMMEDIATE_OFFSET = 4
        private const val BPF_CALL = 0x85
        private const val BPF_MAP_LOOKUP_ELEM = 1
        private const val BPF_MAP_UPDATE_ELEM = 2

        fun from(
            name: String,
            backend: TierESysEnterAttachmentBackend,
            submittedInstructions: MemorySegment,
        ): TierEInstalledProgramIdentity {
            check(submittedInstructions.byteSize() > 0 && submittedInstructions.byteSize() % INSN_BYTES == 0L)
            val view = submittedInstructions.asByteBuffer().order(ByteOrder.nativeOrder())
            val algorithm = MessageDigest.getInstance("SHA-256")
            algorithm.update(view.duplicate())
            val digest = HexFormat.of().formatHex(algorithm.digest())
            var lookups = 0
            var updates = 0
            repeat((submittedInstructions.byteSize() / INSN_BYTES).toInt()) { index ->
                val offset = index * INSN_BYTES.toInt()
                if ((view.get(offset).toInt() and OPCODE_MASK) == BPF_CALL) {
                    val immediate = view.getInt(offset + IMMEDIATE_OFFSET)
                    if (immediate == BPF_MAP_LOOKUP_ELEM) lookups++
                    if (immediate == BPF_MAP_UPDATE_ELEM) updates++
                }
            }
            return TierEInstalledProgramIdentity(name, backend, (submittedInstructions.byteSize() / INSN_BYTES).toInt(), lookups, updates, digest)
        }

        fun kernelInfo(
            returnedLength: Int,
            programId: Int,
            translatedBytes: Int,
            runCount: Long?,
            runTimeNanos: Long?,
            recursionMisses: Long?,
        ): KernelInfo {
            check(returnedLength >= BpfProgramInfoLayout.XLATED_PROGRAM_LENGTH + Int.SIZE_BYTES && programId > 0 && translatedBytes > 0) {
                "kernel BPF program identity is unavailable"
            }
            check((returnedLength >= BpfProgramInfoLayout.RECURSION_MISSES + Long.SIZE_BYTES) == (recursionMisses != null)) {
                "recursion-miss availability disagrees with returned kernel program-info length"
            }
            val unavailable = runCount == null || runTimeNanos == null || (runCount == 0L && runTimeNanos == 0L)
            return KernelInfo(programId, translatedBytes, if (unavailable) null else runCount, if (unavailable) null else runTimeNanos, recursionMisses)
        }
    }
}
