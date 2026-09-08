package io.mazewall.seccomp

import io.mazewall.core.Arch
import io.mazewall.ffi.NativeConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

internal class BpfSimulatorTest {
    companion object {
        private const val LOAD_ABSOLUTE = 0x20.toShort()
        private const val JUMP_EQUAL = 0x15.toShort()
        private const val JUMP_GREATER_THAN = 0x25.toShort()
        private const val JUMP_SET = 0x45.toShort()
        private const val JUMP_ALWAYS = 0x05.toShort()
        private const val AND = 0x54.toShort()
        private const val RETURN = 0x06.toShort()

        data class SimulationCase(
            val name: String,
            val program: List<BpfInstruction>,
            val syscallNr: Int,
            val auditToken: Int = Arch.AMD64.audit,
            val args: LongArray = LongArray(6),
            val expected: Int,
        ) {
            override fun toString(): String = name
        }

        @JvmStatic
        fun instructionPaths(): Stream<SimulationCase> =
            Stream.of(
                SimulationCase(
                    "JEQ true path returns allow",
                    listOf(
                        BpfInstruction.Ld(LOAD_ABSOLUTE, BpfSimulator.SECCOMP_DATA_NR_OFFSET),
                        BpfInstruction.Jmp(JUMP_EQUAL, 0, 1, 42),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ALLOW),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ERRNO),
                    ),
                    syscallNr = 42,
                    expected = NativeConstants.SECCOMP_RET_ALLOW,
                ),
                SimulationCase(
                    "JEQ false path uses jf offset",
                    listOf(
                        BpfInstruction.Ld(LOAD_ABSOLUTE, BpfSimulator.SECCOMP_DATA_NR_OFFSET),
                        BpfInstruction.Jmp(JUMP_EQUAL, 0, 1, 42),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ALLOW),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ERRNO),
                    ),
                    syscallNr = 7,
                    expected = NativeConstants.SECCOMP_RET_ERRNO,
                ),
                SimulationCase(
                    "JGT compares the syscall number as unsigned",
                    listOf(
                        BpfInstruction.Ld(LOAD_ABSOLUTE, BpfSimulator.SECCOMP_DATA_NR_OFFSET),
                        BpfInstruction.Jmp(JUMP_GREATER_THAN, 0, 1, 1),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ALLOW),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ERRNO),
                    ),
                    syscallNr = -1,
                    expected = NativeConstants.SECCOMP_RET_ALLOW,
                ),
                SimulationCase(
                    "JGT false path uses jf offset",
                    listOf(
                        BpfInstruction.Ld(LOAD_ABSOLUTE, BpfSimulator.SECCOMP_DATA_NR_OFFSET),
                        BpfInstruction.Jmp(JUMP_GREATER_THAN, 0, 1, 1),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ALLOW),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ERRNO),
                    ),
                    syscallNr = 1,
                    expected = NativeConstants.SECCOMP_RET_ERRNO,
                ),
                SimulationCase(
                    "JSET takes true path for matching argument bit",
                    listOf(
                        BpfInstruction.Ld(LOAD_ABSOLUTE, BpfSimulator.SECCOMP_DATA_ARGS_OFFSET),
                        BpfInstruction.Jmp(JUMP_SET, 0, 1, 0x80),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ALLOW),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ERRNO),
                    ),
                    syscallNr = 0,
                    args = longArrayOf(0x80),
                    expected = NativeConstants.SECCOMP_RET_ALLOW,
                ),
                SimulationCase(
                    "JSET false path uses jf offset",
                    listOf(
                        BpfInstruction.Ld(LOAD_ABSOLUTE, BpfSimulator.SECCOMP_DATA_ARGS_OFFSET),
                        BpfInstruction.Jmp(JUMP_SET, 0, 1, 0x80),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ALLOW),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ERRNO),
                    ),
                    syscallNr = 0,
                    args = longArrayOf(0),
                    expected = NativeConstants.SECCOMP_RET_ERRNO,
                ),
                SimulationCase(
                    "JA uses K as its skip count",
                    listOf(
                        BpfInstruction.Jmp(JUMP_ALWAYS, 0, 0, 1),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ERRNO),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ALLOW),
                    ),
                    syscallNr = 0,
                    expected = NativeConstants.SECCOMP_RET_ALLOW,
                ),
                SimulationCase(
                    "ALU AND operates on the low argument word",
                    listOf(
                        BpfInstruction.Ld(LOAD_ABSOLUTE, BpfSimulator.SECCOMP_DATA_ARGS_OFFSET),
                        BpfInstruction.Alu(AND, 0xff),
                        BpfInstruction.Jmp(JUMP_EQUAL, 0, 1, 0xab),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ALLOW),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ERRNO),
                    ),
                    syscallNr = 0,
                    args = longArrayOf(0x12345678000000ab),
                    expected = NativeConstants.SECCOMP_RET_ALLOW,
                ),
                SimulationCase(
                    "argument high word is selected at the four-byte offset",
                    listOf(
                        BpfInstruction.Ld(LOAD_ABSOLUTE, BpfSimulator.SECCOMP_DATA_ARGS_OFFSET + 4),
                        BpfInstruction.Jmp(JUMP_EQUAL, 0, 1, 0x12345678),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ALLOW),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ERRNO),
                    ),
                    syscallNr = 0,
                    args = longArrayOf(0x12345678000000ab),
                    expected = NativeConstants.SECCOMP_RET_ALLOW,
                ),
                SimulationCase(
                    "missing argument words read as zero",
                    listOf(
                        BpfInstruction.Ld(LOAD_ABSOLUTE, BpfSimulator.SECCOMP_DATA_ARGS_OFFSET + 40),
                        BpfInstruction.Jmp(JUMP_EQUAL, 0, 1, 0),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ALLOW),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ERRNO),
                    ),
                    syscallNr = 0,
                    args = LongArray(0),
                    expected = NativeConstants.SECCOMP_RET_ALLOW,
                ),
                SimulationCase(
                    "architecture load supports the convenience overload",
                    listOf(
                        BpfInstruction.Ld(LOAD_ABSOLUTE, BpfSimulator.SECCOMP_DATA_ARCH_OFFSET),
                        BpfInstruction.Jmp(JUMP_EQUAL, 0, 1, Arch.AARCH64.audit),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ALLOW),
                        BpfInstruction.Ret(RETURN, NativeConstants.SECCOMP_RET_ERRNO),
                    ),
                    syscallNr = 0,
                    auditToken = Arch.AARCH64.audit,
                    expected = NativeConstants.SECCOMP_RET_ALLOW,
                ),
            )

        @JvmStatic
        fun excludedProbeNumbers(): Stream<Arguments> =
            Stream.of(
                Arguments.of("nr zero", 0),
                Arguments.of("synthetic mid", SyscallProbeMatrix.SYNTHETIC_MID_NR),
                Arguments.of("synthetic high", SyscallProbeMatrix.SYNTHETIC_HIGH_NR),
            )

        @JvmStatic
        fun matchedProbeInputs(): Stream<Arguments> =
            Stream.of(
                Arguments.of(listOf(1, -1, 7), listOf(SyscallProbeMatrix.Probe(1, "policy-nr-1"), SyscallProbeMatrix.Probe(7, "policy-nr-7"))),
                Arguments.of(emptyList<Int>(), emptyList<SyscallProbeMatrix.Probe>()),
            )

        @JvmStatic
        fun unsupportedOpcodes(): Stream<Short> = Stream.of(0x00.toShort(), 0x07.toShort())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("instructionPaths")
    fun `simulator follows BPF instruction paths`(testCase: SimulationCase) {
        assertEquals(
            testCase.expected,
            BpfSimulator.simulate(testCase.program, testCase.syscallNr, testCase.auditToken, testCase.args),
        )
    }

    @ParameterizedTest(name = "structural matrix omits {0}")
    @MethodSource("excludedProbeNumbers")
    fun `structural probes exclude named syscall numbers`(
        name: String,
        excludedNr: Int,
    ) {
        val probes = SyscallProbeMatrix.structural(Arch.AMD64, setOf(excludedNr))

        assertEquals(false, probes.any { it.nr == excludedNr })
    }

    @ParameterizedTest(name = "matched probe {0}")
    @MethodSource("matchedProbeInputs")
    fun `matched probes retain valid policy syscall numbers`(
        numbers: List<Int>,
        expected: List<SyscallProbeMatrix.Probe>,
    ) {
        assertEquals(expected, SyscallProbeMatrix.matched(Arch.AMD64, numbers))
    }

    @ParameterizedTest(name = "unsupported opcode {0}")
    @MethodSource("unsupportedOpcodes")
    fun `unsupported instructions fail with their opcode and program counter`(opcode: Short) {
        val exception = assertThrows(IllegalStateException::class.java) {
            BpfSimulator.simulate(listOf(BpfInstruction.Ret(opcode, 0)), 0, Arch.AMD64)
        }

        assertEquals("BpfSimulator does not implement opcode 0x${opcode.toUByte().toString(16)} at pc=0", exception.message)
    }

    @org.junit.jupiter.api.Test
    fun `programs without a return surface an undecided result`() {
        assertNull(BpfSimulator.simulate(listOf(BpfInstruction.Ld(LOAD_ABSOLUTE, 99)), 0, Arch.AMD64))
    }

    @org.junit.jupiter.api.Test
    fun `structural probes provide the complete default unmatched matrix`() {
        assertEquals(
            listOf(
                SyscallProbeMatrix.Probe(0, "nr-zero-edge"),
                SyscallProbeMatrix.Probe(Arch.AMD64.getpid, "low-real"),
                SyscallProbeMatrix.Probe(SyscallProbeMatrix.SYNTHETIC_MID_NR, "synthetic-mid"),
                SyscallProbeMatrix.Probe(Arch.AMD64.pidfdGetFd, "high-real"),
                SyscallProbeMatrix.Probe(SyscallProbeMatrix.SYNTHETIC_HIGH_NR, "synthetic-high"),
            ),
            SyscallProbeMatrix.structural(Arch.AMD64),
        )
    }
}
