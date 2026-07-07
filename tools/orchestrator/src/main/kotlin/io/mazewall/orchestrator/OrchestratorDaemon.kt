package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit

class OrchestratorContext {
    var state: OrchestratorState = OrchestratorState.SELECT_TASK
    var currentIssueId: String? = null
    var currentIssueTitle: String? = null
    var currentIssueFile: String? = null
    var githubIssueNumber: String? = null
    var julesSessionId: String? = null
    var prNumber: String? = null
    val skippedIds: MutableSet<String> = mutableSetOf()

    // Monitoring state/cache variables
    var lastHeadSha: String? = null
    var lastReviewedSha: String? = null
    var lastBuildStatus: String? = null
    var lastCheckedSha: String? = null
    var lastWaitingLogTime: Long = 0L
    var lastFailedSha: String? = null
    var startTime: Long = 0L

    fun load(props: java.util.Properties) {
        state = OrchestratorState.fromName(props.getProperty("state"))
        currentIssueId = props.getProperty("currentIssueId").takeIf { !it.isNullOrEmpty() }
        currentIssueTitle = props.getProperty("currentIssueTitle").takeIf { !it.isNullOrEmpty() }
        currentIssueFile = props.getProperty("currentIssueFile").takeIf { !it.isNullOrEmpty() }
        githubIssueNumber = props.getProperty("githubIssueNumber").takeIf { !it.isNullOrEmpty() }
        julesSessionId = props.getProperty("julesSessionId").takeIf { !it.isNullOrEmpty() }
        prNumber = props.getProperty("prNumber").takeIf { !it.isNullOrEmpty() }

        skippedIds.clear()
        props.getProperty("skippedIds")?.let { ids ->
            if (ids.isNotEmpty()) {
                skippedIds.addAll(ids.split(","))
            }
        }

        lastHeadSha = props.getProperty("lastHeadSha").takeIf { !it.isNullOrEmpty() }
        lastReviewedSha = props.getProperty("lastReviewedSha").takeIf { !it.isNullOrEmpty() }
        lastBuildStatus = props.getProperty("lastBuildStatus").takeIf { !it.isNullOrEmpty() }
        lastCheckedSha = props.getProperty("lastCheckedSha").takeIf { !it.isNullOrEmpty() }
        lastWaitingLogTime = props.getProperty("lastWaitingLogTime")?.toLongOrNull() ?: 0L
        lastFailedSha = props.getProperty("lastFailedSha").takeIf { !it.isNullOrEmpty() }
        startTime = props.getProperty("startTime")?.toLongOrNull() ?: 0L
    }

    fun save(props: java.util.Properties) {
        props.setProperty("state", state.name)
        props.setProperty("currentIssueId", currentIssueId ?: "")
        props.setProperty("currentIssueTitle", currentIssueTitle ?: "")
        props.setProperty("currentIssueFile", currentIssueFile ?: "")
        props.setProperty("githubIssueNumber", githubIssueNumber ?: "")
        props.setProperty("julesSessionId", julesSessionId ?: "")
        props.setProperty("prNumber", prNumber ?: "")
        props.setProperty("skippedIds", skippedIds.joinToString(","))

        props.setProperty("lastHeadSha", lastHeadSha ?: "")
        props.setProperty("lastReviewedSha", lastReviewedSha ?: "")
        props.setProperty("lastBuildStatus", lastBuildStatus ?: "")
        props.setProperty("lastCheckedSha", lastCheckedSha ?: "")
        props.setProperty("lastWaitingLogTime", lastWaitingLogTime.toString())
        props.setProperty("lastFailedSha", lastFailedSha ?: "")
        props.setProperty("startTime", startTime.toString())
    }

    fun clearActiveTask() {
        currentIssueId = null
        currentIssueTitle = null
        currentIssueFile = null
        githubIssueNumber = null
        julesSessionId = null
        prNumber = null
        lastHeadSha = null
        lastReviewedSha = null
        lastBuildStatus = null
        lastCheckedSha = null
        lastWaitingLogTime = 0L
        lastFailedSha = null
        startTime = 0L
    }
}

