package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit

data class OrchestratorConfig(
    val pollingIntervalSeconds: Long = 30,
    val backlogCheckIntervalMinutes: Long = 2,
    val julesTriggerAttempts: Int = 12,
    val julesTriggerIntervalSeconds: Long = 15,
    val ciFailureRetryMinutes: Long = 5,
    val daemonErrorRetryMinutes: Long = 2,
    val maxExternalCommandTimeoutMinutes: Long = 10,
    val taskTimeoutThresholdMinutes: Long = 60,
    val maxRetries: Int = 3,
    val initialRetryDelayMs: Long = 1000,
    val githubCacheTtlMs: Long = 10000,
    val stuckPendingThresholdMs: Long = 900_000
)

interface OrchestratorEnvironment {
    val config: OrchestratorConfig
    fun println(message: Any?)
    fun print(message: Any?)
    fun errPrintln(message: Any?)
    fun sleep(duration: Long, unit: TimeUnit)
    fun ringBell(times: Int)
    fun readLine(): String?
    fun getEnvOrNull(key: String): String?

    // Bot
    fun sendNotification(message: String)
    fun requestApproval(issueId: String, text: String): Boolean

    // GitHub
    fun findExistingIssueNumber(issueId: String): String?
    fun createIssue(title: String, body: String, label: String): String
    fun isIssueClosed(issueNumber: String): Boolean
    fun findLinkedPR(issueNumber: String, issueId: String, sessionId: String?): String?
    fun isPrMerged(prNumber: String): Boolean
    fun getPrHeadSha(prNumber: String): String
    fun checkBuildStatus(prNumber: String): String
    fun getPrComments(prNumber: String): List<GitHubComment>
    fun commentOnPr(prNumber: String, body: String)
    fun commentOnIssue(issueNumber: String, body: String)
    fun getPrDiff(prNumber: String): String
    fun getFailedBuildLogs(prNumber: String): String
    fun getPrUrl(prNumber: String): String

    // Jules
    fun getJulesSession(issueId: String): JulesSession?
    fun hasUnableToCompleteActivity(sessionId: String): Boolean
    fun sendJulesSessionMessage(sessionId: String, prompt: String)

    // Backlog / Filesystem
    fun parseAllIssues(): List<BacklogIssue>
    fun writeGithubIssue(issue: BacklogIssue, number: Int)
    fun removeGithubIssue(issue: BacklogIssue)
    fun markIssueAsResolved(issue: BacklogIssue)
    fun deleteStateFile()
    fun generateKnowledgeMap()
}

