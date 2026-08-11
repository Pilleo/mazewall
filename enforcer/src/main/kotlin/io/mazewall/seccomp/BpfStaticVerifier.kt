package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

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
        if (instructions.isEmpty()) {
            throw IllegalArgumentException("BPF verification failed: program is empty")
        }
        if (instructions.size > NativeConstants.BPF_MAXINSNS) {
            throw IllegalArgumentException("BPF verification failed: program size (${instructions.size}) exceeds limit of ${NativeConstants.BPF_MAXINSNS} instructions")
        }

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
                    val nextIdx = idx + 1
                    if (nextIdx < 0 || nextIdx >= instructions.size) {
                        throw IllegalArgumentException("BPF verification failed: instruction index $nextIdx is out of bounds")
                    }
                    if (!visited[nextIdx]) {
                        visited[nextIdx] = true
                        stack.push(nextIdx)
                    }
                }
                is BpfInstruction.Jmp -> {
                    if (ins.jt < 0) {
                        throw IllegalArgumentException("BPF verification failed: negative jt offset is not allowed: ${ins.jt}")
                    }
                    if (ins.jf < 0) {
                        throw IllegalArgumentException("BPF verification failed: negative jf offset is not allowed: ${ins.jf}")
                    }

                    val jtTarget = idx + 1 + ins.jt.toInt()
                    val jfTarget = idx + 1 + ins.jf.toInt()

                    if (jtTarget < 0 || jtTarget >= instructions.size) {
                        throw IllegalArgumentException("BPF verification failed: instruction index $jtTarget is out of bounds")
                    }
                    if (jfTarget < 0 || jfTarget >= instructions.size) {
                        throw IllegalArgumentException("BPF verification failed: instruction index $jfTarget is out of bounds")
                    }

                    if (!visited[jtTarget]) {
                        visited[jtTarget] = true
                        stack.push(jtTarget)
                    }
                    if (!visited[jfTarget]) {
                        visited[jfTarget] = true
                        stack.push(jfTarget)
                    }
                }
            }
        }

        // Return a new instance representing the verified state
        return BpfProgram(instructions)
    }
}
