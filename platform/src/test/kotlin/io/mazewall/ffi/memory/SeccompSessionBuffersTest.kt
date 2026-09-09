package io.mazewall.ffi.memory

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SeccompSessionBuffersTest {
    @Test
    fun `failed response rental returns notification lease`() {
        SegmentPool.owned(80L, poolSize = 1).use { notif ->
            val resp = SegmentPool.owned(24L, poolSize = 1)
            resp.close()
            assertFailsWith<IllegalStateException> { SeccompSessionBuffers.acquire(notif, resp) }
            // Closing succeeds only if the partially acquired notification lease was returned.
            notif.close()
        }
    }

    @Test
    fun `session releases both rentals and its confined arena`() {
        SegmentPool.owned(80L).use { notif ->
            SegmentPool.owned(24L).use { resp ->
                val buffers = SeccompSessionBuffers.acquire(notif, resp)
                buffers.notifLease.close()
                buffers.respLease.close()
                buffers.arena.close()
                assertFalse(buffers.arena.isAlive)
                notif.close()
                resp.close()
            }
        }
    }
}
