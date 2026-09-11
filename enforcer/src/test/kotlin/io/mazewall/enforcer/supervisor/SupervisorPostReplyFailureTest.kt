package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.seccomp.daemon.LoopAction
import io.mazewall.testing.awaitOrFail
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SupervisorPostReplyFailureTest {
    @Test
    fun `unresolved synchronous path is denied before descriptor allocation`() {
        val fixture = SupervisorBoundaryFixture()
        val fatal = LinkageError("fatal opened-source cleanup")
        fixture.engine.fileSystem.onClose = { fd ->
            fixture.closed += fd.value
            if (fd.value == 51002) throw fatal
            SupervisorBoundaryFixture.success(0)
        }
        val handler = fixture.handler()
        NativeArena.ofConfined().use { arena ->
            val handled = handler.processNotification(fixture.notification(arena, Arch.AMD64.open), arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
            val finalAction = with(arena) {
                handler.handleActiveListener(arena.allocate(Layouts.POLLFD, 2), arena.allocate(Layouts.SECCOMP_NOTIF), arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
            }
            assertAll(
                { assertTrue(handled) },
                { assertEquals(LoopAction.Continue, finalAction) },
                { assertEquals(listOf(KernelReply(42, -1, 0, false)), fixture.replies) },
                { assertEquals(emptyList<Long>(), fixture.closed) },
                { assertEquals(0, fixture.writeCount) },
            )
        }
    }

    @Test
    fun `pending async fatal cleanup after confirmed injection terminates before uncaught worker exit`() {
        val fixture = SupervisorBoundaryFixture()
        val fatal = LinkageError("fatal accepted-source cleanup")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uncaught = CountDownLatch(1)
        val uncaughtCount = AtomicInteger()
        val observed = AtomicReference<Throwable>()
        fixture.engine.networking.onAccept4 = { _, _, _, _ ->
            Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
                observed.set(failure)
                uncaughtCount.incrementAndGet()
                uncaught.countDown()
            }
            entered.countDown()
            release.awaitOrFail("release successful accept after completion registration")
            SupervisorBoundaryFixture.success(51004)
        }
        fixture.engine.fileSystem.onClose = { fd ->
            fixture.closed += fd.value
            if (fd.value == 51004) throw fatal
            SupervisorBoundaryFixture.success(0)
        }
        val handler = fixture.handler()
        NativeArena.ofConfined().use { arena ->
            try {
                assertTrue(handler.processNotification(fixture.notification(arena, Arch.AMD64.accept), arena.allocate(Layouts.SECCOMP_NOTIF_RESP)))
                entered.awaitOrFail("pending accept worker")
            } finally {
                release.countDown()
            }
            fixture.awaitTerminalProcessing()
            uncaught.awaitOrFail("fatal cleanup delivered to the worker uncaught handler")
            val finalAction = with(arena) {
                handler.handleActiveListener(arena.allocate(Layouts.POLLFD, 2), arena.allocate(Layouts.SECCOMP_NOTIF), arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
            }
            fixture.responseId = 43
            val next = fixture.notification(arena, Arch.AMD64.clone).also { it.writeLong(0, 43) }
            val nextHandled = handler.processNotification(next, arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
            assertAll(
                { assertSame(fatal, observed.get(), "the replying worker must report the original fatal error uncaught") },
                { assertEquals(1, uncaughtCount.get()) },
                { assertEquals(LoopAction.Break, finalAction) },
                { assertFalse(nextHandled) },
                { assertEquals(listOf(KernelReply(42, 0, 0, true)), fixture.replies, "no second reply after confirmed ADDFD+SEND") },
                { assertEquals(listOf(51005, 51006, 51004), fixture.closed) },
                { assertEquals(1, fixture.writeCount) },
                { assertEquals(1, fixture.sockets.closeCalledCount.get()) },
            )
        }
    }
}
