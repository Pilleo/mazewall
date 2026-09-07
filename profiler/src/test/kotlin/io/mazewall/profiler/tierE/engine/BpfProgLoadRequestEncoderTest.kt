package io.mazewall.profiler.tierE.engine

import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class BpfProgLoadRequestEncoderTest {
    @Test
    fun `encodes program load attributes and substitutes pseudo map descriptors`() {
        Arena.ofConfined().use { arena ->
            val request =
                BpfProgLoadRequestEncoder.encode(
                    arena = arena,
                    program = listOf(TierEbpfEngine.Insn(code = 0x18, src = 1, imm = 0)),
                    mapFds = listOf(41),
                    programType = 17,
                    programName = "tier_e",
                )

            assertEquals(17, request.attr.get(ValueLayout.JAVA_INT, BpfProgLoadLayout.PROGRAM_TYPE))
            assertEquals(1, request.attr.get(ValueLayout.JAVA_INT, BpfProgLoadLayout.INSTRUCTION_COUNT))
            assertEquals(41L shl 32 or 0x1018L, request.instructions.get(ValueLayout.JAVA_LONG, 0))
            assertEquals("tier_e", request.attr.asSlice(BpfProgLoadLayout.PROGRAM_NAME).getString(0))
        }
    }
}
