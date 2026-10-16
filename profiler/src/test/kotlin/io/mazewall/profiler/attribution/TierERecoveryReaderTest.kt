package io.mazewall.profiler.internal.attribution

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TierERecoveryReaderTest {
    private fun entry(stack: Long): RecoveryMapEntry {
        val key = ByteBuffer
            .allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(19802)
            .putLong(stack)
            .putInt(257)
            .putInt(0)
            .array()
        val value = ByteBuffer
            .allocate(96)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(1)
            .putInt(100)
            .putInt(101)
            .putInt(257)
            .putInt(0)
            .putLong(55)
            .putLong(1)
        repeat(6) { value.putLong(0) }
        value.putInt(1).putInt(0)
        return RecoveryMapEntry(key, value.array())
    }

    @Test
    fun `independent reader transfers bounded pages without joining dictionary`() {
        val entries = listOf(entry(1), entry(2))
        val scans = AtomicInteger()
        val repeatedSweep = CountDownLatch(1)
        val scanner = CoverageRecoveryScanner(
            RecoveryMapReader { cursor, _ ->
                if (scans.incrementAndGet() >= 3) repeatedSweep.countDown()
                val index = if (cursor == null) 0 else 1
                RecoveryScanPage(listOf(entries[index]), if (index == 0) entries[0].key else null, index == 1)
            },
            maxBatchEntries = 1,
        )
        val reader = TierERecoveryReader(scanner, intervalMillis = 100)
        reader.start()
        try {
            val deadline = System.nanoTime() + 2_000_000_000L
            var pages = emptyList<List<RecoveryMapEntry>>()
            while (pages.size < 2 && System.nanoTime() < deadline) {
                pages = pages + reader.takeReady()
                Thread.onSpinWait()
            }
            assertEquals(2, pages.size)
            assertEquals(listOf(1L, 2L), pages.flatMap { it }.map { decodeRecoveryKey(it.key).stackTraceId })
            assertTrue(repeatedSweep.await(2, TimeUnit.SECONDS), "reader should attempt a later sweep")
            assertTrue(reader.takeReady().isEmpty(), "repeated sweeps must not requeue retained entries")
        } finally {
            assertTrue(reader.stopAndJoin())
        }
    }
}
