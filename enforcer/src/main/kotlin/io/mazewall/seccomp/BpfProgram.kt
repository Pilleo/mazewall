package io.mazewall.seccomp

import io.mazewall.BpfFilter
import io.mazewall.core.Arch
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.ffi.NativeConstants
import java.util.function.Consumer
import java.util.function.Function

/**
 * Represents the verification status of a BPF program.
 */
public sealed interface BpfStatus {
    /** The program has been built but not yet verified by the kernel. */
    public data object Unverified : BpfStatus

    /** The program has been successfully verified and installed by the kernel. */
    public data object Verified : BpfStatus
}

/**
 * A type-safe token representing a jump target within a BPF program.
 */
@JvmInline
public value class BpfLabel(internal val name: String)

/**
 * A compiled seccomp policy containing the BPF filter instructions ready for installation.
 *
 * @param S The [BpfStatus] of the program.
 */
public class BpfProgram<out S : BpfStatus>(
    public val instructions: List<BpfInstruction>,
) {
    public companion object {
        private const val MAX_BPF_JUMP_OFFSET = 255

        @JvmStatic
        public fun builder(): BpfBuilder.Uninitialized = BpfBuilder.Uninitialized()

        /**
         * Declarative entry point for building BPF programs.
         * Enforces that the program ends with a termination instruction.
         */
        @JvmStatic
        public fun dsl(
            arch: Arch,
            block: Function<BpfBuilder.NrLoaded, BpfBuilder.Terminated>
        ): BpfProgram<BpfStatus.Unverified> {
            val nrLoaded = BpfBuilder.Uninitialized()
                .checkArch(arch)
                .loadSyscallNr()
            val terminated = block.apply(nrLoaded)
            return terminated.build()
        }

        /**
         * Kotlin-friendly declarative entry point for building BPF programs.
         * Enforces that the program ends with a termination instruction.
         */
        public inline fun dsl(
            arch: Arch,
            block: BpfBuilder.NrLoaded.() -> BpfBuilder.Terminated
        ): BpfProgram<BpfStatus.Unverified> =
            BpfBuilder.Uninitialized()
                .checkArch(arch)
                .loadSyscallNr()
                .let(block)
                .build()
    }
}

/**
 * Type-safe state machine for building BPF programs.
 * Enforces the initialization sequence: Arch Check -> Load NR -> Filtering -> Termination.
 */
