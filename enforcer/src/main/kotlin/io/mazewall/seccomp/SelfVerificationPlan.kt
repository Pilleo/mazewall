package io.mazewall.seccomp

import io.mazewall.core.Arch
import io.mazewall.ffi.NativeConstants

/**
 * Pure, bounded probe selection for post-install self-verification.
 *
 * The plan deliberately contains no native handle or execution capability: the verifier owns
 * raw syscalls, diagnostics, and memoization after this decision has been made.
 */
internal data class SelfVerificationPlan(
    val livenessNr: Int,
    val probeLiveness: Boolean,
    val deniedProbes: List<Pair<Int, Int>>,
) {
    internal companion object {
        private const val MAX_PROBES = 4
        private const val MAX_PLAUSIBLE_NR = 9_999
        private const val JEQ_OPCODE: Short = 0x15
        private const val LD_ABS_OPCODE: Short = 0x20

        fun create(
            instructions: List<BpfInstruction>,
            arch: Arch,
            maxProbes: Int = MAX_PROBES,
        ): SelfVerificationPlan {
            val livenessNr = arch.getpid
            return SelfVerificationPlan(
                livenessNr = livenessNr,
                probeLiveness = BpfSimulator.simulate(instructions, livenessNr, arch) == NativeConstants.SECCOMP_RET_ALLOW,
                deniedProbes = deniedProbeNrs(instructions, arch, maxProbes),
            )
        }

        fun deniedProbeNrs(
            instructions: List<BpfInstruction>,
            arch: Arch,
            maxProbes: Int = MAX_PROBES,
        ): List<Pair<Int, Int>> {
            val candidates = LinkedHashSet<Int>()
            SyscallProbeMatrix.structural(arch).forEach { candidates.add(it.nr) }
            candidates.add(SyscallProbeMatrix.SYNTHETIC_HIGH_NR)

            val auditTokens = setOf(Arch.AMD64.audit, Arch.AARCH64.audit)
            instructions.filterIsInstance<BpfInstruction.Jmp>().forEach { instruction ->
                if (instruction.code == JEQ_OPCODE &&
                    instruction.k in 0..MAX_PLAUSIBLE_NR &&
                    instruction.k !in auditTokens
                ) {
                    candidates.add(instruction.k)
                }
            }

            return candidates
                .mapNotNull { nr ->
                val action = BpfSimulator.simulate(instructions, nr, arch) ?: return@mapNotNull null
                if (isArgInspected(instructions, nr) ||
                    (action ushr 16) != (NativeConstants.SECCOMP_RET_ERRNO ushr 16)
                ) {
                    return@mapNotNull null
                }
                nr to (action and 0xFFFF)
            }.take(maxProbes)
        }

        fun isArgInspected(
            instructions: List<BpfInstruction>,
            nr: Int,
        ): Boolean {
            val index = instructions.indexOfFirst {
                it is BpfInstruction.Jmp && it.code == JEQ_OPCODE && it.k == nr
            }
            if (index < 0) return false
            for (i in index + 1 until instructions.size) {
                when (val instruction = instructions[i]) {
                    is BpfInstruction.Ret -> return false
                    is BpfInstruction.Ld -> {
                        if (instruction.k in BpfSimulator.SECCOMP_DATA_ARGS_OFFSET until BpfSimulator.SECCOMP_DATA_ARGS_OFFSET + 48) {
                            return true
                        }
                    }
                    else -> Unit
                }
            }
            return false
        }
    }
}
