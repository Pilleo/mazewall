package io.mazewall.core

import io.mazewall.ffi.memory.ManagedSegment
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment

class PrctlCommandTest {

    @Test
    fun `test PrctlCommand objects`() {
        assertEquals(38, PrctlCommand.SetNoNewPrivs(true).option)
        assertEquals(1L, (PrctlCommand.SetNoNewPrivs(true).arg2 as NativeArg.LongArg).value)
        assertEquals(38, PrctlCommand.SetNoNewPrivs(false).option)
        assertEquals(0L, (PrctlCommand.SetNoNewPrivs(false).arg2 as NativeArg.LongArg).value)

        assertEquals(39, PrctlCommand.GetNoNewPrivs.option)
        assertEquals(0L, (PrctlCommand.GetNoNewPrivs.arg2 as NativeArg.LongArg).value)

        assertEquals(22, PrctlCommand.SetSeccomp(2).option)
        assertEquals(2L, (PrctlCommand.SetSeccomp(2).arg2 as NativeArg.LongArg).value)
        assertEquals(21, PrctlCommand.GetSeccomp.option)
        assertEquals(0L, (PrctlCommand.GetSeccomp.arg2 as NativeArg.LongArg).value)

        val memArg = NativeArg.MemoryArg(ManagedSegment.NULL)
        assertEquals(15, PrctlCommand.SetName(memArg).option)
        assertEquals(memArg, PrctlCommand.SetName(memArg).arg2)
        assertEquals(16, PrctlCommand.GetName(memArg).option)
        assertEquals(memArg, PrctlCommand.GetName(memArg).arg2)

        assertEquals(25, PrctlCommand.SetMm(1).option)
        assertEquals(1L, (PrctlCommand.SetMm(1).arg2 as NativeArg.LongArg).value)

        assertEquals(47, PrctlCommand.CapAmbient(1, 2).option)
        assertEquals(1L, (PrctlCommand.CapAmbient(1, 2).arg2 as NativeArg.LongArg).value)
        assertEquals(2L, (PrctlCommand.CapAmbient(1, 2).arg3 as NativeArg.LongArg).value)

        assertEquals(0x59616d61, PrctlCommand.SetPtracer(1).option)
        assertEquals(1L, (PrctlCommand.SetPtracer(1).arg2 as NativeArg.LongArg).value)

        assertEquals(1, PrctlCommand.SetPdeathsig(9).option)
        assertEquals(9L, (PrctlCommand.SetPdeathsig(9).arg2 as NativeArg.LongArg).value)
    }
}