public sealed class BpfBuilder protected constructor(
    internal val ops: MutableList<BpfMacro>,
    internal val labelCounter: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0)
) {

    internal fun nextLabel(prefix: String): BpfLabel {
        return BpfLabel("${prefix}_${labelCounter.incrementAndGet()}")
    }

    /**
     * Initial state: Only allows architecture verification.
     */
    public class Uninitialized : BpfBuilder(mutableListOf()) {
        /**
         * Emits code to verify the current architecture and transitions to [ArchVerified].
         */
        public fun checkArch(arch: Arch): ArchVerified {
            ops.add(BpfMacro.LoadAbsolute(BpfFilter.SECCOMP_DATA_ARCH_OFFSET))
            val archOkLabel = nextLabel("arch_ok")
            ops.add(BpfMacro.JumpIfEqual(arch.audit, jt = archOkLabel))
            ops.add(BpfMacro.Ret(NativeConstants.SECCOMP_RET_KILL_THREAD))
            ops.add(BpfMacro.Label(archOkLabel))
            return ArchVerified(ops, labelCounter)
        }
    }

    /**
     * Architecture verified: Only allows loading the syscall number.
     */
    public class ArchVerified internal constructor(
        ops: MutableList<BpfMacro>,
        labelCounter: java.util.concurrent.atomic.AtomicInteger
    ) : BpfBuilder(ops, labelCounter) {
        /**
         * Emits code to load the syscall number and transitions to [NrLoaded].
         */
        public fun loadSyscallNr(): NrLoaded {
            ops.add(BpfMacro.LoadAbsolute(BpfFilter.SECCOMP_DATA_NR_OFFSET))
            return NrLoaded(ops, labelCounter)
        }
    }

    /**
     * Syscall number loaded: Allows full filtering logic and final building.
     */
    public class NrLoaded internal constructor(
        ops: MutableList<BpfMacro>,
        labelCounter: java.util.concurrent.atomic.AtomicInteger
    ) : BpfBuilder(ops, labelCounter) {

        /** Returns ACT_ALLOW immediately. */
        public fun allow(): Terminated {
            return ret(SeccompAction.ACT_ALLOW.nativeCode)
        }

        /** Returns ACT_ERRNO with the given [errno]. */
        public fun deny(errno: Int): Terminated {
            return ret(SeccompAction.ACT_ERRNO.nativeCode or (errno and ERRNO_MASK))
        }

        /** Returns SECCOMP_RET_KILL_THREAD. */
        public fun killThread(): Terminated {
            return ret(NativeConstants.SECCOMP_RET_KILL_THREAD)
        }

        /** Returns SECCOMP_RET_USER_NOTIF (for profiling or complex rules). */
        public fun notifyUser(): Terminated {
            return ret(NativeConstants.SECCOMP_RET_USER_NOTIF)
        }

        /**
         * Expects a specific syscall number and executes the [block] if matched.
         * Skips the block if the syscall number does not match.
         *
         * Note: The block itself may terminate, but the main sequence continues
         * after the block's end.
         */
        public fun expect(nr: Int, block: NrLoaded.() -> Unit): NrLoaded {
            val skipLabel = nextLabel("skip")
            jumpIfEqual(nr, jf = skipLabel)
            this.block()
            mark(skipLabel)
            return this
        }

        /** Java-compatible version of [expect]. */
        public fun expect(nr: Int, block: Consumer<NrLoaded>): NrLoaded {
            val skipLabel = nextLabel("skip")
            jumpIfEqual(nr, jf = skipLabel)
            block.accept(this)
            mark(skipLabel)
            return this
        }

        /** Expects a specific [syscall] for the given [arch]. */
        public fun expect(syscall: Syscall, arch: Arch, block: NrLoaded.() -> Unit): NrLoaded {
            val nr = syscall.numberFor(arch)
            if (nr >= 0) expect(nr, block)
            return this
        }

        /** Java-compatible version of [expect] using [Syscall]. */
        public fun expect(syscall: Syscall, arch: Arch, block: Consumer<NrLoaded>): NrLoaded {
            val nr = syscall.numberFor(arch)
            if (nr >= 0) expect(nr, block)
            return this
        }

        public fun loadAbsolute(offset: Int): NrLoaded {
            ops.add(BpfMacro.LoadAbsolute(offset))
            return this
        }

        public fun jumpIfEqual(k: Int, jt: BpfLabel? = null, jf: BpfLabel? = null): NrLoaded {
            ops.add(BpfMacro.JumpIfEqual(k, jt, jf))
            return this
        }

        public fun jumpIfSet(k: Int, jt: BpfLabel? = null, jf: BpfLabel? = null): NrLoaded {
            ops.add(BpfMacro.JumpIfSet(k, jt, jf))
            return this
        }

        public fun and(k: Int): NrLoaded {
            ops.add(BpfMacro.And(k))
            return this
        }

        /**
         * Ends the instruction sequence with a RET instruction.
         * Transitions the builder to the [Terminated] state.
         */
        public fun ret(action: Int): Terminated {
            ops.add(BpfMacro.Ret(action))
            return Terminated(ops)
        }

        public fun mark(label: BpfLabel): NrLoaded {
            ops.add(BpfMacro.Label(label))
            return this
        }

        private companion object {
            private const val ERRNO_MASK = 0xFFFF
        }
    }

    /**
     * Terminated state: The BPF program ends with a RET instruction and is ready to be built.
     */
    public class Terminated internal constructor(ops: MutableList<BpfMacro>) : BpfBuilder(ops) {
        /**
         * Compiles the high-level instructions into raw seccomp-bpf opcodes.
         */
        public fun build(): BpfProgram<BpfStatus.Unverified> {
            val labelPositions = mutableMapOf<BpfLabel, Int>()
            val filteredOps = mutableListOf<BpfMacro>()

            // First pass: locate all labels and strip them from the instruction stream
            var currentPos = 0
            for (op in ops) {
                if (op is BpfMacro.Label) {
                    labelPositions[op.label] = currentPos
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
            jtLabel: BpfLabel?,
            jfLabel: BpfLabel?,
            currentIndex: Int,
            labelPositions: Map<BpfLabel, Int>,
        ): BpfInstruction.Jmp {
            val jt = resolveLabel(jtLabel, currentIndex, labelPositions)
            val jf = resolveLabel(jfLabel, currentIndex, labelPositions)
            return BpfInstruction.Jmp(code, jt, jf, k)
        }

        private fun resolveLabel(
            label: BpfLabel?,
            currentIndex: Int,
            labelPositions: Map<BpfLabel, Int>,
        ): Short {
            if (label == null) return 0
            val pos = labelPositions[label] ?: throw IllegalArgumentException("Unknown label: ${label.name}")
            val offset = pos - (currentIndex + 1)
            require(offset >= 0) { "Backward jumps are not allowed: ${label.name}" }
            require(offset <= MAX_BPF_JUMP_OFFSET) { "Jump offset too large for ${label.name}: $offset" }
            return offset.toShort()
        }

        private companion object {
            private const val MAX_BPF_JUMP_OFFSET = 255
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
internal sealed interface BpfMacro {
    data class LoadAbsolute(val offset: Int) : BpfMacro
    data class JumpIfEqual(val k: Int, val jt: BpfLabel? = null, val jf: BpfLabel? = null) : BpfMacro
    data class JumpIfSet(val k: Int, val jt: BpfLabel? = null, val jf: BpfLabel? = null) : BpfMacro
    data class And(val k: Int) : BpfMacro
    data class Ret(val action: Int) : BpfMacro
    data class Label(val label: BpfLabel) : BpfMacro
}
