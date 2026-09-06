package io.mazewall.profiler.tierE.ringbuf

import kotlin.test.Test
import kotlin.test.assertEquals

class BpfRingBufferReaderTest {
    @Test
    fun `record cursor includes header and rounds to eight bytes`() {
        assertEquals(96L, BpfRingBufferReader.alignedRecordSize(SyscallInvocationEvent.SIZE_BYTES))
        assertEquals(16L, BpfRingBufferReader.alignedRecordSize(1))
    }
}
