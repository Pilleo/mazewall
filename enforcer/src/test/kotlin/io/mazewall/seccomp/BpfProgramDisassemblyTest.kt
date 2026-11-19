package io.mazewall.seccomp

import io.mazewall.platform.syscall.SeccompAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BpfProgramDisassemblyTest {
    @Test
    fun `disassemble renders indexed classic BPF mnemonics`() {
        val program = BpfProgram.unverified(
            listOf(
                BpfInstruction.Ld(4),
                BpfInstruction.Jmp(BpfInstruction.Comparison.EQUAL, 1, 2, 59),
                BpfInstruction.Alu(0xff),
                BpfInstruction.Ret(SeccompAction.ACT_ALLOW),
                BpfInstruction.Ja(3),
                BpfInstruction.Jmp(BpfInstruction.Comparison.GREATER_THAN, 0, 1, 42),
                BpfInstruction.Jmp(BpfInstruction.Comparison.BITS_SET, 1, 0, 4),
            ),
        )
        assertEquals(
            """
            0000: ld [4]
            0001: jeq #59, +1, +2
            0002: and #0xff
            0003: ret #0x7fff0000
            0004: ja +3
            0005: jgt #42, +0, +1
            0006: jset #4, +1, +0
            """.trimIndent(),
            program.disassemble(),
        )
    }
}
