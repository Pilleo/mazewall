package io.mazewall.profiler.tierE.collector
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LiveEbpfCollectorTest {
    @Test
    fun `named raw collector and compatibility shim have the same immutable snapshots`() {
        val named = TierERingBufferCollector()
        val shim = LiveEbpfCollector()
        named.record(1, 2, 3u, 4)
        shim.record(1, 2, 3u, 4)

        assertEquals(named.snapshot(), shim.snapshot())
        assertFailsWith<UnsupportedOperationException> {
            (named.snapshot().events as MutableList).clear()
        }
    }

    @Test
    fun `snapshot atomically includes events and drop completeness`() {
        val collector = LiveEbpfCollector()
        collector.record(1, 2, 3u, 4)
        collector.recordDrop()

        val snapshot = collector.snapshot()

        assertEquals(1, snapshot.events.size)
        assertEquals(1, snapshot.droppedCount)
        assertFalse(snapshot.drainComplete)
    }
}
