package io.mazewall.testing

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

class EventuallyTest {
    @Test
    fun `condition is observed again until it becomes true`() {
        var observations = 0
        awaitCondition(description = "third observation") { ++observations == 3 }
        assertEquals(3, observations)
    }

    @Test
    fun `timeout identifies the unobserved condition`() {
        val failure = assertThrows<AssertionError> {
            awaitCondition(Duration.ofMillis(1), "session closed") { false }
        }
        assertTrue(failure.message!!.contains("Timed out"))
        assertTrue(failure.message!!.contains("session closed"))
    }

    @Test
    fun `condition exceptions retain the cause instead of being retried`() {
        val error = IllegalStateException("read failed")
        assertEquals(
            error,
            assertThrows<IllegalStateException> {
            awaitCondition(description = "readable state") { throw error }
        },
        )
    }

    @Test
    fun `latch success and timeout both have bounded outcomes`() {
        CountDownLatch(0).awaitOrFail("worker ready")
        val failure = assertThrows<AssertionError> {
            CountDownLatch(1).awaitOrFail("worker ready", Duration.ofMillis(1))
        }
        assertTrue(failure.message!!.contains("Timed out"))
        assertTrue(failure.message!!.contains("worker ready"))
    }

    @Test
    fun `future preserves the result and names incomplete work`() {
        assertEquals(42, CompletableFuture.completedFuture(42).getOrFail("reply result"))
        val failure = assertThrows<AssertionError> {
            CompletableFuture<Int>().getOrFail("reply result", Duration.ofMillis(1))
        }
        assertTrue(failure.message!!.contains("Timed out"))
        assertTrue(failure.message!!.contains("reply result"))
    }

    @Test
    fun `invalid deadlines and empty descriptions are rejected`() {
        assertThrows<IllegalArgumentException> { awaitCondition(Duration.ZERO, "positive deadline") { true } }
        assertThrows<IllegalArgumentException> { awaitCondition(description = "") { true } }
    }
}
