package io.mazewall.profiler.internal.attribution

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoverageRecoveryScannerTest {
    private fun entry(
        tag: Int = 0x4d5a,
        stack: Int = 7,
        syscall: Int = 257,
        flags: Int = 0,
        invocation: Long = 55,
        sequence: Long = 2,
    ): RecoveryMapEntry {
        val key = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        key
            .putInt(tag)
            .putInt(stack)
            .putInt(syscall)
            .putInt(flags)
        val value = ByteBuffer.allocate(88).order(ByteOrder.LITTLE_ENDIAN)
        value
            .putLong(123L)
            .putInt(100)
            .putInt(101)
            .putInt(syscall)
            .putInt(flags)
        value.putLong(invocation).putLong(sequence)
        repeat(6) { value.putLong(it.toLong()) }
        return RecoveryMapEntry(key.array(), value.array())
    }

    private fun chunkedReader(entries: List<RecoveryMapEntry>): RecoveryMapReader =
        RecoveryMapReader { cursor, maxEntries ->
            val start = cursor?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int } ?: 0
            val page = entries.drop(start).take(maxEntries)
            val next = start + page.size
            if (next >= entries.size) {
                RecoveryScanPage(page, null, complete = true)
            } else {
                val cursorBytes = ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(next)
                    .array()
                RecoveryScanPage(page, cursorBytes, complete = false)
            }
        }

    @Test
    fun `live sweep pages within batch bound and restarts on interval`() {
        var now = 0L
        val entries = List(300) { entry(stack = it) }
        val scanner = CoverageRecoveryScanner(chunkedReader(entries), maxBatchEntries = 256, nanoTime = { now })

        val first = scanner.poll()
        assertEquals(256, first.entries.size)
        assertEquals(false, first.sweepComplete)

        val second = scanner.poll()
        assertEquals(44, second.entries.size)
        assertEquals(true, second.sweepComplete)

        // A new sweep starts only after the interval.
        now += 50_000_000L
        assertTrue(scanner.poll().entries.isEmpty())
        now += 60_000_000L
        assertEquals(256, scanner.poll().entries.size)
    }

    @Test
    fun `final scan terminates within its bound after quiescence`() {
        val entries = List(10) { entry(stack = it) }
        val scanner = CoverageRecoveryScanner(chunkedReader(entries), maxBatchEntries = 4, nanoTime = { 0L })

        val final = scanner.scanFinal(maxCapacity = 4096)
        assertEquals(10, final.entries.size)
        assertEquals(true, final.sweepComplete)
    }

    @Test
    fun `non terminating final scan fails closed`() {
        val reader = RecoveryMapReader { cursor, _ ->
            RecoveryScanPage(listOf(entry()), cursor ?: ByteArray(4), complete = false)
        }
        val scanner = CoverageRecoveryScanner(reader, maxBatchEntries = 4, nanoTime = { 0L })

        assertFailsWith<IllegalStateException> { scanner.scanFinal(maxCapacity = 8) }
    }

    @Test
    fun `key value mismatch never becomes a candidate`() {
        val good = entry()
        val decoded = decodeRecoveryKey(good.key)
        assertEquals(0x4d5a, decoded.sessionTag)
        assertEquals(7L, decoded.stackTraceId)

        val candidate = good.toCandidate(SessionEpoch(19802), ProcessIncarnation(999))
        assertEquals(StackTraceId(7), candidate?.canonicalStackTraceId)

        val corrupt = RecoveryMapEntry(good.key, good.value.copyOf().also { it[16] = 99 })
        assertEquals(null, corrupt.toCandidate(SessionEpoch(19802), ProcessIncarnation(999)))
    }
}
