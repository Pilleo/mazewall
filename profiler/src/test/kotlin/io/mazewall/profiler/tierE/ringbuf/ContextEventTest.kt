package io.mazewall.profiler.tierE.ringbuf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class ContextEventTest {

    private fun encode(
        ktime: ULong = 0x1122334455667788uL,
        tgid: UInt = 1000u,
        tid: UInt = 2002u,
        nr: Int = 257, // openat
        ctx: UInt = 42u,
    ): ByteArray {
        val b = ByteArray(ContextEvent.SIZE_BYTES)
        fun put64(at: Int, v: ULong) { for (i in 0..7) b[at + i] = (v shr (8 * i)).toByte() }
        fun put32(at: Int, v: UInt) { for (i in 0..3) b[at + i] = (v shr (8 * i)).toByte() }
        put64(0, ktime)
        put32(8, tgid)
        put32(12, tid)
        put32(16, nr.toUInt())
        put32(20, ctx)
        return b
    }

    @Test
    fun `wire size is 24 bytes`() {
        assertEquals(24, ContextEvent.SIZE_BYTES)
    }

    @Test
    fun `little-endian round trip preserves every field`() {
        val event = ContextEvent.fromBytes(encode())
        assertEquals(0x1122334455667788uL, event.ktimeNs)
        assertEquals(1000u, event.tgid)
        assertEquals(2002u, event.tid)
        assertEquals(257, event.syscallNr)
        assertEquals(42u, event.contextId)
    }

    @Test
    fun `negative syscall numbers survive the s32 wire type`() {
        val bytes = encode(nr = -1) // e.g. non-syscall marker
        assertEquals(-1, ContextEvent.fromBytes(bytes).syscallNr)
    }

    @Test
    fun `reads only the first record when buffer is longer`() {
        val two = encode() + encode(ctx = 7u)
        val first = ContextEvent.fromBytes(two)
        assertEquals(42u, first.contextId)
    }

    @Test
    fun `short buffer rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ContextEvent.fromBytes(ByteArray(ContextEvent.SIZE_BYTES - 1))
        }
    }
}
