package io.mazewall.portal

import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortalBrokerOwnershipTest {
    @Test
    fun `close cancels an already owned startup and refuses ready publication`() {
        val owner = PortalBrokerOwnership<AutoCloseable>(5_000L)
        val starting = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val closes = AtomicInteger()
        Executors.newSingleThreadExecutor().use { thread ->
            val acquisition = thread.submit {
                owner.acquire("group", {
                    AutoCloseable {
                    closes.incrementAndGet()
                    stopped.countDown()
                }
                }) {
                    starting.countDown()
                    check(stopped.await(5, TimeUnit.SECONDS))
                }
            }
            check(starting.await(5, TimeUnit.SECONDS))
            owner.close()
            assertFailsWith<java.util.concurrent.ExecutionException> { acquisition.get(5, TimeUnit.SECONDS) }
            assertEquals(1, closes.get())
        }
    }

    @Test
    fun `fatal rollback failure is not downgraded beneath ordinary startup failure`() {
        val owner = PortalBrokerOwnership<AutoCloseable>(5_000L)
        val ordinary = IOException("startup failure")
        val fatal = AssertionError("rollback fatal")
        val calls = AtomicInteger()
        val failure = assertFailsWith<AssertionError> {
            owner.acquire("group", { AutoCloseable { if (calls.incrementAndGet() == 1) throw fatal } }) { throw ordinary }
        }
        assertTrue(failure === fatal)
        assertTrue(fatal.suppressed.any { it === ordinary })
        owner.close()
    }

    @Test
    fun `close joins pending acquisition and prevents late startup publication`() {
        val owner = PortalBrokerOwnership<AutoCloseable>(5_000L)
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val starts = AtomicInteger()
        val closes = AtomicInteger()
        Executors.newFixedThreadPool(2).use { threads ->
            try {
                val acquisition = threads.submit {
                    owner.acquire("group", {
                        acquired.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        AutoCloseable { closes.incrementAndGet() }
                    }) { starts.incrementAndGet() }
                }
                check(acquired.await(5, TimeUnit.SECONDS))
                val closing = threads.submit { owner.close() }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (owner.isAccepting() && System.nanoTime() < deadline) Thread.yield()
                assertFalse(owner.isAccepting())
                assertFalse(closing.isDone)
                release.countDown()
                assertFailsWith<java.util.concurrent.ExecutionException> { acquisition.get(5, TimeUnit.SECONDS) }
                closing.get(5, TimeUnit.SECONDS)
                assertEquals(0, starts.get())
                assertEquals(1, closes.get())
                assertFailsWith<PortalRuntimeClosedException> { owner.acquire("other", { AutoCloseable {} }) {} }
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `failed cleanup retains owner and retry finishes without false closed state`() {
        val owner = PortalBrokerOwnership<AutoCloseable>(5_000L)
        val attempts = AtomicInteger()
        owner.acquire("group", { AutoCloseable { if (attempts.incrementAndGet() == 1) throw IOException("close-before-native-dispatch") } }) {}
        assertFailsWith<IOException> { owner.close() }
        assertFalse(owner.isAccepting())
        owner.close()
        owner.close()
        assertEquals(2, attempts.get())
    }

    @Test
    fun `lost state cannot reopen on a delayed completion event`() {
        assertEquals(StatefulGroupPhase.STATE_LOST, PortalStatefulAdmission.evaluate(StatefulGroupPhase.STATE_LOST, StatefulGroupEvent.COMPLETED))
        assertEquals(PortalOwnershipPhase.CLOSED, PortalRuntimeOwnershipLifecycle.evaluate(PortalOwnershipPhase.CLOSED, PortalOwnershipEvent.CLOSE_FAILED))
    }

    @Test
    fun `startup fatal identity survives resource retirement`() {
        val owner = PortalBrokerOwnership<AutoCloseable>(5_000L)
        val fatal = AssertionError("startup fatal")
        val closes = AtomicInteger()
        val failure = assertFailsWith<AssertionError> {
            owner.acquire("group", { AutoCloseable { closes.incrementAndGet() } }) { throw fatal }
        }
        assertTrue(failure === fatal)
        owner.close()
        assertEquals(1, closes.get())
    }
}
