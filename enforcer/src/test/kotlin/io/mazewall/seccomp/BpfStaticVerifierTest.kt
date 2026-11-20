package io.mazewall.seccomp

import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BpfStaticVerifierTest {
    @Test
    fun `valid conditional and ALU paths verify`() {
        val program = BpfProgram.dsl(Arch.AMD64) {
            val label = createLabel()
            and(0xff)
            jumpIfEqual(10, jt = label)
            killThread()
            mark(label)
            allow()
        }
        val verified: BpfProgram<BpfStatus.Verified> = BpfStaticVerifier.verify(program)
        assertEquals(program.instructions, verified.instructions)
        assertEquals(0, BpfSimulator.simulate(verified.instructions, 9, Arch.AMD64))
        assertEquals(0x7fff0000, BpfSimulator.simulate(verified.instructions, 10, Arch.AMD64))
    }

    @Test
    fun `empty and oversized programs are rejected but exact limit is accepted`() {
        val ret = BpfInstruction.Ret(SeccompAction.ACT_ALLOW)
        for (size in listOf(0, NativeConstants.BPF_MAXINSNS + 1)) {
            assertFailsWith<IllegalArgumentException> {
                BpfStaticVerifier.verify(BpfProgram.unverified(List(size) { ret }))
            }
        }
        val program = BpfProgram.unverified(List(NativeConstants.BPF_MAXINSNS - 1) { BpfInstruction.Ld(0) } + ret)
        assertEquals(0x7fff0000, BpfSimulator.simulate(BpfStaticVerifier.verify(program).instructions, 0, Arch.AMD64))
    }

    @Test
    fun `both conditional jump destinations are validated with disassembly`() {
        for ((jt, jf) in listOf(5.toShort() to 0.toShort(), 0.toShort() to 5.toShort())) {
            val program = BpfProgram.unverified(
                listOf(BpfInstruction.Jmp(BpfInstruction.Comparison.EQUAL, jt, jf, 0), BpfInstruction.Ret(SeccompAction.ACT_KILL_THREAD)),
            )
            val failure = assertFailsWith<IllegalArgumentException> { BpfStaticVerifier.verify(program) }
            assertTrue(failure.message.orEmpty().contains("instruction index 6 is out of bounds"))
            assertTrue(failure.message.orEmpty().contains(program.disassemble()))
        }
    }

    @Test
    fun `fallthrough off the end is rejected even when unreachable`() {
        val tails = listOf(BpfInstruction.Ld(0), BpfInstruction.Alu(0xff), BpfInstruction.Ja(0))
        for (tail in tails) {
            for (prefix in listOf(emptyList(), listOf(BpfInstruction.Ret(SeccompAction.ACT_ALLOW)))) {
                assertFailsWith<IllegalArgumentException> {
                    BpfStaticVerifier.verify(BpfProgram.unverified(prefix + tail))
                }
            }
        }
    }
}
