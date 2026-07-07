// KDoc/Documentation update only. No logic changes.
/**
 * Represents a remote Jules Agent session.
 * Note: The ID can be a very large numeric string, exceeding standard Long limits.
 */
package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit

data class JulesSession(
    val id: String,
    val description: String,
    val repo: String,
    val status: String
)

object JulesCli {
    private var config: OrchestratorConfig = OrchestratorConfig()

    fun init(config: OrchestratorConfig) {
        this.config = config
    }

    fun triggerSession(repo: String, issueId: String, prompt: String) {
        val sessionDescription = "[$issueId] ${prompt.take(150)}"
        println("🚀 Triggering remote Jules session for issue $issueId...")
        execute("jules", "remote", "new", "--repo", repo, "--session", sessionDescription)
    }

    fun getActiveSession(issueId: String): JulesSession? {
        val sessions = listSessions()
        // Find the session where description contains the issue ID marker, e.g. "[issue-001]"
        return sessions.firstOrNull { it.description.contains("[$issueId]", ignoreCase = true) }
    }

    /** Lists active remote Jules sessions by parsing the CLI output. */
    fun listSessions(): List<JulesSession> {
        val output = executeWithPipe("jules", "remote", "list", "--session")
        val lines = output.lines()
        if (lines.size <= 1) return emptyList()

        val sessions = mutableListOf<JulesSession>()
        // Parse column headers to locate column positions, or parse using token split since IDs are 19-digit numbers
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("ID") || trimmed.startsWith("Status")) {
                continue
            }

            // Split line by multiple spaces
            val tokens = trimmed.split(Regex("\\s{2,}"))
            if (tokens.size >= 5) {
                val id = tokens[0].trim()
                // Make sure first token is a numeric string (Jules Session ID)
                if (id.isNotEmpty() && id.all { it.isDigit() }) {
                    val desc = tokens[1].trim()
                    val repo = tokens[2].trim()
                    val status = tokens[4].trim()
                    sessions.add(JulesSession(id, desc, repo, status))
                }
            }
        }
        return sessions
    }

    private fun execute(vararg command: String): String {
        return RetryUtils.retry(config.maxRetries, config.initialRetryDelayMs, LoggingEnv) {
            val pb = ProcessBuilder(*command)
            val process = pb.redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(config.maxExternalCommandTimeoutMinutes, TimeUnit.MINUTES)
            output.trim()
        }
    }

    private fun executeWithPipe(vararg command: String): String {
        return RetryUtils.retry(config.maxRetries, config.initialRetryDelayMs, LoggingEnv) {
            // Run command piped to cat to disable TUI/terminal truncation
            val pb = ProcessBuilder("bash", "-c", "${command.joinToString(" ")} | cat")
            val process = pb.redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(config.maxExternalCommandTimeoutMinutes, TimeUnit.MINUTES)
            output.trim()
        }
    }

    private object LoggingEnv : OrchestratorEnvironment {
        override fun println(message: Any?) {}
        override fun print(message: Any?) {}
        override fun errPrintln(message: Any?) = System.err.println("  [JulesCli] $message")
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
        override val config: OrchestratorConfig get() = JulesCli.config
    }
}
