package io.mazewall.orchestrator

import kotlin.test.*

class GitHubCliCacheTest {




    @Test
    fun testRetryUtils() {
        var calls = 0
        val result = RetryUtils.retry(maxRetries = 3, initialDelayMs = 1) {
            calls++
            if (calls < 2) throw RuntimeException("Fail")
            "Success"
        }
        assertEquals(2, calls)
        assertEquals("Success", result)
    }

    @Test
    fun testRetryUtilsFails() {
        assertFails {
            RetryUtils.retry(maxRetries = 2, initialDelayMs = 1) {
                throw RuntimeException("Permanent Fail")
            }
        }
    }
}
