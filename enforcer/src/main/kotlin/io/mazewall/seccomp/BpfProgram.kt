package io.mazewall.seccomp

import io.mazewall.BpfFilter

/**
 * A compiled seccomp policy containing the BPF filter instructions ready for installation.
 */
public class BpfProgram(
    public val instructions: List<BpfInstruction>,
) {
    public companion object {
        private const val MAX_BPF_JUMP_OFFSET = 255

        @JvmStatic
        public fun builder(): Builder = Builder()

        /**
         * Declarative entry point for building BPF programs.
         */
        @JvmStatic
        public fun dsl(block: java.util.function.Consumer<Builder>): BpfProgram {
            val builder = Builder()
            block.accept(builder)
            return builder.build()
        }

        /**
         * Kotlin-friendly declarative entry point for building BPF programs.
         */
        public inline fun dsl(block: Builder.() -> Unit): BpfProgram =
            Builder().apply(block).build()
    }

    /**
     * DSL for building BPF programs using symbolic labels and high-level helpers.
     */
    public class Builder {
        private val ops = mutableListOf<BpfMacro>()

        /** Loads the current syscall number into the accumulator. */
        public fun loadSyscallNr(): Builder = loadAbsolute(BpfFilter.SECCOMP_DATA_NR_OFFSET)

        /** Loads the CPU architecture into the accumulator. */
        public fun loadArch(): Builder = loadAbsolute(BpfFilter.SECCOMP_DATA_ARCH_OFFSET)

        /** Returns ACT_ALLOW immediately. */
        public fun allow(): Builder = ret(io.mazewall.core.SeccompAction.ACT_ALLOW.nativeCode)

        /** Returns ACT_ERRNO with the given [errno]. */
        public fun deny(errno: Int): Builder =
            ret(io.mazewall.core.SeccompAction.ACT_ERRNO.nativeCode or (errno and ERRNO_MASK))

        /** Returns SECCOMP_RET_KILL_THREAD. */
        public fun killThread(): Builder =
            ret(io.mazewall.ffi.NativeConstants.SECCOMP_RET_KILL_THREAD)

        /** Returns SECCOMP_RET_USER_NOTIF (for profiling or complex rules). */
        public fun notifyUser(): Builder =
            ret(io.mazewall.ffi.NativeConstants.SECCOMP_RET_USER_NOTIF)

        /**
         * Expects a specific syscall number and executes the [block] if matched.
         * Skips the block if the syscall number does not match.
         */
        public fun expect(nr: Int, block: Builder.() -> Unit): Builder {
            val skipLabel = "skip_${java.util.UUID.randomUUID().toString().replace("-", "")}"
            jumpIfEqual(nr, jf = skipLabel)
            this.block()
            label(skipLabel)
            return this
        }

        /**
         * Java-compatible version of [expect].
         */
        public fun expect(nr: Int, block: java.util.function.Consumer<Builder>): Builder {
            val skipLabel = "skip_${java.util.UUID.randomUUID().toString().replace("-", "")}"
            jumpIfEqual(nr, jf = skipLabel)
            block.accept(this)
            label(skipLabel)
            return this
        }

        /**
         * Expects a specific [syscall] for the given [arch].
         */
        public fun expect(syscall: io.mazewall.core.Syscall, arch: io.mazewall.core.Arch, block: Builder.() -> Unit): Builder {
            val nr = syscall.numberFor(arch)
            if (nr >= 0) expect(nr, block)
            return this
        }

        /**
         * Java-compatible version of [expect] using [io.mazewall.core.Syscall].
         */
        public fun expect(syscall: io.mazewall.core.Syscall, arch: io.mazewall.core.Arch, block: java.util.function.Consumer<Builder>): Builder {
            val nr = syscall.numberFor(arch)
            if (nr >= 0) expect(nr, block)
            return this
        }

        public fun loadAbsolute(offset: Int): Builder {
            ops.add(BpfMacro.LoadAbsolute(offset))
            return this
        }

        public fun jumpIfEqual(
            k: Int,
            jt: String? = null,
            jf: String? = null,
        ): Builder {
            ops.add(BpfMacro.JumpIfEqual(k, jt, jf))
            return this
        }

        public fun jumpIfSet(
            k: Int,
            jt: String? = null,
            jf: String? = null,
        ): Builder {
            ops.add(BpfMacro.JumpIfSet(k, jt, jf))
            return this
        }

        public fun and(k: Int): Builder {
            ops.add(BpfMacro.And(k))
            return this
        }

        public fun ret(action: Int): Builder {
            ops.add(BpfMacro.Ret(action))
            return this
        }

        public fun label(name: String): Builder {
            ops.add(BpfMacro.Label(name))
            return this
        }

        /**
         * Compiles the high-level instructions into raw seccomp-bpf opcodes.
         * Resolves all symbolic labels into forward-only relative offsets.
         */
        public fun build(): BpfProgram {
            val labelPositions = mutableMapOf<String, Int>()
            val filteredOps = mutableListOf<BpfMacro>()

            // First pass: locate all labels and strip them from the instruction stream
            var currentPos = 0
            for (op in ops) {
                if (op is BpfMacro.Label) {
                    labelPositions[op.name] = currentPos
                } else {
                    filteredOps.add(op)
                    currentPos++
                }
            }

            // Second pass: compile instructions and resolve labels
            val bpfInstructions = filteredOps.mapIndexed { index, op ->
                when (op) {
                    is BpfMacro.LoadAbsolute -> BpfInstruction.Ld(BPF_LD_ABS, op.offset)
                    is BpfMacro.And -> BpfInstruction.Alu(BPF_ALU_AND, op.k)
                    is BpfMacro.Ret -> BpfInstruction.Ret(BPF_RET, op.action)
                    is BpfMacro.JumpIfEqual -> compileJump(BPF_JMP_JEQ, op.k, op.jt, op.jf, index, labelPositions)
                    is BpfMacro.JumpIfSet -> compileJump(BPF_JMP_JSET, op.k, op.jt, op.jf, index, labelPositions)
                    is BpfMacro.Label -> throw IllegalStateException("Label found in filtered ops")
                }
            }

            return BpfProgram(bpfInstructions)
        }

        private fun compileJump(
            code: Short,
            k: Int,
            jtLabel: String?,
            jfLabel: String?,
            currentIndex: Int,
            labelPositions: Map<String, Int>,
        ): BpfInstruction.Jmp {
            val jt = resolveLabel(jtLabel, currentIndex, labelPositions)
            val jf = resolveLabel(jfLabel, currentIndex, labelPositions)
            return BpfInstruction.Jmp(code, jt, jf, k)
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

        private companion object {
            private const val ERRNO_MASK = 0xFFFF
            private const val BPF_LD_ABS: Short = 0x20
            private const val BPF_ALU_AND: Short = 0x54
            private const val BPF_RET: Short = 0x06
            private const val BPF_JMP_JEQ: Short = 0x15
            private const val BPF_JMP_JSET: Short = 0x45
        }
    }
}

/**
 * Intermediate symbolic representation of BPF instructions before label resolution.
 */
private sealed interface BpfMacro {
    data class LoadAbsolute(val offset: Int) : BpfMacro
    data class JumpIfEqual(val k: Int, val jt: String?, val jf: String?) : BpfMacro
    data class JumpIfSet(val k: Int, val jt: String?, val jf: String?) : BpfMacro
    data class And(val k: Int) : BpfMacro
    data class Ret(val action: Int) : BpfMacro
    data class Label(val name: String) : BpfMacro
}
