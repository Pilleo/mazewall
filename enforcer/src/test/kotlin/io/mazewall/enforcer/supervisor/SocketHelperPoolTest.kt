package io.mazewall.enforcer.supervisor

import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.TestFileDescriptors
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SocketHelperPoolTest {
    @Test
    fun `interrupted owner still drains all work and preserves interrupt status`() {
        val fixture = SupervisorBoundaryFixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    return SocketHelperResult.Connected
                }

                override fun close() {
                    release.countDown()
                }
            }
        }
        try {
            val session = pool.session()
            val fd = SupervisorOwnedFd(
                TestFileDescriptors
                .replace<FileDescriptorRole.Generic>(52500),
                    fixture.engine.fileSystem,
            )
            session.submit(SocketOperation.Accept(0), fd)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            Thread.currentThread().interrupt()
            session.close()
            pool.close()
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(listOf(52500), fixture.closed)
        } finally {
            Thread.interrupted()
            release.countDown()
            pool.close()
        }
    }

    @Test
    fun `accepted result is closed when releasing the source fails`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.fileSystem.onClose = { fd ->
            fixture.closed += fd.value
            if (fd.value == 52400) SupervisorBoundaryFixture.failure(5) else SupervisorBoundaryFixture.success(0)
        }
        val source = SupervisorOwnedFd(
            TestFileDescriptors
            .replace<FileDescriptorRole.Generic>(52400),
                fixture.engine.fileSystem,
        )
        val accepted = SupervisorOwnedFd(
            TestFileDescriptors
            .replace<FileDescriptorRole.Generic>(52401),
                fixture.engine.fileSystem,
        )
        SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult = SocketHelperResult.Accepted(accepted, byteArrayOf(), 0)

                override fun close() = Unit
            }
        }.use { pool ->
            pool.session().use { session ->
                assertEquals(SocketHelperResult.Failed(5), session.submit(SocketOperation.Accept(0), source).get(5, TimeUnit.SECONDS))
            }
        }
        assertEquals(listOf(52400, 52401), fixture.closed)
    }

    @Test
    fun `fatal worker failure completes exceptionally with original identity after owned close`() {
        val fixture = SupervisorBoundaryFixture()
        val fatal = LinkageError("helper linkage")
        val uncaught = CountDownLatch(1)
        SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult {
                    Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
                        assertSame(fatal, failure)
                        uncaught.countDown()
                    }
                    throw fatal
                }

                override fun close() = Unit
            }
        }.use { pool ->
            pool.session().use { session ->
                val fd = SupervisorOwnedFd(
                    TestFileDescriptors
                    .replace<FileDescriptorRole.Generic>(52300),
                        fixture.engine.fileSystem,
                )
                val result = session.submit(SocketOperation.Accept(0), fd)
                val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { result.get(5, TimeUnit.SECONDS) }
                assertSame(fatal, failure.cause)
                assertTrue(uncaught.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(52300), fixture.closed)
            }
        }
    }

    @Test
    fun `overload is EAGAIN and session cancellation reaps active helper and drops queue`() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val calls = AtomicInteger()
        val fixture = SupervisorBoundaryFixture()
        SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult {
                    calls.incrementAndGet()
                    started.countDown()
                    assertTrue(stopped.await(5, TimeUnit.SECONDS))
                    return SocketHelperResult.Connected
                }

                override fun close() {
                    stopped.countDown()
                }
            }
        }.use { pool ->
            val session = pool.session()

            fun fd(n: Int) =
                SupervisorOwnedFd(
                TestFileDescriptors
                .replace<FileDescriptorRole.Generic>(n),
                    fixture.engine.fileSystem,
            )
            val first = session.submit(SocketOperation.Connect(byteArrayOf(2, 0)), fd(52000))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val queued = session.submit(SocketOperation.Connect(byteArrayOf(2, 0)), fd(52001))
            val overload = session.submit(SocketOperation.Connect(byteArrayOf(2, 0)), fd(52002))
            assertEquals(SocketHelperResult.Failed(11), overload.get(5, TimeUnit.SECONDS))
            session.close()
            assertTrue(first.isCompletedExceptionally)
            assertTrue(queued.isCompletedExceptionally)
            assertEquals(1, calls.get())
        }
        assertEquals(setOf(52000, 52001, 52002), fixture.closed.toSet())
    }

    @Test
    fun `clean helper is reused but crashed helper is replaced`() {
        val created = AtomicInteger()
        val fixture = SupervisorBoundaryFixture()
        SocketHelperPool(1, 2) {
            val number = created.incrementAndGet()
            object : SocketHelper {
                var count = 0

                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult {
                    if (number == 1 && ++count == 2) throw java.io.EOFException("child exited")
                    return SocketHelperResult.Connected
                }

                override fun close() = Unit
            }
        }.use { pool ->
            pool.session().use { session ->
                repeat(3) { index ->
                    val fd = SupervisorOwnedFd(
                        TestFileDescriptors
                        .replace<FileDescriptorRole.Generic>(52100 + index),
                            fixture.engine.fileSystem,
                    )
                    val result = session.submit(SocketOperation.Connect(byteArrayOf(2, 0)), fd).get(5, TimeUnit.SECONDS)
                    assertEquals(if (index == 1) SocketHelperResult.Failed(5) else SocketHelperResult.Connected, result)
                }
            }
            assertEquals(2, created.get())
        }
    }

    @Test
    fun `operation snapshots cannot be mutated and configuration rejects unbounded values`() {
        val bytes = byteArrayOf(2, 0)
        val operation = SocketOperation.Connect(bytes)
        bytes[0] = 9
        operation.address[0] = 8
        assertArrayEquals(byteArrayOf(2, 0), operation.address)
        assertThrows(IllegalArgumentException::class.java) { SocketOperation.Connect(ByteArray(129)) }
        assertThrows(IllegalArgumentException::class.java) { SocketHelperPool(0, 64) { error("must not spawn") } }
        assertThrows(IllegalArgumentException::class.java) { SocketHelperPool(4, -1) { error("must not spawn") } }
    }
}
