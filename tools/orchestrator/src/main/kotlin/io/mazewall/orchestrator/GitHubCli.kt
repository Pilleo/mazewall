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

class RealGitHubClient(private val config: OrchestratorConfig) : GitHubClient {
    private val json = Json { ignoreUnknownKeys = true }

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

    override fun createIssue(title: String, body: String, label: String): String {
        val preamble = """
            💡 **Jules Instructions Before Starting:**
            You are an experienced, java/kotlin developer, expert in linux, seccomp, landlock, security. This is your task:
            1. **Verify Backlog Items**: Find the code related to this issue and verify if the issue/bug is actually present in the current codebase.
            2. **Design Before Action**: Carefully review the proposed fix, downsides, benefits, and alternative approaches.
            3. **Raise Doubts Early**: If you have any doubts about the correctness or architecture of the fix, stop and ask the operator.
            4. **Doable scope**: Verify the issue is actually singular. If it tries to fix many big things - create subissues and notify
            5. **Challenge**: Question the description of an issue and suggested fix. Is it really an issue? Is the fix making things better, or is it just a hack?

            ---

        """.trimIndent()

        val fullBody = preamble + body

        try {
            // Ensure the label exists in the repository (use --force to update/ignore if already exists)
            execute("gh", "label", "create", label, "--force", "--color", "ed0707", "--description", "Trigger Jules Agent")
        } catch (_: Exception) {
            // Ignore error if the label already exists
        }

        val directory = File("build/tmp").apply { mkdirs() }
        val tempFile = File.createTempFile("issue_body_", ".tmp", directory)
        val output = try {
            tempFile.writeText(fullBody)
            execute("gh", "issue", "create", "--title", title, "--body-file", tempFile.absolutePath, "--label", label)
        } finally {
            tempFile.delete()
        }

        // gh issue create outputs the issue URL (e.g., https://github.com/owner/repo/issues/123)
        val issueNumber = output.substringAfterLast("/").trim()
        if (issueNumber.toIntOrNull() == null) {
            throw RuntimeException("Failed to parse issue number from gh output: $output")
        }
        return issueNumber
    }

    override fun findExistingIssueNumber(issueId: String): String? {
        return try {
            val openJson = execute("gh", "issue", "list", "--state", "open", "--json", "number,title")
            val openIssues = parseIssues(openJson)
            openIssues.firstOrNull { it.title.contains("[$issueId]", ignoreCase = true) }?.number?.toString()
        } catch (e: Exception) {
            null
        }
    }

    override fun findLinkedPR(issueNumber: String, issueId: String, julesSessionId: String?): String? {
        var prListJson = execute("gh", "pr", "list", "--search", "fixes #$issueNumber", "--json", "number,title,headRefName,body")
        var prs = parsePRs(prListJson)
        if (prs.isNotEmpty()) return prs.first().number.toString()

        prListJson = execute("gh", "pr", "list", "--json", "number,title,headRefName,body")
        prs = parsePRs(prListJson)
        
        val matched = prs.firstOrNull { pr ->
            (julesSessionId != null && pr.headRefName.contains(julesSessionId)) ||
            (pr.body?.contains("#$issueNumber") == true) ||
            pr.headRefName.contains(issueId, ignoreCase = true) ||
            (pr.body?.contains(issueId, ignoreCase = true) == true)
        }
        return matched?.number?.toString()
    }

    override fun checkBuildStatus(prNumber: String): String = withCache("checkBuildStatus-$prNumber") {
        try {
            val mergeableJson = execute("gh", "pr", "view", prNumber, "--json", "mergeable")
            val m = json.decodeFromString(GitHubMergeable.serializer(), mergeableJson)
            if (m.mergeable == "CONFLICTING") {
                return "CONFLICT"
            }

            val checksJson = execute("gh", "pr", "checks", prNumber, "--json", "state,name,bucket,event")
            val checks = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GitHubCheck.serializer()), checksJson)
            if (checks.isEmpty()) return "IN_PROGRESS"

            val isFailing = checks.any {
                it.state == "FAILURE" ||
                it.state == "CANCELLED" ||
                it.state == "ACTION_REQUIRED"
            }
            if (isFailing) return "FAILURE"

            val isPending = checks.any {
                it.state == "PENDING" ||
                it.state == "IN_PROGRESS" ||
                it.state == "EXPECTED"
            }
            if (isPending) return "IN_PROGRESS"

            return "SUCCESS"
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

    override fun getFailedBuildLogs(prNumber: String): String {
        try {
            val sha = getPrHeadSha(prNumber)
            val runsJson = execute("gh", "run", "list", "--commit", sha, "--json", "databaseId")
            val runs = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GitHubRun.serializer()), runsJson)
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

    override fun getPrHeadSha(prNumber: String): String = withCache("getPrHeadSha-$prNumber") {
        val output = execute("gh", "pr", "view", prNumber, "--json", "headRefOid")
        output.substringAfter("\"headRefOid\":\"").substringBefore("\"")
    }

