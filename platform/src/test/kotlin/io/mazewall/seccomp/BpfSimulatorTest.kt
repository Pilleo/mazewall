package io.mazewall.seccomp

import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class BpfSimulatorTest {
    data class SimulationCase(
        val name: String,
        val program: List<BpfInstruction>,
        val syscallNr: Int = 0,
        val auditToken: Int = Arch.AMD64.audit,
        val args: LongArray = LongArray(6),
        val expected: Int,
    ) {
        override fun toString(): String = name
    }

    companion object {
        private val allow = BpfInstruction.Ret(SeccompAction.ACT_ALLOW)
        private val deny = BpfInstruction.Ret(SeccompAction.ACT_ERRNO())

        private fun branch(
            comparison: BpfInstruction.Comparison,
            value: Int,
            offset: Int = 0,
        ): List<BpfInstruction> =
            listOf(
            BpfInstruction.Ld(offset),
            BpfInstruction.Jmp(comparison, 0, 1, value),
            allow,
            deny,
        )

        @JvmStatic
        fun instructionPaths(): List<SimulationCase> =
            listOf(
            SimulationCase("JEQ true", branch(BpfInstruction.Comparison.EQUAL, 42), syscallNr = 42, expected = 0x7fff0000),
            SimulationCase("JEQ false", branch(BpfInstruction.Comparison.EQUAL, 42), syscallNr = 7, expected = 0x00050001),
            SimulationCase("JGT unsigned", branch(BpfInstruction.Comparison.GREATER_THAN, 1), syscallNr = -1, expected = 0x7fff0000),
            SimulationCase("JGT false", branch(BpfInstruction.Comparison.GREATER_THAN, 1), syscallNr = 1, expected = 0x00050001),
            SimulationCase("JSET true", branch(BpfInstruction.Comparison.BITS_SET, 0x80, 16), args = longArrayOf(0x80), expected = 0x7fff0000),
            SimulationCase("JSET false", branch(BpfInstruction.Comparison.BITS_SET, 0x80, 16), expected = 0x00050001),
            SimulationCase("JA uses k", listOf(BpfInstruction.Ja(1), deny, allow), expected = 0x7fff0000),
            SimulationCase(
                "ALU masks low word",
                listOf(BpfInstruction.Ld(16), BpfInstruction.Alu(0xff), BpfInstruction.Jmp(BpfInstruction.Comparison.EQUAL, 0, 1, 0xab), allow, deny),
                args = longArrayOf(0x12345678000000ab),
                expected = 0x7fff0000,
            ),
            SimulationCase("high word", branch(BpfInstruction.Comparison.EQUAL, 0x12345678, 20), args = longArrayOf(0x12345678000000ab), expected = 0x7fff0000),
            SimulationCase("missing arguments are zero", branch(BpfInstruction.Comparison.EQUAL, 0, 56), args = LongArray(0), expected = 0x7fff0000),
            SimulationCase("architecture", branch(BpfInstruction.Comparison.EQUAL, Arch.AARCH64.audit, 4), auditToken = Arch.AARCH64.audit, expected = 0x7fff0000),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("instructionPaths")
    fun `simulator follows BPF instruction paths`(testCase: SimulationCase) {
        assertEquals(testCase.expected, BpfSimulator.simulate(testCase.program, testCase.syscallNr, testCase.auditToken, testCase.args))
    }

    @Test
    fun `structural probes exclude named syscall numbers`() {
        for (excludedNr in listOf(0, SyscallProbeMatrix.SYNTHETIC_MID_NR, SyscallProbeMatrix.SYNTHETIC_HIGH_NR)) {
            assertEquals(false, SyscallProbeMatrix.structural(Arch.AMD64, setOf(excludedNr)).any { it.nr == excludedNr })
        }
    }

    @Test
    fun `matched probes retain valid policy syscall numbers`() {
        assertEquals(listOf(SyscallProbeMatrix.Probe(1, "policy-nr-1"), SyscallProbeMatrix.Probe(7, "policy-nr-7")), SyscallProbeMatrix.matched(Arch.AMD64, listOf(1, -1, 7)))
        assertEquals(emptyList(), SyscallProbeMatrix.matched(Arch.AMD64, emptyList()))
    }

    @Test
    fun `programs without a return surface an undecided result`() {
        assertNull(BpfSimulator.simulate(listOf(BpfInstruction.Ld(0)), 0, Arch.AMD64))
    }

    @Test
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
