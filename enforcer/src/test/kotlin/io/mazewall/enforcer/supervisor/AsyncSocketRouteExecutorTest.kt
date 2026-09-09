package io.mazewall.enforcer.supervisor

import io.mazewall.core.Tid
import io.mazewall.testing.awaitOrFail
import io.mazewall.testing.getOrFail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AsyncSocketRouteExecutorTest {
    @Test
    fun `fatal prerequisite cleanup releases the accepted successor before worker exit`() {
        val fixture = SupervisorBoundaryFixture()
        val fatal = LinkageError("fatal imported-listener close")
        val uncaught = CountDownLatch(1)
        val observed = AtomicReference<Throwable>()
        fixture.engine.networking.onAccept4 = { _, _, _, _ ->
            Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
                observed.set(failure)
                uncaught.countDown()
            }
            SupervisorBoundaryFixture.success(51004)
        }
        fixture.engine.fileSystem.onClose = { fd ->
            fixture.closed += fd.value
            if (fd.value == 51006) throw fatal
            SupervisorBoundaryFixture.success(0)
        }
        val executor = AsyncSocketRouteExecutor(SocketRouteExecutor(fixture.engine, TraceeDescriptorImporter(fixture.engine) { it }))
        val future = executor.execute(42, AcceptRequest(Tid(77), 55, 0, 0, 0))
        uncaught.awaitOrFail("fatal cleanup worker exit")
        assertSame(fatal, observed.get())
        assertEquals(listOf(51005, 51006, 51004), fixture.closed)
        assertTrue(future.isCompletedExceptionally)
        assertSame(fatal, assertThrows(ExecutionException::class.java) { future.get(1, TimeUnit.SECONDS) }.cause)
        assertEquals(0, fixture.replies.size)
    }

    @TestFactory
    fun `fatal worker failures complete exceptionally before uncaught exit`(): List<DynamicTest> =
        listOf({ AssertionError("fatal assertion") }, { LinkageError("fatal linkage") }, { OutOfMemoryError("fatal allocation") }).map { error ->
            DynamicTest.dynamicTest(error().javaClass.simpleName) {
                val fixture = SupervisorBoundaryFixture()
                val fatal = error()
                val uncaught = CountDownLatch(1)
                val observed = AtomicReference<Throwable>()
                fixture.engine.networking.onAccept4 = { _, _, _, _ ->
                    Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
                        observed.set(failure)
                        uncaught.countDown()
                    }
                    throw fatal
                }
                val executor = AsyncSocketRouteExecutor(SocketRouteExecutor(fixture.engine, TraceeDescriptorImporter(fixture.engine) { it }))
                val future = executor.execute(42, AcceptRequest(Tid(77), 55, 0, 0, 0))
                uncaught.awaitOrFail("fatal worker exit")
                assertSame(fatal, observed.get())
                assertTrue(future.isCompletedExceptionally, "a fatal worker cannot leave its completion pending")
                assertSame(fatal, assertThrows(ExecutionException::class.java) { future.get(1, TimeUnit.SECONDS) }.cause)
                assertEquals(listOf(51005, 51006), fixture.closed)
                assertEquals(0, fixture.replies.size)
            }
        }

    @Test
    fun `async native exception produces a denial effect and releases imported ownership`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.networking.onAccept4 = { _, _, _, _ -> throw IllegalStateException("native accept failed") }
        val executor = AsyncSocketRouteExecutor(SocketRouteExecutor(fixture.engine, TraceeDescriptorImporter(fixture.engine) { it }))
        val effect = executor.execute(42, AcceptRequest(Tid(77), 55, 0, 0, 0)).getOrFail("async failure effect")
        assertEquals(SupervisorReplyEffect.Error(1), effect)
        assertEquals(listOf(51005, 51006), fixture.closed)
        assertEquals(0, fixture.replies.size)
    }

    @Test
    fun `pending accept returns promptly and publishes a heap-owned effect after completion`() {
        val fixture = SupervisorBoundaryFixture()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.engine.networking.onAccept4 = { _, _, _, _ ->
            started.countDown()
            release.awaitOrFail("release pending accept")
            SupervisorBoundaryFixture.success(51004)
        }
        val executor = AsyncSocketRouteExecutor(SocketRouteExecutor(fixture.engine, TraceeDescriptorImporter(fixture.engine) { it }))
        val future = executor.execute(42, AcceptRequest(Tid(77), 55, 0, 0, 0))
        try {
            started.awaitOrFail("accept entered native boundary")
            assertFalse(future.isDone)
            assertEquals(0, fixture.replies.size)
        } finally {
            release.countDown()
        }
        val effect = assertInstanceOf(SupervisorReplyEffect.AddFdAndReply::class.java, future.getOrFail("accept effect"))
        assertEquals(51004, effect.descriptor.handle.value)
        effect.descriptor.close()
        assertEquals(listOf(51005, 51006, 51004), fixture.closed)
    }
}
