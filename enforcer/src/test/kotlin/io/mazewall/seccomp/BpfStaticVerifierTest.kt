package io.mazewall.seccomp

import io.mazewall.core.Arch
import io.mazewall.ffi.NativeConstants
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class BpfStaticVerifierTest {

    @Test
    fun `valid program verifies successfully`() {
        val program = BpfProgram.dsl(Arch.AMD64) {
            val label = createLabel()
            loadAbsolute(0)
            jumpIfEqual(10, jt = label)
            ret(0)
            mark(label)
            allow()
        }

        // It compiled into an unverified program
        val unverified: BpfProgram<BpfStatus.Unverified> = program

        // Passing it to verifier should succeed and return Verified status
        val verified: BpfProgram<BpfStatus.Verified> = BpfStaticVerifier.verify(unverified)
        assertNotNull(verified)
        assertEquals(unverified.instructions, verified.instructions)
    }

    @Test
    fun `empty program throws verification exception`() {
        val emptyProgram = BpfProgram<BpfStatus.Unverified>(emptyList())
        val exception = assertFailsWith<IllegalArgumentException> {
            BpfStaticVerifier.verify(emptyProgram)
        }
        assertEquals("BPF verification failed: program is empty", exception.message)
    }

    @Test
    fun `program exceeding max instructions throws verification exception`() {
        val excessiveInstructions = List(NativeConstants.BPF_MAXINSNS + 1) {
            BpfInstruction.Ld(0x20.toShort(), 0)
        }
        val excessiveProgram = BpfProgram<BpfStatus.Unverified>(excessiveInstructions)
        val exception = assertFailsWith<IllegalArgumentException> {
            BpfStaticVerifier.verify(excessiveProgram)
        }
        assertEquals(
            "BPF verification failed: program size (${excessiveInstructions.size}) exceeds limit of ${NativeConstants.BPF_MAXINSNS} instructions",
            exception.message
        )
    }

    @Test
    fun `program with out of bounds jump throws verification exception`() {
        // Index 0: Jmp with jt = 5 (target is 0 + 1 + 5 = 6), jf = 0 (target is 0 + 1 + 0 = 1)
        // Index 1: Ret
        val instructions = listOf(
            BpfInstruction.Jmp(0x15.toShort(), 5.toShort(), 0.toShort(), 0),
            BpfInstruction.Ret(0x06.toShort(), 0)
        )
        val badProgram = BpfProgram<BpfStatus.Unverified>(instructions)
        val exception = assertFailsWith<IllegalArgumentException> {
            BpfStaticVerifier.verify(badProgram)
        }
        assertEquals("BPF verification failed: instruction index 6 is out of bounds", exception.message)
    }

    @Test
    fun `program with fallthrough off the end throws verification exception`() {
        // Index 0: Ld (falls through to index 1, which is out of bounds)
        val instructions = listOf(
            BpfInstruction.Ld(0x20.toShort(), 0)
        )
        val badProgram = BpfProgram<BpfStatus.Unverified>(instructions)
        val exception = assertFailsWith<IllegalArgumentException> {
            BpfStaticVerifier.verify(badProgram)
        }
        assertEquals("BPF verification failed: instruction index 1 is out of bounds", exception.message)
    }
}
