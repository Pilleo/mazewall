package io.mazewall.profiler.internal.attribution
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StackDictionaryTransactionTest {
    @Test
    fun `rollback preserves byte identity and watermark until commit`() {
        val encoder = StackDictionaryStreamEncoder()
        encoder.enqueue(definition(31))

        val first = requireNotNull(encoder.prepareBatch())
        val firstBytes = first.bytes
        assertEquals(0L, encoder.watermark)
        encoder.rollback(first)

        val retry = requireNotNull(encoder.prepareBatch())
        assertContentEquals(firstBytes, retry.bytes)
        assertEquals(first.proposedWatermark, retry.proposedWatermark)
        assertEquals(0L, encoder.watermark)
        encoder.commit(retry)

        assertEquals(31L, encoder.watermark)
    }

    @Test
    fun `abandoned batch never advances watermark and releases staging`() {
        val encoder = StackDictionaryStreamEncoder()
        encoder.enqueue(definition(32))
        val batch = requireNotNull(encoder.prepareBatch())

        encoder.abandon()

        assertFalse(encoder.hasInFlightBatch)
        assertEquals(0, encoder.pendingCount)
        assertEquals(0L, encoder.watermark)
        assertEquals(batch.proposedWatermark, 32L)
    }

    private fun definition(id: Long): StackDefinition =
        StackDefinition(
        StackTraceId(id),
        listOf(ManagedFrame("app", "Foo", "bar", "()V", 1L, ManagedFrameKind.JAVA)),
        StackCaptureQuality.COMPLETE,
    )
}
