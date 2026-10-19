package io.mazewall.koog

import io.mazewall.enforcer.api.execution.CancellationDisposition
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.api.execution.InvocationHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThreadInvocationAwaitTest {
    @Test
    fun `submission stays on caller and unconfined delivery leaves completing thread`() =
        runBlocking {
            val caller = Thread.currentThread()
            val handle = TestHandle()
            val submissions = AtomicInteger()
            val submitThread = AtomicReference<Thread>()
            val afterAwaitThread = AtomicReference<Thread>()
            val result = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                val value = awaitThreadInvocation {
                    submissions.incrementAndGet()
                    submitThread.set(Thread.currentThread())
                    handle
                }
                afterAwaitThread.set(Thread.currentThread())
                value
            }
            val completer = Thread.ofPlatform().unstarted { handle.delivery.complete("OK") }
            try {
                assertTrue(handle.delivery.entered.await(5, TimeUnit.SECONDS))
                assertSame(caller, submitThread.get())
                assertNotSame(caller, handle.delivery.waitThread.get())
                completer.start()
                assertEquals("OK", withTimeout(5_000) { result.await() })
                assertNotSame(completer, afterAwaitThread.get())
                assertEquals(1, submissions.get())
            } finally {
                handle.finish()
                completer.join(5_000)
                assertFalse(completer.isAlive)
            }
        }

    @Test
    fun `already cancelled caller never submits`() =
        runBlocking {
            var submissions = 0
            val handle = TestHandle()
            val result = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                awaitThreadInvocation<String> {
                    submissions++
                    handle
                }
            }
            withTimeout(5_000) { result.join() }
            assertEquals(0, submissions)
            assertTrue(result.isCancelled)
        }

    @Test
    fun `caller cancellation requests original handle without ending execution`() =
        runBlocking {
            val handle = TestHandle()
            val result = launch(start = CoroutineStart.UNDISPATCHED) { awaitThreadInvocation<String> { handle } }
            try {
                assertTrue(handle.delivery.entered.await(5, TimeUnit.SECONDS))
                result.cancel()
                withTimeout(5_000) { result.join() }
                assertEquals(1, handle.cancellations.get())
                assertFalse(handle.delivery.isCancelled)
                assertFalse(handle.ended.isDone)
                handle.delivery.complete("late")
                assertFalse(handle.ended.isDone)
                handle.ended.complete(ExecutionEnd.Finished(null))
                assertTrue(handle.ended.isDone)
            } finally {
                handle.finish()
            }
        }

    @Test
    fun `cancellation racing admitted handle still requests that handle once`() =
        runBlocking {
            var submissions = 0
            val handle = TestHandle()
            val result = launch(start = CoroutineStart.UNDISPATCHED) {
                val callerJob = currentCoroutineContext().job
                awaitThreadInvocation<String> {
                    submissions++
                    callerJob.cancel()
                    handle
                }
            }
            try {
                withTimeout(5_000) { result.join() }
                assertTrue(result.isCancelled)
                assertEquals(1, submissions)
                assertEquals(1, handle.cancellations.get())
                assertFalse(handle.delivery.isCancelled)
                assertFalse(handle.ended.isDone)
            } finally {
                handle.finish()
            }
        }

    @Test
    fun `body interruption is not caller cancellation`() =
        runBlocking {
            val handle = TestHandle()
            val failure = InterruptedException("body")
            handle.delivery.completeExceptionally(failure)
            val observed = assertFailsWith<InterruptedException> { awaitThreadInvocation { handle } }
            assertTrue(observed === failure || observed.cause === failure)
            assertTrue(currentCoroutineContext().isActive)
            assertEquals(0, handle.cancellations.get())
        }

    @Test
    fun `body cancellation does not request active caller cancellation`() =
        runBlocking {
            val handle = TestHandle()
            val failure = CancellationException("body")
            handle.delivery.completeExceptionally(failure)
            val observed = assertFailsWith<CancellationException> { awaitThreadInvocation { handle } }
            // JDK get() and coroutine debug recovery can each wrap the body cancellation.
            assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
            assertTrue(currentCoroutineContext().isActive)
            assertEquals(0, handle.cancellations.get())
        }

    @Test
    fun `future wrappers retain ordinary permission and fatal causes without replay`() =
        runBlocking {
            val failures = listOf(AccessDeniedException("canary"), IllegalStateException("ordinary"), LinkageError("fatal"))
            for (failure in failures) {
                var submissions = 0
                val handle = TestHandle()
                handle.delivery.completeExceptionally(CompletionException(ExecutionException(CompletionException(failure))))
                val observed = assertFailsWith<Throwable> {
                    awaitThreadInvocation {
                        submissions++
                        handle
                    }
                }
                if (failure is Error) {
                    assertSame(failure, observed)
                } else {
                    assertTrue(observed === failure || observed.cause === failure)
                }
                assertEquals(1, submissions)
                assertEquals(0, handle.cancellations.get())
            }
        }

    @Test
    fun `admission failure is not retried or moved to wait dispatcher`() =
        runBlocking {
            val caller = Thread.currentThread()
            val handle = TestHandle()
            val failure = IllegalStateException("admission")
            var submissions = 0
            val observed = assertFailsWith<IllegalStateException> {
                awaitThreadInvocation<String> {
                    submissions++
                    assertSame(caller, Thread.currentThread())
                    throw failure
                }
            }
            assertSame(failure, observed)
            assertEquals(1, submissions)
            assertEquals(0, handle.delivery.waits.get())
        }

    @Test
    fun `cancellation request failures preserve cancellation and promote fatal`() =
        runBlocking {
            for (requestFailure in listOf(IllegalStateException("request"), LinkageError("fatal"))) {
                val handle = TestHandle(requestFailure)
                val cancellation = CancellationException("caller")
                val observed = CompletableFuture<Throwable>()
                val result = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitThreadInvocation { handle }
                    } catch (failure: Throwable) {
                        observed.complete(failure)
                    }
                }
                try {
                    assertTrue(handle.delivery.entered.await(5, TimeUnit.SECONDS))
                    result.cancel(cancellation)
                    withTimeout(5_000) { result.join() }
                    val failure = observed.get(5, TimeUnit.SECONDS)
                    if (requestFailure is Error) {
                        assertSame(requestFailure, failure)
                        val suppressed = failure.suppressed.single()
                        assertIs<CancellationException>(suppressed)
                        assertTrue(suppressed === cancellation || suppressed.cause === cancellation)
                    } else {
                        assertIs<CancellationException>(failure)
                        assertTrue(failure === cancellation || failure.cause === cancellation)
                        assertEquals(listOf(requestFailure), failure.suppressed.toList())
                    }
                    assertTrue(result.isCancelled)
                    assertEquals(1, handle.cancellations.get())
                } finally {
                    handle.finish()
                }
            }
        }

    private class TestHandle(
        private val requestFailure: Throwable? = null,
    ) : InvocationHandle<String> {
        val delivery = RecordingFuture()
        val ended = CompletableFuture<ExecutionEnd>()
        val cancellations = AtomicInteger()

        override fun result() = delivery

        override fun executionEnd() = ended

        override fun requestCancellation(): CancellationDisposition {
            cancellations.incrementAndGet()
            requestFailure?.let { throw it }
            return CancellationDisposition.REQUESTED
        }

        fun finish() {
            delivery.complete("cleanup")
            ended.complete(ExecutionEnd.Finished(null))
        }
    }

    private class RecordingFuture : CompletableFuture<String>() {
        val entered = CountDownLatch(1)
        val waitThread = AtomicReference<Thread>()
        val waits = AtomicInteger()

        override fun get(): String {
            waits.incrementAndGet()
            waitThread.set(Thread.currentThread())
            entered.countDown()
            return super.get()
        }
    }
}
