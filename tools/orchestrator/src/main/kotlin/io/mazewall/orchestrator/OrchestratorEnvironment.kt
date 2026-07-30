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
    val gitHubClient: GitHubClient
    val julesClient: JulesClient

    // Bot
    fun sendNotification(message: String)
    fun requestApproval(issueId: String, text: String): Boolean
    fun sendApprovalRequest(issueId: String, text: String)
    fun checkApprovalNonBlocking(issueId: String): Boolean?
    fun pollTelegramUpdates(context: OrchestratorContext)



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
    override val gitHubClient: GitHubClient,
    override val julesClient: JulesClient,
    override val config: OrchestratorConfig = OrchestratorConfig()
) : OrchestratorEnvironment {

    override fun println(message: Any?) {
        val formatted = "[${java.time.LocalDateTime.now()}] $message"
        kotlin.io.println(formatted)
        System.out.flush()
    }

    override fun print(message: Any?) {
        kotlin.io.print(message)
        System.out.flush()
    }

    override fun errPrintln(message: Any?) {
        val formatted = "[${java.time.LocalDateTime.now()}] [ERROR] $message"
        System.err.println(formatted)
        System.err.flush()
    }

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

    init {
        bot?.onReviewRequested = { focusComments ->
            ReviewIssueLauncher.launchReviewTask(focusComments, backlogDir, this, OrchestratorContext())
        }
    }

    override fun sendNotification(message: String) {
        bot?.sendMessage(message)
    }

    override fun pollTelegramUpdates(context: OrchestratorContext) {
        bot?.onReviewRequested = { focusComments ->
            ReviewIssueLauncher.launchReviewTask(focusComments, backlogDir, this, context)
        }
        bot?.pollUpdates()
    }

    override fun sendApprovalRequest(issueId: String, text: String) {
        if (bot != null) {
            bot.onReviewRequested = { focusComments ->
                ReviewIssueLauncher.launchReviewTask(focusComments, backlogDir, this, OrchestratorContext())
            }
            bot.sendMessageWithApprovalMarkup(issueId, text)
        } else {
            print("\u001B[1;31m🔔 [APPROVAL REQUIRED] $text (y/n): \u001B[0m")
        }
    }

    override fun checkApprovalNonBlocking(issueId: String): Boolean? {
        if (bot != null) {
            return bot.checkApprovalNonBlocking(issueId)
        }
        // Terminal fallback logic
        val input = readLine()?.lowercase()?.trim() ?: return null
        return input == "y" || input == "yes"
    }

    override fun requestApproval(issueId: String, text: String): Boolean {
        return if (bot != null) {
            bot.onReviewRequested = { focusComments ->
                ReviewIssueLauncher.launchReviewTask(focusComments, backlogDir, this, OrchestratorContext())
            }
            bot.sendMessageWithApprovalMarkup(issueId, text)
            bot.waitForApproval(issueId)
        } else {
            print("\u001B[1;31m🔔 [APPROVAL REQUIRED] $text (y/n): \u001B[0m")
            System.out.flush()
            val input = readLine()?.trim()?.lowercase()
            input == "y" || input == "yes"
        }
    }



















    override fun parseAllIssues(): List<BacklogIssue> = BacklogParser.parseAllIssues(backlogDir)

    override fun writeGithubIssue(issue: BacklogIssue, number: Int) = BacklogParser.writeGithubIssue(issue, number)

    override fun removeGithubIssue(issue: BacklogIssue) = BacklogParser.removeGithubIssue(issue)

    override fun markIssueAsResolved(issue: BacklogIssue) = BacklogParser.markIssueAsResolved(issue, resolvedDir)

    override fun deleteStateFile() {
        stateFile.delete()
    }

    override fun generateKnowledgeMap() {
        // No-op: Knowledge sub-maps (enforcer_map.md, profiler_map.md) have been removed.
    }

    private fun executeCmd(vararg command: String): String {
        return RetryUtils.retry(config.maxRetries, config.initialRetryDelayMs, { errPrintln(it) }) {
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
