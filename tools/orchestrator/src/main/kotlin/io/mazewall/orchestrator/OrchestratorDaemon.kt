package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit

class OrchestratorDaemonRunner(
    private val env: OrchestratorEnvironment,
    private val stateFile: File
) {
    val context = OrchestratorContext()

    fun loadState() {
        if (stateFile.exists()) {
            val props = java.util.Properties()
            stateFile.inputStream().use { props.load(it) }
            context.load(props)
            env.println("♻️ State machine context loaded from ${stateFile.name}")
        }
    }

    fun saveState() {
        val props = java.util.Properties()
        context.save(props)
        stateFile.outputStream().use { props.store(it, "Orchestrator state") }
    }

    fun run() {
        loadState()
        val forcedTaskId = env.getEnvOrNull("FORCE_TASK")?.takeIf { it.isNotEmpty() }
        if (forcedTaskId != null && context.activeSlots.none { it.currentIssueId == forcedTaskId }) {
            env.println("🎯 FORCE_TASK=$forcedTaskId detected. Resetting active slots.")
            context.clearActiveTask()
            saveState()
        }
        while (true) {
            try {
                // 0. Poll Telegram updates non-blockingly for incoming review requests
                env.pollTelegramUpdates(context)

                // 1. Try to select and start new tasks
                selectAndStartTasks()

                // 2. If we have active slots, execute their state machine
                if (context.activeSlots.isNotEmpty()) {
                    // Process active running tasks (AWAITING_PR, AWAITING_JULES_START) before PENDING_APPROVAL tasks
                    val slotsToProcess = context.activeSlots.sortedWith(
                        compareBy<SlotContext> {
                            when (it.state) {
                                is AwaitingPrState -> 1
                                is AwaitingJulesStartState -> 2
                                is PendingApprovalState -> 3
                                else -> 4
                            }
                        }
                    )
                    for (slot in slotsToProcess) {
                        try {
                            val nextState = slot.state.execute(env, context, slot)
                            if (nextState != slot.state) {
                                env.println("Slot [${slot.currentIssueId}]: Transitioned from ${slot.state.name} to ${nextState.name}")
                                nextState.updateSlot(slot)
                                saveState()
                            }
                        } catch (e: Exception) {
                            env.errPrintln("⚠️ Error in state ${slot.state.name} for slot ${slot.currentIssueId}: ${e.message}")
                            e.printStackTrace()
                            try {
                                env.sendNotification("⚠️ *Daemon Error in State ${slot.state.name} for slot ${slot.currentIssueId}:* `${e.message}`. Retrying in ${env.config.daemonErrorRetryMinutes} minutes...")
                            } catch (notificationEx: Exception) {
                                env.errPrintln("⚠️ Failed to send error notification: ${notificationEx.message}")
                            }
                        }
                    }
                } else {
                    // No active tasks and no new tasks found, sleep before checking again
                    env.println("💤 No active tasks. Checking again in ${env.config.backlogCheckIntervalMinutes} minutes...")
                    env.sleep(env.config.backlogCheckIntervalMinutes, TimeUnit.MINUTES)
                }

                // Always save state after processing
                saveState()

                // To prevent high-CPU spin, sleep for a short polling interval (e.g. 5 seconds) if there are active tasks
                if (context.activeSlots.isNotEmpty()) {
                    env.sleep(5, TimeUnit.SECONDS)
                }
            } catch (e: Exception) {
                env.errPrintln("⚠️ Error in orchestrator loop: ${e.message}")
                e.printStackTrace()
                env.sleep(env.config.daemonErrorRetryMinutes, TimeUnit.MINUTES)
            }
        }
    }

    fun selectAndStartTasks() {
        val allIssues = env.parseAllIssues()

        // Auto-resume orphaned in-progress tasks
        for (issue in allIssues) {
            if (issue.status == "in_progress" && context.activeSlots.none { it.currentIssueId == issue.id }) {
                env.println("♻️ Found orphaned in-progress task in backlog: ${issue.id}. Auto-resuming...")
                val slot = SlotContext(issue.id)
                slot.currentIssueTitle = issue.title
                slot.currentIssueFile = issue.file.path
                slot.githubIssueNumber = issue.githubIssue?.toString()
                slot.state = PendingApprovalState(issue.id, issue.title, issue.file.path, issue.githubIssue?.toString())
                context.activeSlots.add(slot)
                saveState()
            }
        }

        val forcedTaskId = env.getEnvOrNull("FORCE_TASK")?.takeIf { it.isNotEmpty() }

        if (forcedTaskId != null) {
            // If forced task is specified, check if it's already active or resolved
            val alreadyActive = context.activeSlots.any { it.currentIssueId.equals(forcedTaskId, ignoreCase = true) }
            if (!alreadyActive) {
                val forcedIssue = allIssues.firstOrNull { it.id.equals(forcedTaskId, ignoreCase = true) }
                if (forcedIssue != null && forcedIssue.status == "open") {
                    env.println("🎯 Forcing specific task: ${forcedIssue.id} - ${forcedIssue.title}")
                    val slot = SlotContext(forcedIssue.id)
                    slot.currentIssueTitle = forcedIssue.title
                    slot.currentIssueFile = forcedIssue.file.path
                    slot.githubIssueNumber = forcedIssue.githubIssue?.toString()
                    slot.state = PendingApprovalState(forcedIssue.id, forcedIssue.title, forcedIssue.file.path, forcedIssue.githubIssue?.toString())
                    context.activeSlots.add(slot)
                    saveState()
                }
            }
            return
        }

        // Standard task selection with conflict check
        while (true) {
            val activeIssues = allIssues.filter { issue -> context.activeSlots.any { it.currentIssueId == issue.id } }
            val activeFiles = activeIssues.flatMap { it.targetFiles }.toSet()
            val activeModules = activeIssues.flatMap { it.targetModules }.toSet()

            // Filter out issues that are already active or skipped
            val candidateIssues = allIssues.filter {
                it.id !in context.skippedIds &&
                context.activeSlots.none { slot -> slot.currentIssueId == it.id }
            }

            // Find an unblocked candidate using the DependencyGraph selection logic
            val openIssues = candidateIssues.filter { it.status == "open" }
            val openIds = allIssues.filter { it.status == "open" }.map { it.id }.toSet()

            val unblockedIssues = openIssues.filter { issue ->
                issue.dependencies.none { dep -> openIds.contains(dep) }
            }

            // Filter for conflict-free: non-interfering empty-target tasks can run concurrently with other tasks.
            // If a task has empty targets and is NOT non-interfering, it is treated conservatively as a global lock/conflict.
            val conflictFreeIssues = unblockedIssues.filter { issue ->
                if (activeIssues.isEmpty()) {
                    true
                } else {
                    val issueIsEmptyAndInterfering = (issue.targetFiles.isEmpty() || issue.targetModules.isEmpty()) && !issue.isNonInterfering()
                    if (issueIsEmptyAndInterfering) {
                        false
                    } else {
                        activeIssues.none { active ->
                            val activeIsEmptyAndInterfering = (active.targetFiles.isEmpty() || active.targetModules.isEmpty()) && !active.isNonInterfering()
                            activeIsEmptyAndInterfering ||
                            issue.targetFiles.any { it in active.targetFiles } ||
                            issue.targetModules.any { it in active.targetModules }
                        }
                    }
                }
            }

            // Sort by priority descending, then ID descending
            val nextIssue = conflictFreeIssues.sortedWith(
                compareByDescending<BacklogIssue> { it.priority }
                    .thenByDescending { it.id }
            ).firstOrNull()

            if (nextIssue == null) {
                // If there are no conflict-free unblocked tasks available but we have skipped tasks,
                // and there are NO active slots running, we can clear skipped tasks and retry.
                if (context.activeSlots.isEmpty() && context.skippedIds.isNotEmpty()) {
                    env.println("♻️ No unblocked tasks available. Clearing skipped tasks list to retry them.")
                    context.skippedIds.clear()
                    continue // retry selection
                }
                break
            }

            env.println("\n🎯 Next prioritized task selected: ${nextIssue.id} - ${nextIssue.title} (Priority: ${nextIssue.priority})")
            val slot = SlotContext(nextIssue.id)
            slot.currentIssueTitle = nextIssue.title
            slot.currentIssueFile = nextIssue.file.path
            slot.githubIssueNumber = nextIssue.githubIssue?.toString()
            slot.state = PendingApprovalState(nextIssue.id, nextIssue.title, nextIssue.file.path, nextIssue.githubIssue?.toString())
            context.activeSlots.add(slot)
            saveState()
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
        githubCacheTtlMs = getEnvOr("GITHUB_CACHE_TTL_MS", "10000").toLong(),
        stuckPendingThresholdMs = getEnvOr("STUCK_PENDING_THRESHOLD_MS", "900000").toLong()
    )

    val gitHubClient = RealGitHubClient(config)
    val julesClient = RealJulesClient(config)

    val env = RealOrchestratorEnvironment(bot, backlogDir, resolvedDir, stateFile, gitHubClient, julesClient, config)
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
