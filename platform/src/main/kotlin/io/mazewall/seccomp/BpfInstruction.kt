package io.mazewall.seccomp

import io.mazewall.platform.syscall.SeccompAction

private const val SECCOMP_DATA_SIZE = 64

/** The supported classic-BPF subset; opcodes cannot contradict their operands. */
internal sealed interface BpfInstruction {
    val code: Short
    val jt: Short get() = 0
    val jf: Short get() = 0
    val k: Int

    /** Native-endian, aligned 32-bit load from the 64-byte seccomp_data structure. */
    data class Ld(
        override val k: Int,
    ) : BpfInstruction {
        override val code: Short get() = 0x20

        init {
            require(k in 0 until SECCOMP_DATA_SIZE && k % Int.SIZE_BYTES == 0) { "Invalid seccomp_data word offset: $k" }
        }
    }

    enum class Comparison(
        val code: Short,
    ) {
        EQUAL(0x15),
        GREATER_THAN(0x25),
        BITS_SET(0x45),
    }

    data class Jmp(
        val comparison: Comparison,
        override val jt: Short,
        override val jf: Short,
        override val k: Int,
    ) : BpfInstruction {
        override val code: Short get() = comparison.code

        init {
            require(jt in 0..MAX_OFFSET) { "jt offset must be an unsigned 8-bit value (0-255), got $jt" }
            require(jf in 0..MAX_OFFSET) { "jf offset must be an unsigned 8-bit value (0-255), got $jf" }
        }

        companion object {
            const val MAX_OFFSET = 255
        }
    }

    /** JA uses k, never jt/jf. Program verification bounds it against the actual size. */
    data class Ja(
        override val k: Int,
    ) : BpfInstruction {
        override val code: Short get() = 0x05

        init {
            require(k >= 0) { "JA offset must be nonnegative, got $k" }
        }
    }

    data class Ret(
        val action: SeccompAction,
    ) : BpfInstruction {
        override val code: Short get() = 0x06
        override val k: Int get() = action.toKernelReturnCode()
    }

    data class Alu(
        override val k: Int,
    ) : BpfInstruction {
        override val code: Short get() = 0x54
    }
}
