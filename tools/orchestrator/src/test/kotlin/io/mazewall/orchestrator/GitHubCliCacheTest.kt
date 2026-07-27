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

    @Test
    fun testIsPrMatchingBySessionIdUrl() {
        val client = RealGitHubClient(OrchestratorConfig())
        val pr = GitHubPR(
            number = 361,
            title = "Profiler Audit Report",
            headRefName = "jules-335703985290049335-770d54e0",
            body = "Fixes #358\n*PR created by Jules for task [335703985290049335](https://jules.google.com/task/335703985290049335)*"
        )

        // Clean session ID extraction from URL
        val sessionUrl = "https://jules.google.com/task/335703985290049335"
        val cleanSessionId = sessionUrl.substringAfterLast("/").trim()

        assertTrue(client.isPrMatching(pr, "358", "issue-20260727-034322-review-task", cleanSessionId))
        assertTrue(client.isPrMatching(pr, "358", "issue-20260727-034322-review-task", null))
        assertTrue(client.isPrMatching(pr, "", "issue-20260727-034322-review-task", cleanSessionId))
    }

    @Test
    fun testIsPrMatchingByIssueNumberAndId() {
        val client = RealGitHubClient(OrchestratorConfig())
        val pr = GitHubPR(
            number = 320,
            title = "Socket Address Family Filtering",
            headRefName = "socket-address-family-filtering-2569796096437137191",
            body = "Implements address family checks.\nFixes #318"
        )

        assertTrue(client.isPrMatching(pr, "318", "issue-318", null))
        assertFalse(client.isPrMatching(pr, "999", "issue-999", "999999"))
    }
}
