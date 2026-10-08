package io.mazewall.koog

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.typeToken
import io.mazewall.enforcer.api.execution.CancellationDisposition
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.api.execution.InvocationHandle
import io.mazewall.portal.PreparedPortalOperation
import io.mazewall.portal.TestPreparedOperations
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PortalToolAdapterTest {
    @Test
    fun `JVM public constructor requires a prepared operation rather than arbitrary submitter`() {
        val constructors = PortalToolAdapter::class.java.constructors
        assertEquals(1, constructors.size)
        assertEquals(PreparedPortalOperation::class.java, constructors.single().parameterTypes.first())
    }

    @Test
    fun `actual registry dispatches one bound operation and validates before submission`() =
        runBlocking {
        val handle = TestHandle()
        var submissions = 0
        val tool = tool(handle, { submissions++ })
        val registry = ToolRegistry { tool(tool) }
        assertSame(tool, registry.getTool("bounded_echo"))
        assertEquals(listOf("bounded_echo"), registry.tools.map { it.name })
        assertFailsWith<IllegalArgumentException> { tool.execute(Arguments("x".repeat(9))) }
        assertEquals(0, submissions)
        val result = async(start = CoroutineStart.UNDISPATCHED) { tool.execute(Arguments("hello")) }
        assertEquals(1, submissions)
        assertFalse(result.isCompleted)
        handle.delivery.complete("HELLO")
        assertEquals("HELLO", result.await())
    }

    @Test
    fun `cancellation forwards once and delivery cannot finish physical lifetime`() =
        runBlocking {
        val handle = TestHandle()
        val tool = tool(handle)
        val result = async(start = CoroutineStart.UNDISPATCHED) { tool.execute(Arguments("hello")) }
        result.cancel()
        result.join()
        assertEquals(1, handle.cancellations)
        assertFalse(handle.ended.isDone)
        handle.delivery.complete("late")
        assertTrue(result.isCancelled)
        assertFalse(handle.ended.isDone)
        handle.ended.complete(ExecutionEnd.Finished(null))
        assertTrue(handle.ended.isDone)
    }

    @Test
    fun `unknown failure retains cause and never resubmits`() =
        runBlocking {
        val handle = TestHandle()
        var submissions = 0
        val tool = tool(handle, { submissions++ })
        val failure = IllegalStateException("unknown remote result")
        handle.delivery.completeExceptionally(CompletionException(failure))
        val observed = assertFailsWith<IllegalStateException> { tool.execute(Arguments("hello")) }
        // Coroutine debug stack recovery may copy an exception with the original as cause.
        assertTrue(observed === failure || observed.cause === failure)
        assertEquals(1, submissions)
    }

    @Test
    fun `oversized delivered result is rejected without replay`() =
        runBlocking {
        val handle = TestHandle()
        var submissions = 0
        val tool = tool(handle, { submissions++ })
        handle.delivery.complete("x".repeat(9))
        assertFailsWith<IllegalArgumentException> { tool.execute(Arguments("hello")) }
        assertEquals(1, submissions)
    }

    @Serializable
    data class Arguments(
        val text: String,
    )

    private fun tool(
        handle: TestHandle,
        submitted: () -> Unit = {},
    ): PortalToolAdapter<Arguments, String> =
        PortalToolAdapter(
            TestPreparedOperations.create<Arguments, String> {
                submitted()
                handle
            },
            typeToken<Arguments>(),
            typeToken<String>(),
            "bounded_echo",
            "Bound test data",
            { require(it.text.length <= 8) },
            { require(it.length <= 8) },
        )

    private class TestHandle : InvocationHandle<String> {
        val delivery = CompletableFuture<String>()
        val ended = CompletableFuture<ExecutionEnd>()
        var cancellations = 0

        override fun result() = delivery.minimalCompletionStage()

        override fun executionEnd() = ended.minimalCompletionStage()

        override fun requestCancellation(): CancellationDisposition {
            cancellations++
            return CancellationDisposition.REQUESTED
        }
    }
}
