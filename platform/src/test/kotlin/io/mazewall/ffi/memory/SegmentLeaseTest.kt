package io.mazewall.ffi.memory

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Test
import java.lang.foreign.ValueLayout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SegmentLeaseTest {
    @Test
    fun `stale lease cannot release a later rental of the same buffer`() {
        SegmentPool.owned(16L, poolSize = 1).use { pool ->
            val stale = pool.rent()
            val first = stale.segment
            stale.close()
            pool.rent().use { current ->
                assertSame(first, current.segment)
                assertNotEquals(stale.generation, current.generation)
                stale.close()
                assertFailsWith<IllegalStateException> { stale.segment }
                pool.rent().use { concurrent ->
                    assertNotEquals(current.segment.address(), concurrent.segment.address())
                }
            }
        }
    }

    @Test
    fun `outstanding close rejects atomically and permits retry`() {
        val pool = SegmentPool.owned(16L, poolSize = 1)
        val lease = pool.rent()
        val segment = lease.segment
        try {
            assertFailsWith<IllegalStateException> { pool.close() }
            segment.writeInt(0, 7)
            pool.rent().use { assertEquals(0, it.segment.readInt(0)) }
            assertEquals(7, lease.segment.readInt(0))
        } finally {
            lease.close()
            pool.close()
        }
        pool.close()
        lease.close()
        assertFailsWith<IllegalStateException> { segment.readInt(0) }
        assertFailsWith<IllegalStateException> { pool.rent() }
        assertFailsWith<IllegalStateException> { lease.segment }
    }

    @Test
    fun `borrowed pool closes without closing its arena`() {
        NativeArena.ofConfined().use { arena ->
            val pool = SegmentPool.borrowed(16L, arena, poolSize = 1)
            val lease = pool.rent()
            assertFailsWith<IllegalStateException> { pool.close() }
            lease.close()
            pool.close()
            assertTrue(arena.isAlive)
            arena.allocate(8).writeLong(0, 42)
            assertFailsWith<IllegalStateException> { pool.rent() }
        }
        NativeArena.ofShared().use { arena ->
            SegmentPool.borrowed(ValueLayout.JAVA_LONG, arena).use { pool ->
                pool.rent().use { assertEquals(8L, it.segment.byteSize()) }
            }
            assertTrue(arena.isAlive)
        }
    }

    @Test
    fun `foreign and wrong sized leases cannot be returned to another pool`() {
        SegmentPool.owned(16L).use { owner ->
            SegmentPool.owned(16L).use { other ->
                SegmentPool.owned(8L).use { wrongSize ->
                    owner.rent().use { lease ->
                        assertFailsWith<IllegalArgumentException> { other.returnLease(lease) }
                        assertFailsWith<IllegalArgumentException> { wrongSize.returnLease(lease) }
                        lease.segment.writeInt(0, 19)
                        assertEquals(19, lease.segment.readInt(0))
                    }
                }
            }
        }
    }

    @Test
    fun `raw releases and ambiguous constructors are absent`() {
        assertTrue(
            SegmentPool::class.java.constructors
            .filterNot { it.isSynthetic }
            .isEmpty(),
        )
        assertFalse(
            SegmentPool::class.java.methods.any { method ->
            method.parameterTypes.any { it == ManagedSegment::class.java }
        },
        )
    }

    @Test
    fun `borrowed arena closed externally rejects new rent`() {
        val arena = NativeArena.ofShared()
        val pool = SegmentPool.borrowed(8L, arena)
        arena.close()
        assertFailsWith<IllegalStateException> { pool.rent() }
        pool.close()
    }

    @Test
    fun `invalid dimensions reject before taking arena ownership`() {
        assertFailsWith<IllegalArgumentException> { SegmentPool.owned(0L) }
        assertFailsWith<IllegalArgumentException> { SegmentPool.owned(-1L) }
        assertFailsWith<IllegalArgumentException> { SegmentPool.owned(8L, poolSize = -1) }
        NativeArena.ofShared().use { arena ->
            assertFailsWith<IllegalArgumentException> { SegmentPool.borrowed(0L, arena) }
            assertTrue(arena.isAlive)
        }
    }
}
