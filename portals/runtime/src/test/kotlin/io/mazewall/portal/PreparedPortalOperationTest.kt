package io.mazewall.portal

import io.mazewall.enforcer.api.execution.CancellationDisposition
import io.mazewall.enforcer.api.execution.ExecutionEnd
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PreparedPortalOperationTest {
    @Test
    fun `cancellation transitions never erase running body or pending retirement`() {
        assertEquals(PortalInvocationPhase.ENDED, PortalInvocationLifecycle.evaluate(PortalInvocationPhase.QUEUED, PortalInvocationEvent.CANCEL))
        assertEquals(PortalInvocationPhase.RUNNING, PortalInvocationLifecycle.evaluate(PortalInvocationPhase.RUNNING, PortalInvocationEvent.CANCEL))
        assertEquals(PortalInvocationPhase.RETIRING, PortalInvocationLifecycle.evaluate(PortalInvocationPhase.RETIRING, PortalInvocationEvent.CANCEL))
        assertEquals(PortalInvocationPhase.ENDED, PortalInvocationLifecycle.evaluate(PortalInvocationPhase.RETIRING, PortalInvocationEvent.RETIREMENT_ENDED))
        assertEquals(PortalInvocationPhase.ENDED, PortalInvocationLifecycle.evaluate(PortalInvocationPhase.ENDED, PortalInvocationEvent.START))
    }

    @Test
    fun `delivery failure retains admission until exact physical retirement`() {
        val identity = Any()
        val owner = PortalOperationOwner(identity, PortalRuntimeLimits(maxAdmissions = 1))
        val initial = owner.diagnostics()
        val retirement = CompletableFuture<Unit>()
        try {
            val failed = owner.submit(
                Callable<String> {
                PortalInvocationRetirement.dispatched(identity)
                PortalInvocationRetirement.retain(retirement)
                throw PortalCallException("ambiguous worker loss")
            },
            )
            runCatching { failed.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }
            failed.executionEnd().toCompletableFuture().complete(ExecutionEnd.Finished(null))
            assertFalse(failed.executionEnd().toCompletableFuture().isDone)
            assertEquals(1, owner.unfinishedInvocations())
            val rejected = owner.submit(Callable { error("overloaded body must not run") })
            assertIs<ExecutionEnd.NotStarted>(rejected.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
            assertEquals(1L, owner.diagnostics().admitted)
            assertEquals(1L, owner.diagnostics().rejected)
            assertEquals(1, owner.unfinishedInvocations(), "delivery and rejected delivery cannot retire the held invocation")
            retirement.complete(Unit)
            assertIs<ExecutionEnd.Finished>(failed.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
            assertEquals(CancellationDisposition.ALREADY_ENDED, failed.requestCancellation())
        } finally {
            retirement.complete(Unit)
            owner.stop()
            assertTrue(
                owner.await(
                    io.mazewall.platform.lifecycle.Deadline
                .afterMillis(5_000),
                ),
            )
            assertEquals(0, owner.unfinishedInvocations())
            assertEquals(2L, owner.diagnostics().delivered)
            assertEquals(2L, owner.diagnostics().ended)
            assertEquals(0L, initial.admitted, "captured counter snapshots must not change")
        }
    }

    @Test
    fun `queued cancellation never enters mapping and releases its admission`() {
        val identity = Any()
        val owner = PortalOperationOwner(identity, PortalRuntimeLimits(workers = 1, maxAdmissions = 2))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mapped = AtomicInteger()
        try {
            val active = owner.submit(
                Callable {
                PortalInvocationRetirement.dispatched(identity)
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "active"
            },
            )
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queued = owner.submit(Callable { mapped.incrementAndGet() })
            assertEquals(CancellationDisposition.REQUESTED, queued.requestCancellation())
            assertIs<ExecutionEnd.NotStarted>(queued.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
            assertEquals(0, mapped.get())
            assertEquals(1L, owner.diagnostics().cancellationRequests)
            val next = owner.submit(
                Callable {
                PortalInvocationRetirement.dispatched(identity)
                "next"
            },
            )
            release.countDown()
            assertEquals("active", active.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
            assertEquals("next", next.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
            assertEquals(0, mapped.get())
        } finally {
            release.countDown()
            owner.stop()
            assertTrue(
                owner.await(
                    io.mazewall.platform.lifecycle.Deadline
                .afterMillis(5_000),
                ),
            )
            assertEquals(3L, owner.diagnostics().admitted)
            assertEquals(3L, owner.diagnostics().ended)
        }
    }

    @Test
    fun `mapping cannot substitute a different prepared broker or return local fallback`() {
        val owner = PortalOperationOwner(Any(), PortalRuntimeLimits())
        try {
            val foreign = owner.submit(
                Callable {
                PortalInvocationRetirement.dispatched(Any())
                "foreign"
            },
            )
            val local = owner.submit(Callable { "local fallback" })
            assertTrue(runCatching { foreign.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.exceptionOrNull() is ExecutionException)
            assertTrue(runCatching { local.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.exceptionOrNull() is ExecutionException)
            assertIs<ExecutionEnd.NotStarted>(foreign.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
            assertIs<ExecutionEnd.NotStarted>(local.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
        } finally {
            owner.stop()
            assertTrue(
                owner.await(
                    io.mazewall.platform.lifecycle.Deadline
                .afterMillis(5_000),
                ),
            )
        }
    }
}
