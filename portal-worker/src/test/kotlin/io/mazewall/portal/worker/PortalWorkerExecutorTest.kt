package io.mazewall.portal.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PortalWorkerExecutorTest {
    @Test
    fun `executor rejects work after its bounded queue is full`() {
        val executor = PortalWorkerExecutor.create(1)
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            executor.submit { entered.countDown(); release.await() }
            check(entered.await(1, TimeUnit.SECONDS))
            executor.submit { }
            assertFailsWith<java.util.concurrent.RejectedExecutionException> {
                executor.submit { }
            }
            release.countDown()
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
