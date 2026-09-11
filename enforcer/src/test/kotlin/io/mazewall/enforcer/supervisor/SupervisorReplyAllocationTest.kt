package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.FaultingNativeArena
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.seccomp.daemon.LoopAction
import io.mazewall.testing.awaitOrFail
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SupervisorReplyAllocationTest {
    @TestFactory
    fun `reply preparation failure disposes unconsumed effects and terminates`(): List<DynamicTest> =
        listOf(false, true).flatMap { async ->
            listOf(false, true).flatMap { failCreation ->
                listOf(false, true).map { fatal ->
                    DynamicTest.dynamicTest("${if (async) "async accept" else "sync open"} ${if (failCreation) "arena" else "buffer"} ${if (fatal) "fatal" else "ordinary"}") {
                        verifyFailure(async, failCreation, fatal)
                    }
                }
            }
        }

    private fun verifyFailure(
        async: Boolean,
        failCreation: Boolean,
        fatal: Boolean,
    ) {
        val fixture = SupervisorBoundaryFixture()
        val failure = if (fatal) OutOfMemoryError("reply preparation failed") else IllegalStateException("reply preparation failed")
        val faults = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val uncaught = AtomicReference<Throwable>()
        val fatalObserved = CountDownLatch(1)
        fixture.engine.networking.onAccept4 = { _, _, _, _ ->
            worker.set(Thread.currentThread())
            Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                uncaught.set(error)
                fatalObserved.countDown()
            }
            entered.countDown()
            release.awaitOrFail("release accept after reply callback registration")
            SupervisorBoundaryFixture.success(51004)
        }
        val handler = fixture.handlerWithReplyArena {
            if (failCreation) {
                faults.incrementAndGet()
                throw failure
            }
            FaultingNativeArena.failingAllocation(1) {
                faults.incrementAndGet()
                throw failure
            }
        }
        NativeArena.ofConfined().use { arena ->
            var handled: Boolean? = null
            val escaped = try {
                handled = handler.processNotification(fixture.notification(arena, if (async) Arch.AMD64.accept else Arch.AMD64.open), arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                null
            } catch (error: Throwable) {
                error
            } finally {
                if (async) release.countDown()
            }
            if (async) {
                entered.awaitOrFail("accept worker entered")
                fixture.awaitSessionClosed()
                if (fatal) fatalObserved.awaitOrFail("fatal completion delivered to uncaught handler")
            }
            val finalAction = with(arena) {
                handler.handleActiveListener(arena.allocate(Layouts.POLLFD, 2), arena.allocate(Layouts.SECCOMP_NOTIF), arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
            }
            fixture.responseId = 43
            val next = fixture.notification(arena, Arch.AMD64.clone).also { it.writeLong(0, 43) }
            val nextHandled = handler.processNotification(next, arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
            assertAll(
                { if (async) assertTrue(handled == true) else assertEquals(if (fatal) null else false, handled) },
                { assertSame(if (fatal && !async) failure else null, escaped) },
                { assertSame(if (fatal && async) failure else null, uncaught.get()) },
                { assertEquals(1, faults.get()) },
                { assertEquals(LoopAction.Break, finalAction) },
                { assertFalse(nextHandled) },
                { assertEquals(emptyList<KernelReply>(), fixture.replies) },
                { assertEquals(if (async) listOf(51005, 51006, 51004) else emptyList(), fixture.closed, "unconsumed effect owns its source descriptor") },
                { assertEquals(if (async) 1 else 0, fixture.writeCount) },
                { assertEquals(1, fixture.sockets.closeCalledCount.get()) },
            )
        }
    }
}