    override fun isIssueClosed(issueNumber: String): Boolean {
        val state = execute("gh", "issue", "view", issueNumber, "--json", "state")
        return state.contains("\"state\":\"CLOSED\"", ignoreCase = true)
    }

    override fun isPrMerged(prNumber: String): Boolean {
        val state = execute("gh", "pr", "view", prNumber, "--json", "state")
        return state.contains("\"state\":\"MERGED\"", ignoreCase = true)
    }

    override fun getPrComments(prNumber: String): List<GitHubComment> {
        return try {
            val jsonText = execute("gh", "pr", "view", prNumber, "--json", "comments")
            val container = json.decodeFromString(CommentsContainer.serializer(), jsonText)
            container.comments
        } catch (e: Exception) {
            System.err.println("Error fetching comments for PR #$prNumber: ${e.message}")
            emptyList()
        }
    }

    private fun parseIssues(jsonText: String): List<GitHubIssue> {
        return try {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GitHubIssue.serializer()), jsonText)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parsePRs(jsonText: String): List<GitHubPR> {
        return try {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GitHubPR.serializer()), jsonText)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun execute(vararg command: String): String {
        return RetryUtils.retry(config.maxRetries, config.initialRetryDelayMs, { System.err.println("  [GitHubCli] $it") }) {
            val directory = File("build/tmp").apply { mkdirs() }
            val tempFile = File.createTempFile("gh_cmd_", ".log", directory)
            try {
                val pb = ProcessBuilder(*command)
                pb.redirectErrorStream(true)
                pb.redirectOutput(tempFile)
                val process = pb.start()
                val completed = process.waitFor(config.maxExternalCommandTimeoutMinutes, TimeUnit.MINUTES)
                if (!completed) {
                    process.destroyForcibly()
                    throw RuntimeException("Command '${command.joinToString(" ")}' timed out after ${config.maxExternalCommandTimeoutMinutes} minutes.")
                }
                val output = tempFile.readText().trim()
                val exitCode = process.exitValue()
                if (exitCode != 0) {
                    throw ProcessExecutionException(command.joinToString(" "), exitCode, output)
                }
                output
            } finally {
                tempFile.delete()
            }
        }
    }

    override fun commentOnPr(prNumber: String, body: String) {
        val directory = File("build/tmp").apply { mkdirs() }
        val tempFile = File.createTempFile("pr_comment_", ".tmp", directory)
        try {
            tempFile.writeText(body)
            execute("gh", "pr", "comment", prNumber, "--body-file", tempFile.absolutePath)
        } finally {
            tempFile.delete()
        }
    }

    override fun commentOnIssue(issueNumber: String, body: String) {
        val directory = File("build/tmp").apply { mkdirs() }
        val tempFile = File.createTempFile("issue_comment_", ".tmp", directory)
        try {
            tempFile.writeText(body)
            execute("gh", "issue", "comment", issueNumber, "--body-file", tempFile.absolutePath)
        } finally {
            tempFile.delete()
        }
    }

    override fun getPrDiff(prNumber: String): String {
        return execute("gh", "pr", "diff", prNumber)
    }

    override fun getPrUrl(prNumber: String): String {
        return execute("gh", "pr", "view", prNumber, "--json", "url")
            .substringAfter("\"url\":\"").substringBefore("\"")
    }

    override fun isCommitEmpty(prNumber: String, shaOld: String, shaNew: String): Boolean {
        if (shaOld.isEmpty() || shaNew.isEmpty()) return false
        return try {
            val output = execute("gh", "api", "repos/:owner/:repo/compare/$shaOld...$shaNew", "--jq", ".files | length")
            val filesChanged = output.trim().toIntOrNull() ?: 0
            filesChanged == 0
        } catch (e: Exception) {
            System.err.println("Error checking if commit is empty via gh api compare: ${e.message}")
            false
        }
    }

    override fun rebaseBranch(prNumber: String): Boolean {
        // 1. Try GitHub API native branch update with rebase first
        try {
            execute("gh", "pr", "update-branch", prNumber, "--rebase")
            return true
        } catch (e: Exception) {
            System.err.println("gh pr update-branch --rebase failed for PR #$prNumber (${e.message}). Attempting local git fetch and rebase...")
        }

        // 2. Fallback to local git rebase and force push
        return try {
            val branchName = execute("gh", "pr", "view", prNumber, "--json", "headRefName", "--jq", ".headRefName").trim()
            if (branchName.isBlank()) return false

            execute("git", "fetch", "origin", branchName)
            execute("git", "fetch", "origin", "master")

            // Create temporary worktree or checkout branch safely
            val currentBranch = execute("git", "rev-parse", "--abbrev-ref", "HEAD").trim()
            try {
                execute("git", "checkout", branchName)
                execute("git", "rebase", "origin/master")
                execute("git", "push", "--force-with-lease", "origin", branchName)
                true
            } finally {
                if (currentBranch.isNotBlank() && currentBranch != "HEAD") {
                    execute("git", "checkout", currentBranch)
                }
            }
        } catch (e: Exception) {
            System.err.println("Local git rebase failed for PR #$prNumber: ${e.message}")
            false
        }
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
