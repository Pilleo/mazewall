package io.mazewall.enforcer.internal

import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.PolicyDefinition
import io.mazewall.enforcer.api.ContainmentViolationEvidence
import io.mazewall.enforcer.api.ContainmentViolationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@EnabledIfLinuxAndSupported
class ContainedExecutorWrapperTest {
    val policyDefinition = PolicyDefinition<io.mazewall.PolicyScope.ProcessWideSafe>()

    @Test
    fun `test execute wrapper success`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policyDefinition)

        var executed = false
        wrapper.execute {
            executed = true
        }

        delegate.shutdown()
        delegate.awaitTermination(1, TimeUnit.SECONDS)

        assertTrue(executed)
    }

    @Test
    fun `test execute wrapper failure containment violation`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policyDefinition)

        val runnable = Runnable {
            throw AccessDeniedException("test")
        }

        val future = wrapper.submit(runnable)

        val ex = assertThrows(java.util.concurrent.ExecutionException::class.java) {
            future.get()
        }

        assertTrue(ex.cause is AccessDeniedException)

        delegate.shutdown()
        delegate.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun `test execute wrapper failure regular exception`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policyDefinition)

        val runnable = Runnable {
            throw IllegalArgumentException("test")
        }

        val future = wrapper.submit(runnable)

        val ex = assertThrows(java.util.concurrent.ExecutionException::class.java) {
            future.get()
        }

        assertTrue(ex.cause is IllegalArgumentException)

        delegate.shutdown()
        delegate.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun `test submit callable success`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policyDefinition)

        val callable = Callable {
            "result"
        }

        val future = wrapper.submit(callable)
        assertEquals("result", future.get())

        delegate.shutdown()
        delegate.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun `test submit callable failure containment violation`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policyDefinition)

        val callable = Callable {
            throw AccessDeniedException("test")
        }

        val future = wrapper.submit(callable)

        val ex = assertThrows(java.util.concurrent.ExecutionException::class.java) {
            future.get()
        }

        assertTrue(ex.cause is AccessDeniedException)

        delegate.shutdown()
        delegate.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun `test submit preserves structured containment metadata`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policyDefinition)
        val denial = ContainmentViolationException(
            message = "kernel denial",
            errno = 1,
            syscallNr = 59,
            evidence = ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL,
        )

        val ex = assertThrows(java.util.concurrent.ExecutionException::class.java) {
            wrapper.submit(Callable { throw denial }).get()
        }

        val propagated = assertInstanceOf(ContainmentViolationException::class.java, ex.cause)
        assertEquals(1, propagated.errno)
        assertEquals(59, propagated.syscallNr)
        assertEquals(ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL, propagated.evidence)

        delegate.shutdown()
        delegate.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun `test submit promotes nested structured metadata without losing the original failure`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policyDefinition)
        val denial = ContainmentViolationException(
            message = "kernel denial",
            errno = 13,
            syscallNr = 257,
            evidence = ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL,
        )
        val outer = IllegalStateException("task context", denial)

        val ex = assertThrows(java.util.concurrent.ExecutionException::class.java) {
            wrapper.submit(Callable { throw outer }).get()
        }

        val propagated = assertInstanceOf(ContainmentViolationException::class.java, ex.cause)
        assertEquals(13, propagated.errno)
        assertEquals(257, propagated.syscallNr)
        assertEquals(ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL, propagated.evidence)
        assertTrue(propagated.cause === outer)

        delegate.shutdown()
        delegate.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun `test submit runnable with result`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policyDefinition)

        var executed = false
        val runnable = Runnable {
            executed = true
        }

        val future = wrapper.submit(runnable, "result")
        assertEquals("result", future.get())
        assertTrue(executed)

        delegate.shutdown()
        delegate.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun `test invokeAll`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policyDefinition)

        val callables = listOf(
            Callable { "r1" },
            Callable { "r2" },
        )

        val futures = wrapper.invokeAll(callables)
        assertEquals(2, futures.size)
        assertEquals("r1", futures[0].get())
        assertEquals("r2", futures[1].get())

        delegate.shutdown()
        delegate.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun `test invokeAll with timeout`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policyDefinition)

        val callables = listOf(
            Callable { "r1" },
            Callable { "r2" },
        )

        val futures = wrapper.invokeAll(callables, 1, TimeUnit.SECONDS)
        assertEquals(2, futures.size)
        assertEquals("r1", futures[0].get())
        assertEquals("r2", futures[1].get())

        delegate.shutdown()
        delegate.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun `test invokeAny`() {
        val delegate = Executors.newFixedThreadPool(2)
        val wrapper = ContainedExecutorWrapper(delegate, policyDefinition)

        val callables = listOf(
            Callable { "r1" },
            Callable { "r2" },
        )

        val result = wrapper.invokeAny(callables)
        assertTrue(result == "r1" || result == "r2")

        delegate.shutdown()
        delegate.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun `test invokeAny with timeout`() {
        val delegate = Executors.newFixedThreadPool(2)
        val wrapper = ContainedExecutorWrapper(delegate, policyDefinition)

        val callables = listOf(
            Callable { "r1" },
            Callable { "r2" },
        )

        val result = wrapper.invokeAny(callables, 1, TimeUnit.SECONDS)
        assertTrue(result == "r1" || result == "r2")

        delegate.shutdown()
        delegate.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun `test close`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policyDefinition)

        wrapper.close()
        assertTrue(delegate.isShutdown)
    }
}
