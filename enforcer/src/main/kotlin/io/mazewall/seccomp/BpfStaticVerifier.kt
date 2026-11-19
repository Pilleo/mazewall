package io.mazewall.seccomp

import io.mazewall.ffi.NativeConstants

/** Checks every instruction, including unreachable code, as the kernel verifier does. */
internal object BpfStaticVerifier {
    fun verify(program: BpfProgram<BpfStatus.Unverified>): BpfProgram<BpfStatus.Verified> = BpfProgram.verify(program)

    internal fun validate(program: BpfProgram<BpfStatus.Unverified>) {
        val instructions = program.instructions
        require(instructions.isNotEmpty()) { "BPF verification failed: program is empty" }
        require(instructions.size <= NativeConstants.BPF_MAXINSNS) {
            "BPF verification failed: program size (${instructions.size}) exceeds limit of ${NativeConstants.BPF_MAXINSNS} instructions"
        }
        instructions.forEachIndexed { index, instruction ->
            when (instruction) {
                is BpfInstruction.Ret -> Unit
                is BpfInstruction.Ld, is BpfInstruction.Alu -> target(program, index, 0)
                is BpfInstruction.Ja -> target(program, index, instruction.k)
                is BpfInstruction.Jmp -> {
                    target(program, index, instruction.jt.toInt())
                    target(program, index, instruction.jf.toInt())
                }
            }
        }
        if (instructions.last() !is BpfInstruction.Ret) failure(program, "last instruction must be RET")
        // All edges strictly advance within the program, whose tail is RET: every path terminates.
    }

    private fun target(
        program: BpfProgram<BpfStatus.Unverified>,
        index: Int,
        offset: Int,
    ) {
        val target = index.toLong() + 1 + offset.toLong()
        if (offset < 0 || target >= program.instructions.size) failure(program, "instruction index $target is out of bounds")
    }

    private fun failure(
        program: BpfProgram<BpfStatus.Unverified>,
        detail: String,
    ): Nothing = throw IllegalArgumentException("BPF verification failed: $detail\nBPF program:\n${program.disassemble()}")
}
