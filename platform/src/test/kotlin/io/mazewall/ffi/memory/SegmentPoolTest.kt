package io.mazewall.ffi.memory

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.ffi.Layouts
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Test
import java.lang.foreign.ValueLayout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SegmentPoolTest {
    @Test
    fun `rentals are zeroed including retained overflow allocations`() {
        SegmentPool.owned(32L, poolSize = 1).use { pool ->
            val first = pool.rent()
            val overflow = pool.rent()
            val firstSegment = first.segment
            val overflowSegment = overflow.segment
            first.segment.writeInt(0L, 42)
            overflow.segment.writeInt(0L, 84)
            first.close()
            overflow.close()
            pool.rent().use { next ->
                pool.rent().use { nextOverflow ->
                    assertSame(firstSegment, next.segment)
                    assertSame(overflowSegment, nextOverflow.segment)
                    assertEquals(0, next.segment.readInt(0L))
                    assertEquals(0, nextOverflow.segment.readInt(0L))
                    assertEquals(32L, next.segment.byteSize())
                }
            }
        }
    }

    @Test
    fun `layout rentals preserve alignment and size`() {
        SegmentPool.owned(ValueLayout.JAVA_LONG, poolSize = 1).use { pool ->
            pool.rent().use { lease ->
                assertEquals(8L, lease.segment.byteSize())
                assertEquals(0L, lease.segment.address() % ValueLayout.JAVA_LONG.byteAlignment())
                lease.segment.writeLong(0L, 123L)
            }
            pool.rent().use { assertEquals(0L, it.segment.readLong(0L)) }
        }
    }

    @Test
    fun `global pools rent separate correctly sized leases`() {
        SegmentPool.SECCOMP_NOTIF_POOL.rent().use { notif ->
            SegmentPool.SECCOMP_NOTIF_RESP_POOL.rent().use { resp ->
                assertEquals(Layouts.SECCOMP_NOTIF_SIZE, notif.segment.byteSize())
                assertEquals(Layouts.SECCOMP_NOTIF_RESP_SIZE, resp.segment.byteSize())
            }
        }
    }

    @Test
    fun `concurrent rental never shares an active native address`() {
        SegmentPool.owned(64L, poolSize = 2).use { pool ->
            Executors.newFixedThreadPool(8).use { executor ->
                val active = ConcurrentHashMap.newKeySet<Long>()
                val futures = (1..8).map {
                    executor.submit {
                        repeat(500) {
                            pool.rent().use { lease ->
                                val address = lease.segment.address()
                                assertTrue(active.add(address), "Address already has an active lease")
                                try {
                                    assertEquals(0, lease.segment.readInt(0))
                                    lease.segment.writeInt(0, 999)
                                } finally {
                                    assertTrue(active.remove(address))
                                }
                            }
                        }
                    }
                }
                futures.forEach { it.get(10, TimeUnit.SECONDS) }
                assertTrue(active.isEmpty())
            }
        }
    }
}
