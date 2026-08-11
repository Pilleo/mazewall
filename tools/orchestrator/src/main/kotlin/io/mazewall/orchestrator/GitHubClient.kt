package io.mazewall.orchestrator

data class PrMergeStatus(
    val mergeable: String,
    val behindBy: Int,
    val isError: Boolean = false,
    val errorMessage: String? = null
) {
    fun isAuthError(): Boolean {
        val errMsg = errorMessage ?: return false
        val lower = errMsg.lowercase()
        return lower.contains("unauthorized") ||
               lower.contains("401") ||
               lower.contains("bad credentials") ||
               lower.contains("login") ||
               lower.contains("authentication") ||
               lower.contains("access token") ||
               lower.contains("rate limit") ||
               lower.contains("403") ||
               lower.contains("forbidden")
    }
}

interface GitHubClient {
    fun getPrMergeStatus(prNumber: String): PrMergeStatus
    fun findExistingIssueNumber(issueId: String): String?
    fun findLinkedPR(issueNumber: String, issueId: String, julesSessionId: String?): String?
    fun checkBuildStatus(prNumber: String): String
    fun getFailedBuildLogs(prNumber: String): String
    fun getPrHeadSha(prNumber: String): String
    fun isIssueClosed(issueNumber: String): Boolean
    fun isPrClosed(prNumber: String): Boolean
    fun isPrMerged(prNumber: String): Boolean
    fun getPrComments(prNumber: String): List<GitHubComment>
    fun createIssue(title: String, body: String, label: String): String
    fun getRepoName(): String
    fun addLabel(issueNumber: String, label: String)
    fun ensureLabelExists(label: String)
    fun labelPr(prNumber: String, label: String)
    fun commentOnPr(prNumber: String, body: String)
    fun commentOnIssue(issueNumber: String, body: String)
    fun getPrDiff(prNumber: String): String
    fun getPrUrl(prNumber: String): String
    fun isCommitEmpty(prNumber: String, shaOld: String, shaNew: String): Boolean



    /**
     * Clears all cached properties and statuses associated with a PR (such as merge status, head SHA, build status).
     * This is crucial to ensure that subsequent queries fetch fresh, non-stale data from the GitHub API.
     */
    fun clearPrCache(prNumber: String)
}
