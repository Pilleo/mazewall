package io.mazewall.seccomp

import io.mazewall.core.Arch
import io.mazewall.enforcer.state.ContainerState
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
        private const val SECCOMP_ACTION_MASK = -0x1_0000
        private const val SECCOMP_DATA_MASK = 0xFFFF
        private const val SECCOMP_ARGUMENT_BYTES = 6 * Long.SIZE_BYTES

        fun create(
            instructions: List<BpfInstruction>,
            arch: Arch,
            maxProbes: Int = MAX_PROBES,
            mergedState: ContainerState? = null,
        ): SelfVerificationPlan {
            val livenessNr = arch.getpid
            return SelfVerificationPlan(
                livenessNr = livenessNr,
                probeLiveness = if (mergedState == null) {
                    BpfSimulator.simulate(instructions, livenessNr, arch) == NativeConstants.SECCOMP_RET_ALLOW
                } else {
                    mergedState.getEffectiveAction(livenessNr, arch).toKernelReturnCode() == NativeConstants.SECCOMP_RET_ALLOW
                },
                deniedProbes = if (mergedState == null) {
                    deniedProbeNrs(instructions, arch, maxProbes)
                } else {
                    deniedProbeNrsWithUnion(instructions, arch, mergedState, maxProbes)
                },
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
                    (action and SECCOMP_ACTION_MASK) != NativeConstants.SECCOMP_RET_ERRNO
                ) {
                    return@mapNotNull null
                }
                nr to (action and SECCOMP_DATA_MASK)
            }.take(maxProbes)
        }

        fun deniedProbeNrsWithUnion(
            instructions: List<BpfInstruction>,
            arch: Arch,
            mergedState: ContainerState,
            maxProbes: Int = MAX_PROBES,
        ): List<Pair<Int, Int>> =
            candidateSyscallNrs(instructions, arch)
                .apply { mergedState.syscallActions.keys.forEach { add(it.numberFor(arch)) } }
                .asSequence()
                .filterNot { isArgInspected(instructions, it) }
                .map { nr -> nr to mergedState.getEffectiveAction(nr, arch).toKernelReturnCode() }
                .filter { (_, action) -> (action and SECCOMP_ACTION_MASK) == NativeConstants.SECCOMP_RET_ERRNO }
                .map { (nr, action) -> nr to (action and SECCOMP_DATA_MASK) }
                .take(maxProbes)
                .toList()

        private fun candidateSyscallNrs(
            instructions: List<BpfInstruction>,
            arch: Arch,
        ): LinkedHashSet<Int> {
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
        }

        fun isArgInspected(
            instructions: List<BpfInstruction>,
            nr: Int,
        ): Boolean {
            val index = instructions.indexOfFirst {
                it is BpfInstruction.Jmp && it.code == JEQ_OPCODE && it.k == nr
            }
            if (index < 0) return false
            for (instruction in instructions.drop(index + 1)) {
                when (instruction) {
                    is BpfInstruction.Ret -> return false
                    is BpfInstruction.Ld -> {
                        if (instruction.k in BpfSimulator.SECCOMP_DATA_ARGS_OFFSET until
                            BpfSimulator.SECCOMP_DATA_ARGS_OFFSET + SECCOMP_ARGUMENT_BYTES
                        ) {
                            return true
                        }
                    }

                    is BpfInstruction.Alu,
                    is BpfInstruction.Jmp,
                    -> Unit
                }
            }
            return false
        }
    }
}
