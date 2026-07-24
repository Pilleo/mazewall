package io.mazewall.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetryUtilsTest {

    @Test
    fun `retry returns success on first try`() {
        var attempts = 0
        val result = RetryUtils.retry(maxRetries = 3, initialDelayMs = 1) {
            attempts++
            "success"
        }
        assertEquals("success", result)
        assertEquals(1, attempts)
    }

    @Test
    fun `retry succeeds after one failure`() {
        var attempts = 0
        val result = RetryUtils.retry(maxRetries = 3, initialDelayMs = 1) {
            attempts++
            if (attempts == 1) throw RuntimeException("fail")
            "success"
        }
        assertEquals("success", result)
        assertEquals(2, attempts)
    }

    @Test
    fun `retry fails after max retries`() {
        var attempts = 0
        assertFailsWith<RuntimeException> {
            RetryUtils.retry(maxRetries = 3, initialDelayMs = 1) {
                attempts++
                throw RuntimeException("fail")
            }
        }
        assertEquals(3, attempts)
    }
}
