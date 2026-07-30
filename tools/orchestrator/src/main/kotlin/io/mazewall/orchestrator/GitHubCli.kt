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

    override fun approveRescue(prNumber: String, rescueBranchName: String) {
        val branchName = execute("gh", "pr", "view", prNumber, "--json", "headRefName", "--jq", ".headRefName").trim()
        execute("git", "push", "--force", "origin", "origin/$rescueBranchName:$branchName")
        // Clean up rescue branch
        execute("git", "push", "origin", "--delete", rescueBranchName)
    }

    override fun clearPrCache(prNumber: String) {
        cache.remove("checkBuildStatus-$prNumber")
        cache.remove("getPrMergeStatus-$prNumber")
        cache.remove("getPrHeadSha-$prNumber")
    }

    override fun getPrMergeStatus(prNumber: String): PrMergeStatus {
        val cacheKey = "getPrMergeStatus-$prNumber"
        val result = withCache(cacheKey) {
            try {
                val jsonText = execute("gh", "pr", "view", prNumber, "--json", "mergeable,mergeStateStatus")
                val status = json.decodeFromString(GitHubPrStatus.serializer(), jsonText)
                val isBehind = status.mergeStateStatus == "BEHIND"
                PrMergeStatus(status.mergeable, if (isBehind) 1 else 0)
            } catch (e: Exception) {
                System.err.println("Error getting PR merge status for PR #$prNumber: ${e.message}")
                PrMergeStatus("UNKNOWN", 0, isError = true, errorMessage = e.message ?: e.toString())
            }
        }
        if (result.isError) {
            cache.remove(cacheKey)
        }
        return result
    }

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
        val cleanSessionId = julesSessionId?.substringAfterLast("/")?.trim()?.takeIf { it.isNotBlank() }

        val searchQueries = mutableListOf<String>()
        if (issueNumber.isNotBlank()) {
            searchQueries.add("fixes #$issueNumber")
            searchQueries.add("#$issueNumber")
        }
        if (cleanSessionId != null) {
            searchQueries.add(cleanSessionId)
        }
        if (issueId.isNotBlank()) {
            searchQueries.add(issueId)
        }

        for (query in searchQueries) {
            try {
                val prListJson = execute("gh", "pr", "list", "--search", query, "--json", "number,title,headRefName,body")
                val prs = parsePRs(prListJson)
                val matched = prs.firstOrNull { isPrMatching(it, issueNumber, issueId, cleanSessionId) }
                if (matched != null) return matched.number.toString()
            } catch (_: Exception) {
                // Ignore failure of specific search query and try next fallback
            }
        }

        return try {
            val prListJson = execute("gh", "pr", "list", "--state", "open", "--json", "number,title,headRefName,body")
            val prs = parsePRs(prListJson)
            val matched = prs.firstOrNull { isPrMatching(it, issueNumber, issueId, cleanSessionId) }
            matched?.number?.toString()
        } catch (e: Exception) {
            System.err.println("Error finding linked PR for issue #$issueNumber ($issueId): ${e.message}")
            null
        }
    }

    internal fun isPrMatching(pr: GitHubPR, issueNumber: String, issueId: String, cleanSessionId: String?): Boolean {
        if (cleanSessionId != null) {
            if (pr.headRefName.contains(cleanSessionId, ignoreCase = true) ||
                (pr.body?.contains(cleanSessionId, ignoreCase = true) == true)) {
                return true
            }
        }

        if (issueNumber.isNotBlank()) {
            val body = pr.body ?: ""
            val title = pr.title
            if (body.contains("#$issueNumber") ||
                body.contains("issue $issueNumber", ignoreCase = true) ||
                title.contains("#$issueNumber") ||
                title.contains("issue $issueNumber", ignoreCase = true)) {
                return true
            }
        }

        if (issueId.isNotBlank()) {
            if (pr.headRefName.contains(issueId, ignoreCase = true) ||
                (pr.body?.contains(issueId, ignoreCase = true) == true) ||
                pr.title.contains(issueId, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    override fun checkBuildStatus(prNumber: String): String = withCache("checkBuildStatus-$prNumber") {
        try {
            // First check if the PR has merge conflicts (only DIRTY indicates real git conflicts)
            val mergeableJson = execute("gh", "pr", "view", prNumber, "--json", "mergeable,mergeStateStatus,state")
            val m = json.decodeFromString(GitHubMergeable.serializer(), mergeableJson)

            if (m.state != "MERGED" && m.state != "CLOSED") {
                if (m.mergeStateStatus == "DIRTY" || m.mergeable == "CONFLICTING") {
                    System.out.println("  [gh pr view #$prNumber] Conflict detected: state=${m.state}, mergeable=${m.mergeable}, mergeStateStatus=${m.mergeStateStatus}")
                    return "CONFLICT"
                }
            }

            val checksJson = execute("gh", "pr", "checks", prNumber, "--json", "state,name,bucket,event")
            val checks = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GitHubCheck.serializer()), checksJson)
            if (checks.isEmpty()) return "IN_PROGRESS"

            // Evaluate check failure using both bucket and state fields
            val isFailing = checks.any {
                it.bucket == "fail" ||
                it.state == "FAILURE" ||
                it.state == "CANCELLED" ||
                it.state == "ACTION_REQUIRED"
            }
            if (isFailing) return "FAILURE"

            // Evaluate pending build status using bucket and pending states
            val isPending = checks.any {
                it.bucket == "pending" ||
                it.state == "PENDING" ||
                it.state == "IN_PROGRESS" ||
                it.state == "EXPECTED" ||
                it.state == "QUEUED" ||
                it.state == "WAITING"
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

    override fun isPrClosed(prNumber: String): Boolean {
        val state = execute("gh", "pr", "view", prNumber, "--json", "state")
        return state.contains("\"state\":\"CLOSED\"", ignoreCase = true) ||
               state.contains("\"state\":\"MERGED\"", ignoreCase = true)
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
        return executeInDir(null, *command, retry = true)
    }

    private fun executeWithoutRetry(vararg command: String): String {
        return executeInDir(null, *command, retry = false)
    }

    private fun executeInDir(workingDir: File?, vararg command: String, retry: Boolean = true): String {
        val action = {
            val directory = File("build/tmp").apply { mkdirs() }
            val tempFile = File.createTempFile("gh_cmd_", ".log", directory)
            try {
                val pb = ProcessBuilder(*command)
                if (workingDir != null) {
                    pb.directory(workingDir)
                }
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

        return if (retry) {
            RetryUtils.retry(config.maxRetries, config.initialRetryDelayMs, { System.err.println("  [GitHubCli] $it") }, action)
        } else {
            action()
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

    /**
     * Integrates PR branch changes with origin/master using the Surgical Intended-Files Apply algorithm.
     *
     * 🧠 DESIGN DECISIONS (WHY, WHAT, WHEN, WHERE):
     *
     * 🟢 WHY:
     * Standard `git rebase` replays every individual commit. This is highly vulnerable to merge conflicts.
     * Standard full-tree patch apply (`git apply --3way`) computes a full-tree diff and applies it onto master.
     * However, Jules operates in a stale/frozen workspace. If master advances (adding new files or modifying other files)
     * after Jules diverged, those master-added files won't be in Jules's stale workspace. Applying a full-tree diff
     * would silently DELETE those new master files from the final PR branch perspective, causing serious regression bugs.
     * Furthermore, standard git merge-base checkout sequences can pollute the directory or error out on moved files.
     * To be 100% deterministic, we must completely ignore stale workspace deletions and only apply files Jules *intended* to touch.
     *
     * 🔵 WHAT:
     * We employ Surgical Intended-Files Extraction:
     * 1. Find the parent of the first commit on Jules's branch (`INITIAL_BASE`) using `git rev-list --reverse` to track
     *    the exact point where Jules diverged from master before any rebase/merge commits. Fallback to `git merge-base` if needed.
     * 2. Extract `INTENDED_FILES` using `git diff --name-only --diff-filter=AM`. The `--diff-filter=AM` ensures that we ONLY
     *    capture files that Jules Added (A) or Modified (M) for the task. We explicitly ignore Deleted (D) files, meaning stale
     *    workspace deletions are completely bypassed.
     * 3. Set up a fresh worktree pointing to origin/master.
     * 4. For each intended file, copy its exact version from origin/$branchName (`git checkout origin/$BRANCH -- <file>`) and
     *    stage it (`git add <file>`). This overwrites any master version of only these files, while master-only files added
     *    after divergence remain completely untouched and preserved.
     *
     * 🟡 WHEN:
     * This is triggered whenever the orchestrator daemon detects a PR is out of date (behind master) or has merge conflicts
     * (CONFLICTING status) during active polling loops.
     *
     * 🔴 WHERE:
     * Executed within a detached temporary worktree (`../temp-rebase-<prNumber>`) to ensure absolute isolation.
     * This prevents working-tree pollution or conflicts with uncommitted/untracked local edits in the main agent workspace.
     */
    private fun isFileAllowed(file: String, targetFiles: List<String>): Boolean {
        if (file.startsWith("docs/internals/backlog/") && file.endsWith(".md")) return true
        val normalizedFile = file.replace('\\', '/').trim()
        return targetFiles.any { target ->
            val normalizedTarget = target.replace('\\', '/').trim().removePrefix(":")

            // Exact match or suffix match
            if (normalizedFile == normalizedTarget || normalizedFile.endsWith("/$normalizedTarget")) return true

            // If the target is a main file, also allow its corresponding test file
            if (normalizedTarget.contains("/src/main/")) {
                val testTarget = normalizedTarget
                    .replace("/src/main/", "/src/test/")
                    .replace(".kt", "Test.kt")
                    .replace(".java", "Test.java")
                if (normalizedFile == testTarget || normalizedFile.endsWith("/$testTarget")) return true
            }
            false
        }
    }

    override fun rebaseBranch(prNumber: String, sessionId: String?): RebaseResult {
        return BranchRebaser(
            execute = { args -> execute(*args) },
            executeInDir = { dir, args -> executeInDir(dir, *args) },
            executeInDirNoRetry = { dir, args -> executeInDir(dir, *args, retry = false) },
            clearPrCache = ::clearPrCache
        ).run(prNumber, sessionId)
    }

    override fun rebaseBranchFallback(prNumber: String, sessionId: String?, targetFiles: List<String>): RebaseResult {
        return BranchRebaser(
            execute = { args -> execute(*args) },
            executeInDir = { dir, args -> executeInDir(dir, *args) },
            executeInDirNoRetry = { dir, args -> executeInDir(dir, *args, retry = false) },
            clearPrCache = ::clearPrCache
        ).runFallback(prNumber, sessionId, targetFiles)
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
    val mergeable: String? = null,
    val mergeStateStatus: String? = null,
    val state: String? = null
)

@Serializable
data class GitHubPrStatus(
    val mergeable: String = "UNKNOWN",
    val mergeStateStatus: String = "UNKNOWN"
)
