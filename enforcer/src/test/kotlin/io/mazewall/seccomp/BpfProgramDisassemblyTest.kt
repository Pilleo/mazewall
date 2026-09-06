package io.mazewall.seccomp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BpfProgramDisassemblyTest {

    @Test
    fun `disassemble renders indexed classic BPF mnemonics`() {
        val program = BpfProgram<BpfStatus.Unverified>(
            listOf(
                BpfInstruction.Ld(0x20, 4),
                BpfInstruction.Jmp(0x15, 1, 2, 59),
                BpfInstruction.Alu(0x54, 0xff),
                BpfInstruction.Ret(0x06, 0x7fff0000),
                BpfInstruction.Jmp(0x05, 0, 0, 3),
            ),
        )

        assertEquals(
            """
            0000: ld [4]
            0001: jeq #59, +1, +2
            0002: and #0xff
            0003: ret #0x7fff0000
            0004: ja +3
            """.trimIndent(),
            program.disassemble(),
        )
    }

    @Test
    fun `disassemble preserves unknown instructions as raw fields`() {
        val program = BpfProgram<BpfStatus.Unverified>(
            listOf(BpfInstruction.Jmp(0x35, 7, 9, 0x1234)),
        )

        assertEquals(
            "0000: raw(code=0x35, jt=7, jf=9, k=0x1234)",
            program.disassemble(),
        )
    }
}
