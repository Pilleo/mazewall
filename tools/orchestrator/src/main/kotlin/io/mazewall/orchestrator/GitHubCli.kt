// KDoc/Documentation update only. No logic changes.
/**
 * Wrapper for the GitHub CLI (`gh`).
 * Relies on external tool availability and system authentication.
 */
package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GitHubPR(
    val number: Int,
    val title: String,
    val headRefName: String,
    val body: String? = null
)

@Serializable
data class GitHubIssue(
    val number: Int,
    val title: String,
    val state: String = "open"
)

@Serializable
data class GitHubCheck(
    val state: String? = null,
    val name: String? = null,
    val bucket: String? = null,
    val event: String? = null
)

@Serializable
data class GitHubRun(
    val databaseId: Long
)

object GitHubCli {
    private val json = Json { ignoreUnknownKeys = true }
    private var config: OrchestratorConfig = OrchestratorConfig()

    fun init(config: OrchestratorConfig) {
        this.config = config
    }

    internal data class CachedValue<T>(val value: T, val expiry: Long)
    internal val cache = mutableMapOf<String, CachedValue<*>>()

    private inline fun <T> withCache(key: String, block: () -> T): T {
        val now = System.currentTimeMillis()
        val cached = cache[key]
        if (cached != null && cached.expiry > now) {
            @Suppress("UNCHECKED_CAST")
            return cached.value as T
        }
        val result = block()
        cache[key] = CachedValue(result, now + config.githubCacheTtlMs)
        return result
    }

    fun createIssue(title: String, bodyFile: File, label: String): String {
        try {
            // Ensure the label exists in the repository
            execute("gh", "label", "create", label, "--color", "ed0707", "--description", "Trigger Jules Agent")
        } catch (_: Exception) {
            // Ignore error if the label already exists
        }
        val output = execute("gh", "issue", "create", "--title", title, "--body-file", bodyFile.absolutePath, "--label", label)
        // gh issue create outputs the issue URL (e.g., https://github.com/owner/repo/issues/123)
        val issueNumber = output.substringAfterLast("/").trim()
        if (issueNumber.toIntOrNull() == null) {
            throw RuntimeException("Failed to parse issue number from gh output: $output")
        }
        return issueNumber
    }

    /** Search GitHub for an existing issue matching the given title prefix (the [issueId] part). Returns the issue number or null. */
    fun findExistingIssueNumber(issueId: String): String? {
        return try {
            val openJson = execute("gh", "issue", "list", "--state", "open", "--json", "number,title")
            val closedJson = execute("gh", "issue", "list", "--state", "closed", "--json", "number,title")
            val allIssues = parseIssues(openJson) + parseIssues(closedJson)
            allIssues.firstOrNull { it.title.contains("[$issueId]", ignoreCase = true) }?.number?.toString()
        } catch (e: Exception) {
            null
        }
    }

    fun findLinkedPR(issueNumber: String, issueId: String, julesSessionId: String?): String? {
        // Try to search via standard linked PR references
        var prListJson = execute("gh", "pr", "list", "--search", "fixes #$issueNumber", "--json", "number,title,headRefName,body")
        var prs = parsePRs(prListJson)
        if (prs.isNotEmpty()) return prs.first().number.toString()

        // Fallback: search open PRs with JSON details
        prListJson = execute("gh", "pr", "list", "--json", "number,title,headRefName,body")
        prs = parsePRs(prListJson)
        
        val matched = prs.firstOrNull { pr ->
            // Match branch name containing session ID
            (julesSessionId != null && pr.headRefName.contains(julesSessionId)) ||
            // Match body containing fixes #issueNumber
            (pr.body?.contains("#$issueNumber") == true) ||
            // Match body/branch containing local issue ID (e.g. "issue-001")
            pr.headRefName.contains(issueId, ignoreCase = true) ||
            (pr.body?.contains(issueId, ignoreCase = true) == true)
        }
        return matched?.number?.toString()
    }

    fun checkBuildStatus(prNumber: String): String = withCache("checkBuildStatus-$prNumber") {
        try {
            // First check if the PR has merge conflicts
            val mergeableJson = execute("gh", "pr", "view", prNumber, "--json", "mergeable")
            val m = json.decodeFromString<GitHubMergeable>(mergeableJson)
            if (m.mergeable == "CONFLICTING") {
                return "CONFLICT"
            }

            val checksJson = execute("gh", "pr", "checks", prNumber, "--json", "state,name,bucket,event")
            val checks = json.decodeFromString<List<GitHubCheck>>(checksJson)
            if (checks.isEmpty()) return "IN_PROGRESS"

            // If any check has state FAILURE, the build is failing
            val isFailing = checks.any { it.state == "FAILURE" }
            if (isFailing) return "FAILURE"

            // If all checks are completed and successful
            val allSuccess = checks.all { it.state == "SUCCESS" }
            if (allSuccess) return "SUCCESS"

            return "IN_PROGRESS"
        } catch (e: ProcessExecutionException) {
            val output = e.output.lowercase()
            if (output.contains("no checks reported")) {
                return "IN_PROGRESS"
            }
            System.err.println("Error checking build status for PR #$prNumber (exit code ${e.exitCode}): ${e.output}")
            return "IN_PROGRESS"
        } catch (e: Exception) {
            System.err.println("Error checking build status for PR #$prNumber: ${e.message}")
            return "IN_PROGRESS"
        }
    }

