package io.mazewall.seccomp

import io.mazewall.platform.syscall.Arch
import java.util.function.Function

internal sealed interface BpfStatus {
    data object Unverified : BpfStatus

    /** Passed local structural verification; this is not proof of kernel installation. */
    data object Verified : BpfStatus
}

/** Immutable bytecode. Only successful validation can mint the Verified type. */
internal class BpfProgram<out S : BpfStatus> private constructor(
    instructions: List<BpfInstruction>,
) {
    val instructions: List<BpfInstruction> = java.util.List.copyOf(instructions)

    fun disassemble(): String =
        instructions
            .mapIndexed { index, instruction ->
                "${index.toString().padStart(4, '0')}: ${instruction.disassemblyMnemonic()}"
            }.joinToString("\n")

    companion object {
        fun unverified(instructions: List<BpfInstruction>): BpfProgram<BpfStatus.Unverified> = BpfProgram(instructions)

        fun verify(program: BpfProgram<BpfStatus.Unverified>): BpfProgram<BpfStatus.Verified> {
            BpfStaticVerifier.validate(program)
            return BpfProgram(program.instructions)
        }

        fun builder(): BpfBuilder.Uninitialized = BpfBuilder.start()

        fun dsl(
            arch: Arch,
            block: Function<BpfBuilder.Active, BpfBuilder.Terminated>,
        ): BpfProgram<BpfStatus.Unverified> = dsl(arch) { block.apply(this) }

        fun dsl(
            arch: Arch,
            block: BpfBuilder.Active.() -> BpfBuilder.Terminated,
        ): BpfProgram<BpfStatus.Unverified> {
            val active = builder().checkArch(arch).loadSyscallNr()
            return active.complete(active.block())
        }
    }
}

private fun BpfInstruction.disassemblyMnemonic(): String =
    when (this) {
        is BpfInstruction.Ld -> "ld [$k]"
        is BpfInstruction.Alu -> "and #0x${k.toUInt().toString(16)}"
        is BpfInstruction.Ret -> "ret #0x${k.toUInt().toString(16)}"
        is BpfInstruction.Ja -> "ja +$k"
        is BpfInstruction.Jmp -> {
            val mnemonic = when (comparison) {
                BpfInstruction.Comparison.EQUAL -> "jeq"
                BpfInstruction.Comparison.GREATER_THAN -> "jgt"
                BpfInstruction.Comparison.BITS_SET -> "jset"
            }
            "$mnemonic #$k, ${jt.relativeOffset()}, ${jf.relativeOffset()}"
        }
    }

private fun Short.relativeOffset(): String = if (this >= 0) "+$this" else toString()