class RealOrchestratorEnvironment(
    private val bot: TelegramBot?,
    private val backlogDir: File,
    private val resolvedDir: File,
    private val stateFile: File,
    override val config: OrchestratorConfig = OrchestratorConfig()
) : OrchestratorEnvironment {

    override fun println(message: Any?) = kotlin.io.println(message)
    override fun print(message: Any?) = kotlin.io.print(message)
    override fun errPrintln(message: Any?) = System.err.println(message)
    override fun sleep(duration: Long, unit: TimeUnit) = unit.sleep(duration)

    override fun ringBell(times: Int) {
        try {
            repeat(times) {
                var rung = false
                try {
                    java.awt.Toolkit.getDefaultToolkit().beep()
                    rung = true
                } catch (_: Throwable) {}

                try {
                    ProcessBuilder("tput", "bel").inheritIO().start().waitFor()
                    rung = true
                } catch (_: Throwable) {}

                if (!rung) {
                    kotlin.io.print("\u0007")
                    System.out.flush()
                }
                Thread.sleep(300)
            }
        } catch (_: Exception) {}
    }

    override fun readLine(): String? = readlnOrNull()

    override fun getEnvOrNull(key: String): String? {
        return System.getenv(key) ?: System.getProperty(key)
    }

    override fun sendNotification(message: String) {
        bot?.sendMessage(message)
    }

    override fun requestApproval(issueId: String, text: String): Boolean {
        return if (bot != null) {
            bot.sendMessageWithApprovalMarkup(issueId, text)
            bot.waitForApproval(issueId)
        } else {
            print("\u001B[1;31m🔔 [APPROVAL REQUIRED] $text (y/n): \u001B[0m")
            System.out.flush()
            val input = readLine()?.trim()?.lowercase()
            input == "y" || input == "yes"
        }
    }

    override fun findExistingIssueNumber(issueId: String): String? = GitHubCli.findExistingIssueNumber(issueId)

    override fun createIssue(title: String, body: String, label: String): String {
        val preamble = """
            💡 **Jules Instructions Before Starting:**
            1. **Verify Backlog Items**: Find the code related to this issue and verify if the issue/bug is actually present in the current codebase.
            2. **Design Before Action**: Carefully review the proposed fix, downsides, benefits, and alternative approaches.
            3. **Raise Doubts Early**: If you have any doubts about the correctness or architecture of the fix, stop and ask the operator.

            ---

        """.trimIndent()

        return GitHubCli.createIssue(title, preamble + body, label)
    }

    override fun isIssueClosed(issueNumber: String): Boolean = GitHubCli.isIssueClosed(issueNumber)

    override fun findLinkedPR(issueNumber: String, issueId: String, sessionId: String?): String? =
        GitHubCli.findLinkedPR(issueNumber, issueId, sessionId)

    override fun isPrMerged(prNumber: String): Boolean = GitHubCli.isPrMerged(prNumber)

    override fun getPrHeadSha(prNumber: String): String = GitHubCli.getPrHeadSha(prNumber)

    override fun checkBuildStatus(prNumber: String): String = GitHubCli.checkBuildStatus(prNumber)

    override fun getPrComments(prNumber: String): List<GitHubComment> = GitHubCli.getPrComments(prNumber)

    override fun commentOnPr(prNumber: String, body: String) {
        val directory = File("build/tmp").apply { mkdirs() }
        val tempFile = File.createTempFile("pr_comment_", ".tmp", directory)
        try {
            tempFile.writeText(body)
            executeCmd("gh", "pr", "comment", prNumber, "--body-file", tempFile.absolutePath)
        } finally {
            tempFile.delete()
        }
    }

    override fun commentOnIssue(issueNumber: String, body: String) {
        val directory = File("build/tmp").apply { mkdirs() }
        val tempFile = File.createTempFile("issue_comment_", ".tmp", directory)
        try {
            tempFile.writeText(body)
            executeCmd("gh", "issue", "comment", issueNumber, "--body-file", tempFile.absolutePath)
        } finally {
            tempFile.delete()
        }
    }

    override fun getPrDiff(prNumber: String): String {
        return executeCmd("gh", "pr", "diff", prNumber)
    }

    override fun getFailedBuildLogs(prNumber: String): String = GitHubCli.getFailedBuildLogs(prNumber)

    override fun getPrUrl(prNumber: String): String {
        return executeCmd("gh", "pr", "view", prNumber, "--json", "url")
            .substringAfter("\"url\":\"").substringBefore("\"")
    }

    override fun getJulesSession(issueId: String): JulesSession? = JulesCli.getActiveSession(issueId)

    override fun hasUnableToCompleteActivity(sessionId: String): Boolean = JulesCli.hasUnableToCompleteActivity(sessionId)

    override fun sendJulesSessionMessage(sessionId: String, prompt: String) {
        JulesCli.sendSessionMessage(sessionId, prompt)
    }

    override fun parseAllIssues(): List<BacklogIssue> = BacklogParser.parseAllIssues(backlogDir)

    override fun writeGithubIssue(issue: BacklogIssue, number: Int) = BacklogParser.writeGithubIssue(issue, number)

    override fun removeGithubIssue(issue: BacklogIssue) = BacklogParser.removeGithubIssue(issue)

    override fun markIssueAsResolved(issue: BacklogIssue) = BacklogParser.markIssueAsResolved(issue, resolvedDir)

    override fun deleteStateFile() {
        stateFile.delete()
    }

    override fun generateKnowledgeMap() {
        executeCmd("./gradlew", "generateKnowledgeMap")
    }

    private fun executeCmd(vararg command: String): String {
        return RetryUtils.retry(config.maxRetries, config.initialRetryDelayMs, this) {
            val pb = ProcessBuilder(*command)
            val process = pb.redirectErrorStream(true).start()
            process.outputStream.close()

            val output = java.lang.StringBuilder()
            val reader = process.inputStream.bufferedReader()
            var line: String? = reader.readLine()
            while (line != null) {
                println("  [exec] $line")
                output.append(line).append("\n")
                line = reader.readLine()
            }

            val completed = process.waitFor(config.maxExternalCommandTimeoutMinutes, TimeUnit.MINUTES)
            if (!completed) {
                process.destroyForcibly()
                throw RuntimeException("Command '${command.joinToString(" ")}' timed out after ${config.maxExternalCommandTimeoutMinutes} minutes.")
            }
            output.toString().trim()
        }
    }
}