    fun getFailedBuildLogs(prNumber: String): String {
        try {
            val sha = getPrHeadSha(prNumber)
            val runsJson = execute("gh", "run", "list", "--commit", sha, "--json", "databaseId")
            val runs = json.decodeFromString<List<GitHubRun>>(runsJson)
            if (runs.isEmpty()) {
                return "Error retrieving failed build logs: No workflow runs found for commit $sha"
            }
            val runId = runs.first().databaseId.toString()
            val fullLogs = execute("gh", "run", "view", runId, "--log-failed")
            return fullLogs.lines().takeLast(80).joinToString("\n")
        } catch (e: Exception) {
            return "Error retrieving failed build logs: ${e.message}"
        }
    }

    fun getPrHeadSha(prNumber: String): String = withCache("getPrHeadSha-$prNumber") {
        val output = execute("gh", "pr", "view", prNumber, "--json", "headRefOid")
        output.substringAfter("\"headRefOid\":\"").substringBefore("\"")
    }

    fun isIssueClosed(issueNumber: String): Boolean {
        val state = execute("gh", "issue", "view", issueNumber, "--json", "state")
        return state.contains("\"state\":\"CLOSED\"", ignoreCase = true)
    }

    fun isPrMerged(prNumber: String): Boolean {
        val state = execute("gh", "pr", "view", prNumber, "--json", "state")
        return state.contains("\"state\":\"MERGED\"", ignoreCase = true)
    }

    fun getPrComments(prNumber: String): List<GitHubComment> {
        return try {
            val jsonText = execute("gh", "pr", "view", prNumber, "--json", "comments")
            val container = json.decodeFromString<CommentsContainer>(jsonText)
            container.comments
        } catch (e: Exception) {
            System.err.println("Error fetching comments for PR #$prNumber: ${e.message}")
            emptyList()
        }
    }

    private fun parseIssues(jsonText: String): List<GitHubIssue> {
        return try {
            json.decodeFromString<List<GitHubIssue>>(jsonText)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parsePRs(jsonText: String): List<GitHubPR> {
        return try {
            json.decodeFromString<List<GitHubPR>>(jsonText)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun execute(vararg command: String): String {
        return RetryUtils.retry(config.maxRetries, config.initialRetryDelayMs, LoggingEnv) {
            val pb = ProcessBuilder(*command)
            val process = pb.redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw ProcessExecutionException(command.joinToString(" "), exitCode, output.trim())
            }
            output.trim()
        }
    }

    private object LoggingEnv : OrchestratorEnvironment {
        override fun println(message: Any?) {}
        override fun print(message: Any?) {}
        override fun errPrintln(message: Any?) = System.err.println("  [GitHubCli] $message")
        override fun sleep(duration: Long, unit: TimeUnit) = unit.sleep(duration)
        override fun ringBell(times: Int) {}
        override fun readLine(): String? = null
        override fun sendNotification(message: String) {}
        override fun requestApproval(issueId: String, text: String): Boolean = false
        override fun findExistingIssueNumber(issueId: String): String? = null
        override fun createIssue(title: String, bodyFile: File, label: String): String = ""
        override fun isIssueClosed(issueNumber: String): Boolean = false
        override fun findLinkedPR(issueNumber: String, issueId: String, sessionId: String?): String? = null
        override fun isPrMerged(prNumber: String): Boolean = false
        override fun getPrHeadSha(prNumber: String): String = ""
        override fun checkBuildStatus(prNumber: String): String = ""
        override fun getPrComments(prNumber: String): List<GitHubComment> = emptyList()
        override fun commentOnPr(prNumber: String, body: String) {}
        override fun getPrDiff(prNumber: String): String = ""
        override fun getFailedBuildLogs(prNumber: String): String = ""
        override fun getPrUrl(prNumber: String): String = ""
        override fun getJulesSession(issueId: String): JulesSession? = null
        override fun parseAllIssues(): List<BacklogIssue> = emptyList()
        override fun writeGithubIssue(issue: BacklogIssue, number: Int) {}
        override fun removeGithubIssue(issue: BacklogIssue) {}
        override fun markIssueAsResolved(issue: BacklogIssue) {}
        override fun deleteStateFile() {}
        override fun generateKnowledgeMap() {}
        override val config: OrchestratorConfig get() = GitHubCli.config
    }
}

class ProcessExecutionException(val command: String, val exitCode: Int, val output: String) :
    RuntimeException("Command '$command' failed with exit code $exitCode. Output:\n$output")

@Serializable
data class GitHubComment(
    val author: GitHubCommentAuthor? = null,
    val body: String,
    val createdAt: String
)

@Serializable
data class GitHubCommentAuthor(
    val login: String
)

@Serializable
data class CommentsContainer(
    val comments: List<GitHubComment>
)

@Serializable
data class GitHubMergeable(
    val mergeable: String
)
