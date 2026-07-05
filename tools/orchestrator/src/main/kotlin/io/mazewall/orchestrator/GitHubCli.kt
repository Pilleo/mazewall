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

object GitHubCli {
    private val json = Json { ignoreUnknownKeys = true }

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

    fun checkBuildStatus(prNumber: String): String {
        try {
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
        } catch (e: Exception) {
            System.err.println("Error checking build status for PR #$prNumber: ${e.message}")
            return "IN_PROGRESS"
        }
    }

    fun getFailedBuildLogs(prNumber: String): String {
        try {
            // Get the failed logs of the most recent run directly (capture only the tail to avoid token overflow)
            val fullLogs = execute("gh", "run", "view", "--log-failed")
            return fullLogs.lines().takeLast(80).joinToString("\n")
        } catch (e: Exception) {
            return "Error retrieving failed build logs: ${e.message}"
        }
    }

    fun getPrHeadSha(prNumber: String): String {
        val output = execute("gh", "pr", "view", prNumber, "--json", "headRefOid")
        return output.substringAfter("\"headRefOid\":\"").substringBefore("\"")
    }

    fun isIssueClosed(issueNumber: String): Boolean {
        val state = execute("gh", "issue", "view", issueNumber, "--json", "state")
        return state.contains("\"state\":\"CLOSED\"", ignoreCase = true)
    }

    fun isPrMerged(prNumber: String): Boolean {
        val state = execute("gh", "pr", "view", prNumber, "--json", "state")
        return state.contains("\"state\":\"MERGED\"", ignoreCase = true)
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
        val pb = ProcessBuilder(*command)
        val env = pb.environment()
        val customToken = System.getProperty("GITHUB_TOKEN")
        if (customToken != null) {
            env["GITHUB_TOKEN"] = customToken
        } else {
            env.remove("GITHUB_TOKEN")
        }
        val process = pb.redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(2, TimeUnit.MINUTES)
        return output.trim()
    }
}
