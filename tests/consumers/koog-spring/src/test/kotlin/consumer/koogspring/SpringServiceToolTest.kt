package consumer.koogspring

import ai.koog.agents.core.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Timeout(20)
class SpringServiceToolTest {
    @Test
    fun invokesTheServiceOnTheOriginalCallerWithItsIdentity() = runBlocking {
        val caller = Thread.currentThread()
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = UsernamePasswordAuthenticationToken.authenticated("admin", null, emptyList())
        SecurityContextHolder.setContext(context)
        val entries = AtomicInteger()
        try {
            val tool = registered { value ->
                assertSame(caller, Thread.currentThread())
                assertSame(context, SecurityContextHolder.getContext())
                entries.incrementAndGet()
                value
            }
            assertEquals("approved", tool.execute(SpringServiceArguments("approved")))
            assertEquals(1, entries.get())
            assertSame(context, SecurityContextHolder.getContext())
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun aBusinessInterruptedExceptionIsNotCoroutineCancellation() = runBlocking {
        val expected = InterruptedException("business interruption")
        val failure = runCatching { registered { throw expected }.execute(SpringServiceArguments("interrupt")) }.exceptionOrNull()
        assertSame(expected, failure)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test
    fun invalidArgumentsNeverEnterTheService() = runBlocking {
        val entries = AtomicInteger()
        val tool = registered { entries.incrementAndGet(); "ok" }
        for (value in listOf("", "   ", "a\u0000b", "x".repeat(4097), "é".repeat(2049))) {
            val failure = runCatching { tool.execute(SpringServiceArguments(value)) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException, "Invalid UTF-8 input must reject before submission")
        }
        assertEquals(0, entries.get())
        assertEquals("ok", tool.execute(SpringServiceArguments("é".repeat(2048))))
        assertEquals(1, entries.get())
    }

    @Test
    fun resultBoundsCountUtf8Bytes() = runBlocking {
        assertEquals("🙂".repeat(16384), registered { "🙂".repeat(16384) }.execute(SpringServiceArguments("boundary")))
        val failure = runCatching { registered { "🙂".repeat(16385) }.execute(SpringServiceArguments("oversized")) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        val absent = runCatching { registered { null }.execute(SpringServiceArguments("null")) }.exceptionOrNull()
        assertTrue(absent is IllegalArgumentException)
    }

    @Test
    fun coroutineCancellationInterruptsTheSynchronousWait() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val entries = AtomicInteger()
        val tool = registered {
            entries.incrementAndGet()
            entered.countDown()
            try {
                check(release.await(10, TimeUnit.SECONDS))
                "released"
            } catch (failure: InterruptedException) {
                interrupted.countDown()
                throw failure
            }
        }
        val call = launch(Dispatchers.IO) { tool.execute(SpringServiceArguments("held")) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            call.cancel()
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "Cancellation must reach the synchronous caller wait")
            call.join()
            assertTrue(call.isCancelled)
            assertEquals(1, entries.get(), "No invocation replay")
        } finally {
            release.countDown()
            call.cancelAndJoin()
        }
    }

    private fun registered(service: JdbcService): SpringServiceTool {
        val registry = ToolRegistry { tool(SpringServiceTool(service)) }
        return registry.getTool<SpringServiceTool>()
    }
}
