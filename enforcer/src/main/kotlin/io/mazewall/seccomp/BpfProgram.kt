package io.mazewall.seccomp

import io.mazewall.SockFilter

/**
 * High-level BPF instructions that use symbolic labels instead of raw offsets.
 */
internal sealed interface BpfInstruction {
    data class LoadAbsolute(
        val offset: Int,
    ) : BpfInstruction

    data class JumpIfEqual(
        val k: Int,
        val jt: String? = null,
        val jf: String? = null,
    ) : BpfInstruction

    data class JumpIfSet(
        val k: Int,
        val jt: String? = null,
        val jf: String? = null,
    ) : BpfInstruction

    data class And(
        val k: Int,
    ) : BpfInstruction

    data class Ret(
        val action: Int,
    ) : BpfInstruction

    data class Label(
        val name: String,
    ) : BpfInstruction
}

/**
 * A compiled BPF program ready for installation into the kernel.
 */
public class BpfProgram private constructor(
    public val instructions: Array<SockFilter>,
) {
    public companion object {
        private const val MAX_BPF_JUMP_OFFSET = 255

        @JvmStatic
        public fun builder(): Builder = Builder()
    }

    /**
     * DSL for building BPF programs using symbolic labels.
     */
    public class Builder {
        private val ops = mutableListOf<BpfInstruction>()

        public fun loadAbsolute(offset: Int): Builder {
            ops.add(BpfInstruction.LoadAbsolute(offset))
            return this
        }

        public fun jumpIfEqual(
            k: Int,
            jt: String? = null,
            jf: String? = null,
        ): Builder {
            ops.add(BpfInstruction.JumpIfEqual(k, jt, jf))
            return this
        }

        public fun jumpIfSet(
            k: Int,
            jt: String? = null,
            jf: String? = null,
        ): Builder {
            ops.add(BpfInstruction.JumpIfSet(k, jt, jf))
            return this
        }

        public fun and(k: Int): Builder {
            ops.add(BpfInstruction.And(k))
            return this
        }

        public fun ret(action: Int): Builder {
            ops.add(BpfInstruction.Ret(action))
            return this
        }

        public fun label(name: String): Builder {
            ops.add(BpfInstruction.Label(name))
            return this
        }

        /**
         * Compiles the high-level instructions into raw seccomp-bpf opcodes.
         * Resolves all symbolic labels into forward-only relative offsets.
         */
        public fun build(): BpfProgram {
            val labelPositions = mutableMapOf<String, Int>()
            val filteredOps = mutableListOf<BpfInstruction>()

            // First pass: locate all labels and strip them from the instruction stream
            var currentPos = 0
            for (op in ops) {
                if (op is BpfInstruction.Label) {
                    labelPositions[op.name] = currentPos
                } else {
                    filteredOps.add(op)
                    currentPos++
                }
            }

            // Second pass: compile instructions and resolve labels
            val sockFilters = filteredOps.mapIndexed { index, op ->
                when (op) {
                    is BpfInstruction.LoadAbsolute -> SockFilter(0x20.toShort(), 0, 0, op.offset)
                    is BpfInstruction.And -> SockFilter(0x54.toShort(), 0, 0, op.k)
                    is BpfInstruction.Ret -> SockFilter(0x06.toShort(), 0, 0, op.action)
                    is BpfInstruction.JumpIfEqual -> compileJump(0x15.toShort(), op.k, op.jt, op.jf, index, labelPositions)
                    is BpfInstruction.JumpIfSet -> compileJump(0x45.toShort(), op.k, op.jt, op.jf, index, labelPositions)
                    is BpfInstruction.Label -> throw IllegalStateException("Label found in filtered ops")
                }
            }

            return BpfProgram(sockFilters.toTypedArray())
        }

        private fun compileJump(
            code: Short,
            k: Int,
            jtLabel: String?,
            jfLabel: String?,
            currentIndex: Int,
            labelPositions: Map<String, Int>,
        ): SockFilter {
            val jt = resolveLabel(jtLabel, currentIndex, labelPositions)
            val jf = resolveLabel(jfLabel, currentIndex, labelPositions)
            return SockFilter(code, jt, jf, k)
        }

        private fun resolveLabel(
            label: String?,
            currentIndex: Int,
            labelPositions: Map<String, Int>,
        ): Short {
            if (label == null) return 0
            val pos = labelPositions[label] ?: throw IllegalArgumentException("Unknown label: $label")
            val offset = pos - (currentIndex + 1)
            require(offset >= 0) { "Backward jumps are not allowed: $label" }
            require(offset <= MAX_BPF_JUMP_OFFSET) { "Jump offset too large for $label: $offset" }
            return offset.toShort()
        }
    }
}
