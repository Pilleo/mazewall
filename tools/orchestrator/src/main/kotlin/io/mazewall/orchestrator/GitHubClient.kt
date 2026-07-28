package io.mazewall.orchestrator

data class RebaseResult(
    val success: Boolean,
    val conflictCount: Int,
    val conflictedFiles: List<String> = emptyList(),
    val needsRescueApproval: Boolean = false,
    val rescueBranchName: String? = null
)

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
    fun commentOnPr(prNumber: String, body: String)
    fun commentOnIssue(issueNumber: String, body: String)
    fun getPrDiff(prNumber: String): String
    fun getPrUrl(prNumber: String): String
    fun isCommitEmpty(prNumber: String, shaOld: String, shaNew: String): Boolean

    /**
     * Merges the current origin/master into the given PR branch using an isolated
     * worktree. Preserves Jules's original commits and authorship. The resulting
     * PR diff will contain exactly Jules's session changes relative to master.
     *
     * Returns [RebaseResult.success = true] if the merge succeeded and was pushed.
     * Returns [RebaseResult.success = false] if there are merge conflicts (human intervention required).
     */
    fun mergeMasterIntoBranch(prNumber: String, sessionId: String?, issueId: String? = null): RebaseResult
    fun approveRescue(prNumber: String, rescueBranchName: String)

    /**
     * Clears all cached properties and statuses associated with a PR (such as merge status, head SHA, build status).
     * This is crucial to ensure that subsequent queries fetch fresh, non-stale data from the GitHub API.
     */
    fun clearPrCache(prNumber: String)
}
