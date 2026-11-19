package io.mazewall.seccomp

import io.mazewall.platform.syscall.SeccompAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BpfInstructionTest {
    @Test
    fun `word loads reject unaligned or out of bounds seccomp data`() {
        for (offset in listOf(-4, -1, 1, 2, 3, 61, 64, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { BpfInstruction.Ld(offset) }
            assertFailsWith<IllegalArgumentException> { BpfInstruction.Ld(0).copy(k = offset) }
        }
        for (offset in 0..60 step 4) assertEquals(offset, BpfInstruction.Ld(offset).k)
    }

    @Test
    fun `conditional offsets cannot overflow unsigned eight bit fields`() {
        val jump = BpfInstruction.Jmp(BpfInstruction.Comparison.EQUAL, 0, 255, -1)
        for (offset in listOf(-1, 256, Short.MAX_VALUE.toInt()).map { it.toShort() }) {
            assertFailsWith<IllegalArgumentException> { jump.copy(jt = offset) }
            assertFailsWith<IllegalArgumentException> { jump.copy(jf = offset) }
        }
        assertEquals(255, jump.jf.toInt())
        assertEquals(-1, jump.k)
    }

    @Test
    fun `JA has a wide skip count and no conditional offsets`() {
        val jump = BpfInstruction.Ja(4094)
        assertEquals(0x05.toShort(), jump.code)
        assertEquals(4094, jump.k)
        assertEquals(0, jump.jt.toInt())
        assertEquals(0, jump.jf.toInt())
        assertFailsWith<IllegalArgumentException> { jump.copy(k = -1) }
    }

    @Test
    fun `return instruction preserves action payload`() {
        assertEquals(0x7ff01234, BpfInstruction.Ret(SeccompAction.ACT_TRACE(0x1234)).k)
        assertEquals(0x0005000d, BpfInstruction.Ret(SeccompAction.ACT_ERRNO(13)).k)
    }
}
