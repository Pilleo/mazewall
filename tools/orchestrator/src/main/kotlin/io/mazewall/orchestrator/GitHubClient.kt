package io.mazewall.orchestrator

interface GitHubClient {
    fun findExistingIssueNumber(issueId: String): String?
    fun findLinkedPR(issueNumber: String, issueId: String, julesSessionId: String?): String?
    fun checkBuildStatus(prNumber: String): String
    fun getFailedBuildLogs(prNumber: String): String
    fun getPrHeadSha(prNumber: String): String
    fun isIssueClosed(issueNumber: String): Boolean
    fun isPrMerged(prNumber: String): Boolean
    fun getPrComments(prNumber: String): List<GitHubComment>
    fun createIssue(title: String, body: String, label: String): String
    fun commentOnPr(prNumber: String, body: String)
    fun commentOnIssue(issueNumber: String, body: String)
    fun getPrDiff(prNumber: String): String
    fun getPrUrl(prNumber: String): String
    fun isCommitEmpty(prNumber: String, shaOld: String, shaNew: String): Boolean
}
