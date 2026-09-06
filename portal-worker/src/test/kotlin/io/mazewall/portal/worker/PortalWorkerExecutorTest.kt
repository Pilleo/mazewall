package io.mazewall.portal.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PortalWorkerExecutorTest {
    @Test
    fun `executor rejects work after its bounded queue is full`() {
        val executor = PortalWorkerExecutor.create(1)
        try {
            executor.submit { Thread.sleep(1_000) }
            executor.submit { }
            assertFailsWith<java.util.concurrent.RejectedExecutionException> {
                executor.submit { }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `executor prestarts configured workers`() {
        val executor = PortalWorkerExecutor.create(2)
        try {
            assertEquals(2, executor.poolSize)
        } finally {
            executor.shutdownNow()
        }
    }
}