class OrchestratorDaemonRunner(
    private val env: OrchestratorEnvironment,
    private val stateFile: File
) {
    private val context = OrchestratorContext()

    fun loadState() {
        if (stateFile.exists()) {
            val props = java.util.Properties()
            stateFile.inputStream().use { props.load(it) }
            context.load(props)
            env.println("♻️ State machine context loaded from ${stateFile.name} (State: ${context.state.name})")
        }
    }

    fun saveState() {
        val props = java.util.Properties()
        context.save(props)
        stateFile.outputStream().use { props.store(it, "Orchestrator state") }
    }

    fun run() {
        loadState()
        while (true) {
            try {
                context.state = context.state.execute(env, context)
                saveState()
            } catch (e: Exception) {
                env.errPrintln("⚠️ Error in state ${context.state.name}: ${e.message}")
                e.printStackTrace()
                try {
                    env.sendNotification("⚠️ *Daemon Error in State ${context.state.name}:* `${e.message}`. Retrying in ${env.config.daemonErrorRetryMinutes} minutes...")
                } catch (notificationEx: Exception) {
                    env.errPrintln("⚠️ Failed to send error notification: ${notificationEx.message}")
                }
                env.sleep(env.config.daemonErrorRetryMinutes, TimeUnit.MINUTES)
            }
        }
    }
}

fun main() {
    println("🤖 Starting Autonomous Backlog Orchestrator Daemon...")
    loadDotEnv()

    val telegramToken = getEnvOrNull("TELEGRAM_BOT_TOKEN")
    val telegramChatId = getEnvOrNull("TELEGRAM_CHAT_ID")
    val julesRepo = getEnvOr("JULES_REPO", "Pilleo/mazewall")
    val backlogPath = getEnvOr("BACKLOG_PATH", "docs/internals/backlog")

    val bot = if (!telegramToken.isNullOrBlank() && !telegramChatId.isNullOrBlank()) {
        TelegramBot(telegramToken, telegramChatId)
    } else {
        println("⚠️ TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID not set. Running in local terminal-only mode.")
        null
    }

    val backlogDir = File(backlogPath)
    val resolvedDir = File(backlogDir, "resolved")
    val stateFile = File(".orchestrator_state.properties")

    println("🤖 *Orchestrator Daemon Online* in repo `$julesRepo`.")

    val config = OrchestratorConfig(
        pollingIntervalSeconds = getEnvOr("POLLING_INTERVAL_SECONDS", "30").toLong(),
        backlogCheckIntervalMinutes = getEnvOr("BACKLOG_CHECK_INTERVAL_MINUTES", "2").toLong(),
        julesTriggerAttempts = getEnvOr("JULES_TRIGGER_ATTEMPTS", "12").toInt(),
        julesTriggerIntervalSeconds = getEnvOr("JULES_TRIGGER_INTERVAL_SECONDS", "15").toLong(),
        ciFailureRetryMinutes = getEnvOr("CI_FAILURE_RETRY_MINUTES", "5").toLong(),
        daemonErrorRetryMinutes = getEnvOr("DAEMON_ERROR_RETRY_MINUTES", "2").toLong(),
        maxExternalCommandTimeoutMinutes = getEnvOr("MAX_EXTERNAL_COMMAND_TIMEOUT_MINUTES", "10").toLong(),
        taskTimeoutThresholdMinutes = getEnvOr("TASK_TIMEOUT_THRESHOLD_MINUTES", "60").toLong(),
        maxRetries = getEnvOr("MAX_RETRIES", "3").toInt(),
        initialRetryDelayMs = getEnvOr("INITIAL_RETRY_DELAY_MS", "1000").toLong(),
        githubCacheTtlMs = getEnvOr("GITHUB_CACHE_TTL_MS", "10000").toLong()
    )

    GitHubCli.init(config)
    JulesCli.init(config)

    val env = RealOrchestratorEnvironment(bot, backlogDir, resolvedDir, stateFile, config)
    val runner = OrchestratorDaemonRunner(env, stateFile)
    runner.run()
}

private fun loadDotEnv() {
    val file = File(".ENV")
    if (file.exists()) {
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                val key = trimmed.substringBefore("=").trim()
                val value = trimmed.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
                System.setProperty(key, value)
            }
        }
    }
}

private fun getEnvOrNull(key: String): String? {
    return System.getenv(key) ?: System.getProperty(key)
}

private fun getEnv(key: String): String {
    return System.getenv(key) ?: System.getProperty(key) ?: throw IllegalStateException("Environment variable $key is not set.")
}

private fun getEnvOr(key: String, default: String): String {
    return System.getenv(key) ?: System.getProperty(key) ?: default
}
