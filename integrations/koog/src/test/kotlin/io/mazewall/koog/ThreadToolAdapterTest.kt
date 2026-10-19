package io.mazewall.koog

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.typeToken
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState
import io.mazewall.enforcer.api.execution.InProcessDomain
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.platform.syscall.Syscall
import io.mazewall.testing.withMockManagedRuntime
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
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

class ThreadToolAdapterTest {
    private val timeout = Duration.ofSeconds(5)

    @Test
    fun `registry invocation runs only the action on its owned platform worker`() =
        withMockManagedRuntime { runtime ->
            val domain = domain(runtime)
            val caller = Thread.currentThread()
            val worker = AtomicReference<Thread>()
            val resultValidator = AtomicReference<Thread>()
            val actions = AtomicInteger()
            val tool = tool(
                runtime,
                domain,
                validateArguments = { assertSame(caller, Thread.currentThread()) },
                validateResult = { resultValidator.set(Thread.currentThread()) },
            ) {
                actions.incrementAndGet()
                worker.set(Thread.currentThread())
                it.text.uppercase()
            }
            val registry = ToolRegistry { tool(tool) }
            repeat(3) { assertEquals(0L, tool.diagnostics().invocations.admitted) }
            assertEquals("thread_echo", tool.operationId())
            runBlocking {
                val result = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    registry.getTool<ThreadToolAdapter<Arguments, String>>().execute(Arguments("hello"))
                }
                assertEquals("HELLO", withTimeout(5_000) { result.await() })
            }
            assertEquals(1, actions.get())
            assertFalse(worker.get().isVirtual)
            assertNotSame(caller, worker.get())
            assertNotSame(worker.get(), resultValidator.get())
            assertEquals(1L, tool.diagnostics().invocations.admitted)
            assertEquals(DrainReport.Complete, runtime.drain(timeout))
        }

    @Test
    fun `argument rejection happens before admission or action`() =
        withMockManagedRuntime { runtime ->
            val tool = tool(runtime, domain(runtime), validateArguments = { require(it.text.length <= 8) }) {
                error("Rejected arguments reached the action")
            }
            runBlocking { assertFailsWith<IllegalArgumentException> { tool.execute(Arguments("x".repeat(9))) } }
            assertEquals(0L, tool.diagnostics().invocations.admitted)
        }

    @Test
    fun `result rejection never replays the action`() =
        withMockManagedRuntime { runtime ->
            val actions = AtomicInteger()
            val tool = tool(runtime, domain(runtime), validateResult = { require(it.length <= 8) }) {
                actions.incrementAndGet()
                "x".repeat(9)
            }
            runBlocking { assertFailsWith<IllegalArgumentException> { tool.execute(Arguments("hello")) } }
            assertEquals(1, actions.get())
            assertEquals(1L, tool.diagnostics().invocations.admitted)
        }

    @Test
    fun `wrong owner is rejected without running on either runtime`() =
        withMockManagedRuntime { runtime ->
            ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout).use { other ->
                val domain = domain(runtime)
                val tool = tool(other, domain) { error("Wrong owner reached the action") }
                runBlocking { assertFailsWith<IllegalArgumentException> { tool.execute(Arguments("hello")) } }
                assertEquals(0L, domain.diagnostics().invocations.admitted)
            }
        }

    @Test
    fun `closed owner rejects without caller fallback`() =
        withMockManagedRuntime { runtime ->
            val tool = tool(runtime, domain(runtime)) { error("Closed owner reached the action") }
            assertEquals(DrainReport.Complete, runtime.drain(timeout))
            runBlocking { assertFailsWith<RejectedExecutionException> { tool.execute(Arguments("hello")) } }
            assertEquals(0L, tool.diagnostics().invocations.admitted)
        }

    @Test
    fun `owned caller cannot bypass nested dispatch by hopping to the wait dispatcher`() =
        withMockManagedRuntime { runtime ->
            val source = domain(runtime)
            val target = domain(runtime)
            val tool = tool(runtime, target) { error("Nested dispatch reached the target action") }
            val handle = runtime.submit(source, Callable { runBlocking { tool.execute(Arguments("hello")) } })
            val failure = assertFailsWith<ExecutionException> { handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }
            assertIs<RejectedExecutionException>(failure.cause)
            handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
            assertEquals(0L, target.diagnostics().invocations.admitted)
        }

    @Test
    fun `body failure retains its cause without replay`() =
        withMockManagedRuntime { runtime ->
            val actions = AtomicInteger()
            val failure = IllegalStateException("action failed")
            val tool = tool(runtime, domain(runtime)) {
                actions.incrementAndGet()
                throw failure
            }
            runBlocking {
                val observed = assertFailsWith<IllegalStateException> { tool.execute(Arguments("hello")) }
                assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
            }
            assertEquals(1, actions.get())
        }

    @Test
    fun `cancelled tool keeps admission until physical cleanup finishes`() =
        withMockManagedRuntime { runtime ->
            val entered = CountDownLatch(1)
            val bodyRelease = CountDownLatch(1)
            val cleanupEntered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val actions = AtomicInteger()
            val tool = tool(runtime, domain(runtime)) {
                actions.incrementAndGet()
                entered.countDown()
                try {
                    bodyRelease.await()
                    "unexpected"
                } finally {
                    cleanupEntered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            try {
                runBlocking {
                    val result = async(start = CoroutineStart.UNDISPATCHED) { tool.execute(Arguments("hello")) }
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        assertFailsWith<RejectedExecutionException> { withTimeout(1_000) { tool.execute(Arguments("overload")) } }
                        result.cancel()
                        withTimeout(5_000) { result.join() }
                        assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
                        assertEquals(1L, tool.diagnostics().invocations.cancellationRequests)
                        assertEquals(1, tool.diagnostics().unfinishedInvocations)
                        assertFalse(tool.diagnostics().retirementComplete)
                        assertFailsWith<RejectedExecutionException> { withTimeout(1_000) { tool.execute(Arguments("still busy")) } }
                        assertIs<DrainReport.Incomplete>(runtime.drain(Duration.ofMillis(20)))
                    } finally {
                        bodyRelease.countDown()
                        release.countDown()
                        result.cancel()
                    }
                }
            } finally {
                bodyRelease.countDown()
                release.countDown()
                assertEquals(DrainReport.Complete, runtime.drain(timeout))
                assertTrue(tool.diagnostics().retirementComplete)
                assertEquals(0, tool.diagnostics().liveWorkers)
            }
            assertEquals(1, actions.get())
            assertEquals(ExecutionDiagnosticState.CLOSED, tool.diagnostics().state)
            assertTrue(tool.diagnostics().retirementComplete)
            assertEquals(0, tool.diagnostics().liveWorkers)
        }

    @Serializable
    data class Arguments(
        val text: String,
    )

    private fun domain(runtime: ManagedRuntime): InProcessDomain =
        runtime.prepare(
            WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).block(Syscall.CONNECT).build(),
            DomainLimits(workers = 1, maxAdmissions = 1),
        )

    private fun tool(
        runtime: ManagedRuntime,
        domain: InProcessDomain,
        validateArguments: (Arguments) -> Unit = {},
        validateResult: (String) -> Unit = {},
        action: (Arguments) -> String,
    ): ThreadToolAdapter<Arguments, String> =
        ThreadToolAdapter(
            runtime,
            domain,
            typeToken<Arguments>(),
            typeToken<String>(),
            "thread_echo",
            "Bound synchronous test action",
            validateArguments,
            validateResult,
            action,
        )
}
