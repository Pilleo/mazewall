package io.mazewall.ffi.memory

import io.mazewall.ffi.Layouts
import org.junit.jupiter.api.Test
import java.lang.foreign.ValueLayout
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SegmentPoolTest {

    @Test
    fun `test SegmentPool size-based allocation renting and releasing`() {
        val pool = SegmentPool(32L, poolSize = 3)

        // Rent 3 segments
        val s1 = pool.rent()
        val s2 = pool.rent()
        val s3 = pool.rent()

        assertEquals(32L, s1.byteSize())
        assertEquals(32L, s2.byteSize())
        assertEquals(32L, s3.byteSize())

        // Modify segments
        s1.writeInt(0L, 42)
        s2.writeInt(0L, 84)

        // Release them back
        pool.release(s1)
        pool.release(s2)
        pool.release(s3)

        // Rent again - should get the same segments (zero-initialized)
        val r1 = pool.rent()
        val r2 = pool.rent()

        assertEquals(0, r1.readInt(0L))
        assertEquals(0, r2.readInt(0L))
    }

    @Test
    fun `test SegmentPool layout-based allocation renting and releasing`() {
        val pool = SegmentPool(ValueLayout.JAVA_LONG, poolSize = 2)

        val s1 = pool.rent()
        assertEquals(8L, s1.byteSize())

        s1.writeLong(0L, 123456789L)
        pool.release(s1)

        val s2 = pool.rent()
        assertEquals(0L, s2.readLong(0L))
    }

    @Test
    fun `test SegmentPool exhausted fallback behavior`() {
        val pool = SegmentPool(16L, poolSize = 1)

        val s1 = pool.rent() // Rented the only pooled segment
        val s2 = pool.rent() // Fallback segment (allocated on demand)

        assertEquals(16L, s1.byteSize())
        assertEquals(16L, s2.byteSize())

        // Release both
        pool.release(s1)
        pool.release(s2)

        val s3 = pool.rent()
        val s4 = pool.rent()

        assertEquals(16L, s3.byteSize())
        assertEquals(16L, s4.byteSize())
        assertSame(s1, s3)
        assertSame(s2, s4, "Overflow segments must be retained for reuse")
    }

    @Test
    fun `test SegmentPool global pools validation`() {
        val notifPool = SegmentPool.SECCOMP_NOTIF_POOL
        val respPool = SegmentPool.SECCOMP_NOTIF_RESP_POOL

        assertNotNull(notifPool)
        assertNotNull(respPool)

        assertEquals(Layouts.SECCOMP_NOTIF_SIZE, notifPool.byteSize)
        assertEquals(Layouts.SECCOMP_NOTIF_RESP_SIZE, respPool.byteSize)

        val notifSeg = notifPool.rent()
        val respSeg = respPool.rent()

        assertEquals(Layouts.SECCOMP_NOTIF_SIZE, notifSeg.byteSize())
        assertEquals(Layouts.SECCOMP_NOTIF_RESP_SIZE, respSeg.byteSize())

        notifPool.release(notifSeg)
        respPool.release(respSeg)
    }

    @Test
    fun `test SegmentPool concurrent renting and releasing stress test`() {
        val poolSize = 10
        val pool = SegmentPool(64L, poolSize = poolSize)
        val threadCount = 16
        val iterations = 1000

        val executor = Executors.newFixedThreadPool(threadCount)
        val rentedCount = AtomicInteger(0)

        for (i in 0 until threadCount) {
            executor.submit {
                for (j in 0 until iterations) {
                    val seg = pool.rent()
                    assertEquals(64L, seg.byteSize())
                    assertEquals(0, seg.readInt(0L))

                    seg.writeInt(0L, 999)
                    rentedCount.incrementAndGet()

                    pool.release(seg)
                }
            }
        }

        executor.shutdown()
        val finished = executor.awaitTermination(5, TimeUnit.SECONDS)
        assertTrue(finished, "Stress test threads should complete execution")
        assertEquals(threadCount * iterations, rentedCount.get())

        // Verify we can still rent clean segments
        val finalSeg = pool.rent()
        assertEquals(0, finalSeg.readInt(0L))
        pool.release(finalSeg)
    }
}
