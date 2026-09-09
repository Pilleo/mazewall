package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.SocketManager
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.seccomp.daemon.LoopAction
import io.mazewall.testing.awaitOrFail
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SupervisorTerminalFailureTest {
    private data class UnusableExchange(
        val name: String,
        val configure: (SupervisorBoundaryFixture) -> Unit,
        val resultType: Class<out JvmExchangeResult>,
    )

    private fun unusableExchanges() =
        listOf(
        UnusableExchange("timeout", { it.engine.pollResult = SupervisorBoundaryFixture.success(0) }, JvmExchangeResult.Timeout::class.java),
        UnusableExchange("oversized path", { it.pathLength = 4097 }, JvmExchangeResult.Malformed::class.java),
        UnusableExchange("ID mismatch", { it.responseId = 99 }, JvmExchangeResult.Malformed::class.java),
        UnusableExchange("transport EOF", { it.frameLength = 0 }, JvmExchangeResult.TransportFailed::class.java),
    )

    @TestFactory
    fun `unusable exchange remains observable when its closer throws`(): List<DynamicTest> =
        unusableExchanges().map { case ->
            DynamicTest.dynamicTest(case.name) {
                val fixture = SupervisorBoundaryFixture()
                case.configure(fixture)
                val closer = ThrowingCloser(fixture.sockets)
                NativeArena.ofConfined().use { arena ->
                    val result = assertDoesNotThrow<JvmExchangeResult> {
                        with(arena) { JvmVerdictExchange(fixture.engine, fixture.socket, closer).receive(fixture.context(arena).request) }
                    }
                    assertInstanceOf(case.resultType, result)
                    assertSame(closer.failure, result.cleanupFailure, "cleanup evidence must accompany the original exchange outcome")
                }
                assertEquals(1, closer.attempts.get())
                assertEquals(0, fixture.replies.size)
            }
        }

    @TestFactory
    fun `failed exchange and failed close cannot admit a second notification`(): List<DynamicTest> =
        unusableExchanges().map { case ->
            DynamicTest.dynamicTest(case.name) {
                val fixture = SupervisorBoundaryFixture()
                case.configure(fixture)
                val closer = ThrowingCloser(fixture.sockets)
                val handler = fixture.handler(closer)
                NativeArena.ofConfined().use { arena ->
                    assertFalse(handler.processNotification(fixture.notification(arena, Arch.AMD64.clone), arena.allocate(Layouts.SECCOMP_NOTIF_RESP)))
                    fixture.engine.pollResult = SupervisorBoundaryFixture.success(1)
                    fixture.pathLength = null
                    fixture.frameLength = 4116
                    fixture.responseId = 43
                    val next = fixture.notification(arena, Arch.AMD64.clone).also { it.writeLong(0, 43) }
                    assertFalse(handler.processNotification(next, arena.allocate(Layouts.SECCOMP_NOTIF_RESP)))
                    assertEquals(LoopAction.Break, with(arena) { handler.handleActiveListener(arena.allocate(Layouts.POLLFD, 2), next, arena.allocate(Layouts.SECCOMP_NOTIF_RESP)) })
                }
                assertTrue(closer.attempts.get() >= 1)
                assertEquals(1, fixture.writeCount)
                assertEquals(listOf(KernelReply(42, -1, 0, false)), fixture.replies)
            }
        }

    @Test
    fun `fatal async failure terminates the owning session without a denial reply`() {
        val fixture = SupervisorBoundaryFixture()
        val fatal = LinkageError("fatal accept initialization")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uncaught = CountDownLatch(1)
        val observed = AtomicReference<Throwable>()
        fixture.engine.networking.onAccept4 = { _, _, _, _ ->
            Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
                observed.set(failure)
                uncaught.countDown()
            }
            entered.countDown()
            release.awaitOrFail("allow fatal accept failure")
            throw fatal
        }
        val handler = fixture.handler()
        NativeArena.ofConfined().use { arena ->
            try {
                assertTrue(handler.processNotification(fixture.notification(arena, Arch.AMD64.accept), arena.allocate(Layouts.SECCOMP_NOTIF_RESP)))
                entered.awaitOrFail("accept worker entered")
            } finally {
                release.countDown()
            }
            uncaught.awaitOrFail("fatal worker exit")
            assertSame(fatal, observed.get())
            assertEquals(
                LoopAction.Break,
                with(arena) { handler.handleActiveListener(arena.allocate(Layouts.POLLFD, 2), arena.allocate(Layouts.SECCOMP_NOTIF), arena.allocate(Layouts.SECCOMP_NOTIF_RESP)) },
            )
            assertFalse(handler.processNotification(fixture.notification(arena, Arch.AMD64.clone), arena.allocate(Layouts.SECCOMP_NOTIF_RESP)))
        }
        assertEquals(1, fixture.sockets.closeCalledCount.get())
        assertEquals(1, fixture.writeCount)
        assertEquals(listOf(51005, 51006), fixture.closed)
        assertEquals(0, fixture.replies.size)
    }

    private class ThrowingCloser(
        delegate: SocketManager,
    ) : SocketManager by delegate {
        val attempts = AtomicInteger()
        val failure = IllegalStateException("failure before native close dispatch")

        override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
            attempts.incrementAndGet()
            throw failure
        }
    }
}
