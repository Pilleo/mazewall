package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SocketHelperRequestCancellationTest {
    @Test
    fun `pool cleanup failure remains visible and a later close retries unreleased helper`() {
        val fixture = SupervisorBoundaryFixture()
        val closes = AtomicInteger()
        val pool = SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ) = SocketHelperResult.Connected

                override fun close() {
                    if (closes.incrementAndGet() == 1) throw IllegalStateException("reap failed")
                }
            }
        }
        pool.session().use { session ->
            assertEquals(SocketHelperResult.Connected, session.submit(SocketOperation.Accept(0), descriptor(fixture, 52606)).get(5, TimeUnit.SECONDS))
        }
        assertThrows(IllegalStateException::class.java) { pool.close() }
        assertDoesNotThrow { pool.close() }
        assertEquals(2, closes.get())
    }

    @Test
    fun `cancelling queued request closes its descriptor and frees queue without cancelling another session`() {
        val fixture = SupervisorBoundaryFixture()
        val executing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult {
                    calls.incrementAndGet()
                    executing.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    return SocketHelperResult.Connected
                }

                override fun close() {
                    release.countDown()
                }
            }
        }.use { pool ->
            val active = pool.session()
            val first = active.submit(SocketOperation.Accept(0), descriptor(fixture, 52603))
            assertTrue(executing.await(5, TimeUnit.SECONDS))
            val queued = pool.session()
            val cancelled = queued.submit(SocketOperation.Accept(0), descriptor(fixture, 52604))
            queued.cancel()
            queued.cancel()
            assertTrue(cancelled.isCancelled)
            assertEquals(listOf(52604), fixture.closed)
            assertFalse(first.isDone)
            pool.session().use { next ->
                val replacement = next.submit(SocketOperation.Accept(0), descriptor(fixture, 52605))
                assertFalse(replacement.isDone)
                release.countDown()
                assertEquals(SocketHelperResult.Connected, first.get(5, TimeUnit.SECONDS))
                assertEquals(SocketHelperResult.Connected, replacement.get(5, TimeUnit.SECONDS))
            }
            active.close()
            assertEquals(2, calls.get())
        }
        assertEquals(listOf(52604, 52603, 52605), fixture.closed)
    }

    @Test
    fun `request cancellation returns before reap and replacement waits for reap`() {
        val fixture = SupervisorBoundaryFixture()
        val executing = CountDownLatch(1)
        val stopping = CountDownLatch(1)
        val reaped = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val created = AtomicInteger()
        val closes = AtomicInteger()
        SocketHelperPool(1, 2) {
            val number = created.incrementAndGet()
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult {
                    if (number == 1) {
                        executing.countDown()
                        assertTrue(stopping.await(5, TimeUnit.SECONDS))
                    }
                    return SocketHelperResult.Connected
                }

                override fun close() {
                    if (number == 1) {
                        closes.incrementAndGet()
                        stopping.countDown()
                        assertTrue(reaped.await(5, TimeUnit.SECONDS))
                    }
                }
            }
        }.use { pool ->
            val session = pool.session()
            val first = session.submit(SocketOperation.Accept(0), descriptor(fixture, 52600))
            assertTrue(executing.await(5, TimeUnit.SECONDS))
            val cancelling = Thread.ofPlatform().start {
                session.cancel()
                returned.countDown()
            }
            try {
                assertTrue(returned.await(500, TimeUnit.MILLISECONDS), "reactor cancellation must not wait for reap")
                assertTrue(stopping.await(5, TimeUnit.SECONDS))
                pool.session().use { next ->
                    val second = next.submit(SocketOperation.Accept(0), descriptor(fixture, 52601))
                    assertFalse(second.isDone)
                    assertEquals(1, created.get())
                    reaped.countDown()
                    assertEquals(SocketHelperResult.Connected, second.get(5, TimeUnit.SECONDS))
                }
                assertTrue(first.isCompletedExceptionally)
                assertEquals(1, closes.get())
            } finally {
                reaped.countDown()
                cancelling.join(5000)
            }
        }
        assertEquals(listOf(52600, 52601), fixture.closed)
    }

    @Test
    fun `cancellation during factory startup returns and retires late helper without execution`() {
        val fixture = SupervisorBoundaryFixture()
        val starting = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val calls = AtomicInteger()
        val closes = AtomicInteger()
        SocketHelperPool(1, 1) {
            starting.countDown()
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult {
                    calls.incrementAndGet()
                    return SocketHelperResult.Connected
                }

                override fun close() {
                    closes.incrementAndGet()
                }
            }
        }.use { pool ->
            val session = pool.session()
            val result = session.submit(SocketOperation.Accept(0), descriptor(fixture, 52602))
            assertTrue(starting.await(5, TimeUnit.SECONDS))
            val cancelling = Thread.ofPlatform().start {
                session.cancel()
                returned.countDown()
            }
            try {
                assertTrue(returned.await(500, TimeUnit.MILLISECONDS), "startup cannot hold the reactor")
            } finally {
                ready.countDown()
                cancelling.join(5000)
            }
            session.close()
            assertTrue(result.isCompletedExceptionally)
        }
        assertEquals(0, calls.get())
        assertEquals(1, closes.get())
        assertEquals(listOf(52602), fixture.closed)
    }

    private fun descriptor(
        fixture: SupervisorBoundaryFixture,
        value: Int,
    ) = SupervisorOwnedFd(TestFileDescriptors.replace<FileDescriptorRole.Generic>(value), fixture.engine.fileSystem)
}
