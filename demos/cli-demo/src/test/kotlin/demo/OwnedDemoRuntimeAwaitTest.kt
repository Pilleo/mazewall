package demo

import io.mazewall.enforcer.api.execution.CancellationDisposition
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.api.execution.InvocationHandle
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Host protocol tests only: controlled stages grant no native owner or containment authority. */
class OwnedDemoRuntimeAwaitTest {
    @Test
    fun `success and business failures wait for execution end before returning`() {
        for (business in listOf(null, InterruptedException("business interruption"), CancellationException("business cancellation"))) {
            val delivery = if (business == null) Result.success("value") else Result.failure<String>(business)
            val handle = ControlledInvocation(delivery)
            val returned = CompletableFuture<Result<String>>()
            val interrupted = AtomicBoolean()
            val caller = Thread.ofPlatform().start {
                val result = runCatching { OwnedDemoRuntime.await(handle) }
                interrupted.set(Thread.currentThread().isInterrupted)
                returned.complete(result)
            }
            try {
                CompletableFuture.anyOf(handle.endWaitEntered, returned).get(5, TimeUnit.SECONDS)
                assertTrue(handle.endWaitEntered.isDone, "Delivery alone must not release the synchronous caller")
                assertFalse(returned.isDone, "The original execution lease is still live")
                handle.ended.complete(ExecutionEnd.Finished(business))
                val result = returned.get(5, TimeUnit.SECONDS)
                when (business) {
                    null -> assertEquals("value", result.getOrThrow())
                    is CancellationException -> assertTrue(generateSequence(result.exceptionOrNull()) { it.cause }.any { it === business })
                    else -> assertSame(business, result.exceptionOrNull())
                }
                assertFalse(interrupted.get(), "A business InterruptedException is not waiter interruption")
                assertEquals(0, handle.cancellations.get())
            } finally {
                handle.ended.complete(ExecutionEnd.Finished(business))
                caller.join(5000)
                assertFalse(caller.isAlive)
            }
        }
    }

    @Test
    fun `end timeout cannot be mistaken for a completed permission denial`() {
        val denied = AccessDeniedException("controlled-stage-not-a-kernel-observation")
        val timeout = TimeoutException("controlled end timeout")
        val handle = ControlledInvocation(Result.failure<String>(denied), timeout)
        val failure = runCatching { OwnedDemoRuntime.await(handle) }.exceptionOrNull()
        assertSame(timeout, failure)
        assertTrue(timeout.suppressed.any { it === denied })
        assertEquals(1, handle.cancellations.get())
    }

    @Test
    fun `interrupted end wait restores the flag even if cancellation fails fatally`() {
        val interrupted = InterruptedException("controlled waiter interruption")
        val fatal = AssertionError("controlled cancellation failure")
        val handle = ControlledInvocation(Result.success("value"), interrupted, fatal)
        try {
            val failure = runCatching { OwnedDemoRuntime.await(handle) }.exceptionOrNull()
            assertSame(fatal, failure)
            assertTrue(fatal.suppressed.any { it === interrupted })
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, handle.cancellations.get())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `fatal end wait keeps the delivered failure as suppressed evidence`() {
        val denied = AccessDeniedException("controlled stage")
        val fatal = AssertionError("controlled end failure")
        val handle = ControlledInvocation(Result.failure<String>(denied), fatal)
        assertSame(fatal, runCatching { OwnedDemoRuntime.await(handle) }.exceptionOrNull())
        assertTrue(fatal.suppressed.any { it === denied })
    }

    @Test
    fun `nullable result remains a successful delivery`() {
        val handle = ControlledInvocation(Result.success<String?>(null))
        handle.ended.complete(ExecutionEnd.Finished(null))
        assertEquals(null, OwnedDemoRuntime.await(handle))
    }

    private class ControlledInvocation<T>(
        delivery: Result<T>,
        endFailure: Throwable? = null,
        private val cancellationFailure: Throwable? = null,
    ) : InvocationHandle<T> {
        private val delivered = CompletableFuture<T>().apply {
            delivery.fold({ complete(it) }, { completeExceptionally(it) })
        }
        val endWaitEntered = CompletableFuture<Unit>()
        val cancellations = AtomicInteger()
        val ended = object : CompletableFuture<ExecutionEnd>() {
            override fun get(timeout: Long, unit: TimeUnit): ExecutionEnd {
                assertTrue(unit.toNanos(timeout) in 0..TimeUnit.SECONDS.toNanos(10), "One shared ten-second wait budget")
                endWaitEntered.complete(Unit)
                endFailure?.let { throw it }
                return super.get(timeout, unit)
            }
        }

        override fun result(): CompletionStage<T> = delivered

        override fun executionEnd(): CompletionStage<ExecutionEnd> = ended

        override fun requestCancellation(): CancellationDisposition {
            cancellations.incrementAndGet()
            cancellationFailure?.let { throw it }
            return CancellationDisposition.REQUESTED
        }
    }
}
