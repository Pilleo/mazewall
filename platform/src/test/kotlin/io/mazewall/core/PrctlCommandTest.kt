package io.mazewall.core

import io.mazewall.ffi.memory.ManagedSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class PrctlCommandTest {

    data class PrctlTestCase(
        val name: String,
        val command: PrctlCommand,
        val expectedOption: Int,
        val expectedArg2: NativeArg,
        val expectedArg3: NativeArg = NativeArg.LongArg(0L),
    ) {
        override fun toString(): String = name
    }

    companion object {
        @JvmStatic
        fun prctlTestCases(): Stream<PrctlTestCase> {
            val memArg = NativeArg.MemoryArg(ManagedSegment.NULL)
            return Stream.of(
                PrctlTestCase("SetNoNewPrivs(true)", PrctlCommand.SetNoNewPrivs(true), 38, NativeArg.LongArg(1L)),
                PrctlTestCase("SetNoNewPrivs(false)", PrctlCommand.SetNoNewPrivs(false), 38, NativeArg.LongArg(0L)),
                PrctlTestCase("GetNoNewPrivs", PrctlCommand.GetNoNewPrivs, 39, NativeArg.LongArg(0L)),
                PrctlTestCase("SetSeccomp(2)", PrctlCommand.SetSeccomp(2), 22, NativeArg.LongArg(2L), NativeArg.NullArg),
                PrctlTestCase("GetSeccomp", PrctlCommand.GetSeccomp, 21, NativeArg.LongArg(0L)),
                PrctlTestCase("SetName", PrctlCommand.SetName(memArg), 15, memArg),
                PrctlTestCase("GetName", PrctlCommand.GetName(memArg), 16, memArg),
                PrctlTestCase("SetMm(1)", PrctlCommand.SetMm(1), 25, NativeArg.LongArg(1L)),
                PrctlTestCase("CapAmbient(1, 2)", PrctlCommand.CapAmbient(1, 2), 47, NativeArg.LongArg(1L), NativeArg.LongArg(2L)),
                PrctlTestCase("SetPtracer(1)", PrctlCommand.SetPtracer(1), 0x59616d61, NativeArg.LongArg(1L)),
                PrctlTestCase("SetPdeathsig(9)", PrctlCommand.SetPdeathsig(9), 1, NativeArg.LongArg(9L)),
            )
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("prctlTestCases")
    fun `verify PrctlCommand option and arguments`(testCase: PrctlTestCase) {
        assertEquals(testCase.expectedOption, testCase.command.option, "option should match")
        assertEquals(testCase.expectedArg2, testCase.command.arg2, "arg2 should match")
        assertEquals(testCase.expectedArg3, testCase.command.arg3, "arg3 should match")
    }
}
