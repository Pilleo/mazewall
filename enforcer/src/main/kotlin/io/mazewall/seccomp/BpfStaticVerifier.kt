package io.mazewall.seccomp

import io.mazewall.ffi.NativeConstants

/**
 * Static verifier for BPF programs.
 * Performs deep, formal static control-flow analysis on BPF instruction paths.
 * Guarantees that the program is non-empty, fits within kernel size limits, has only
 * valid jump destinations, contains no out-of-bounds execution pathways, and that all
 * possible reachable instruction paths terminate with a valid RET instruction.
 *
 * Implemented iteratively using a stack on the heap to avoid [StackOverflowError] risks.
 */
public object BpfStaticVerifier {
    /**
     * Statically verifies a BpfProgram, transitioning its state to [BpfStatus.Verified].
     * Throws an [IllegalArgumentException] if any validation rule or control-flow invariant is violated.
     */
    public fun verify(program: BpfProgram<BpfStatus.Unverified>): BpfProgram<BpfStatus.Verified> {
        val instructions = program.instructions
        validateProgramSize(instructions)
        verifyControlFlow(program)
        return BpfProgram(instructions)
    }

    private fun validateProgramSize(instructions: List<BpfInstruction>) {
        require(instructions.isNotEmpty()) { "BPF verification failed: program is empty" }
        require(instructions.size <= NativeConstants.BPF_MAXINSNS) {
            "BPF verification failed: program size (${instructions.size}) exceeds limit of ${NativeConstants.BPF_MAXINSNS} instructions"
        }
    }

    private fun verifyControlFlow(program: BpfProgram<BpfStatus.Unverified>) {
        val instructions = program.instructions
        val visited = BooleanArray(instructions.size)
        val stack = java.util.ArrayDeque<Int>()

        stack.push(0)
        visited[0] = true

        while (!stack.isEmpty()) {
            val idx = stack.pop()
            val ins = instructions[idx]

            when (ins) {
                is BpfInstruction.Ret -> {
                    // Safe termination
                }
                is BpfInstruction.Ld, is BpfInstruction.Alu -> {
                    enqueue(program, visited, stack, idx + 1)
                }
                is BpfInstruction.Jmp -> {
                    enqueueJumpTargets(program, visited, stack, idx, ins)
                }
            }
        }
    }

    private fun enqueueJumpTargets(
        program: BpfProgram<BpfStatus.Unverified>,
        visited: BooleanArray,
        stack: java.util.ArrayDeque<Int>,
        index: Int,
        instruction: BpfInstruction.Jmp,
    ) {
        requireNonNegativeOffset(program, "jt", instruction.jt)
        requireNonNegativeOffset(program, "jf", instruction.jf)
        enqueue(program, visited, stack, index + 1 + instruction.jt.toInt())
        enqueue(program, visited, stack, index + 1 + instruction.jf.toInt())
    }

    private fun requireNonNegativeOffset(
        program: BpfProgram<BpfStatus.Unverified>,
        name: String,
        offset: Short,
    ) {
        if (offset < 0) verificationFailure(program, "negative $name offset is not allowed: $offset")
    }

    private fun enqueue(
        program: BpfProgram<BpfStatus.Unverified>,
        visited: BooleanArray,
        stack: java.util.ArrayDeque<Int>,
        index: Int,
    ) {
        if (index !in visited.indices) verificationFailure(program, "instruction index $index is out of bounds")
        if (!visited[index]) {
            visited[index] = true
            stack.push(index)
        }
    }
}

private fun verificationFailure(
    program: BpfProgram<BpfStatus.Unverified>,
    detail: String,
): Nothing {
    throw IllegalArgumentException("BPF verification failed: $detail\nBPF program:\n${program.disassemble()}")
}
