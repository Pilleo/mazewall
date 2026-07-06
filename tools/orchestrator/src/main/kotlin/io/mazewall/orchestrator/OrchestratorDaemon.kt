package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit

enum class OrchestratorState {
    SELECT_TASK,
    AWAIT_START_APPROVAL,
    AWAIT_JULES_START,
    AWAIT_PR_CREATION,
    MONITOR_PR,
    RESOLVE_TASK
}

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

    fun load(props: java.util.Properties) {
        state = props.getProperty("state")?.let { OrchestratorState.valueOf(it) } ?: OrchestratorState.SELECT_TASK
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
    }
}

class OrchestratorDaemonRunner(
    private val bot: TelegramBot?,
    private val backlogDir: File,
    private val resolvedDir: File,
    private val stateFile: File
) {
    private val context = OrchestratorContext()

    fun loadState() {
        if (stateFile.exists()) {
            val props = java.util.Properties()
            stateFile.inputStream().use { props.load(it) }
            context.load(props)
            println("♻️ State machine context loaded from ${stateFile.name} (State: ${context.state})")
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
                when (context.state) {
                    OrchestratorState.SELECT_TASK -> handleSelectTask()
                    OrchestratorState.AWAIT_START_APPROVAL -> handleAwaitStartApproval()
                    OrchestratorState.AWAIT_JULES_START -> handleAwaitJulesStart()
                    OrchestratorState.AWAIT_PR_CREATION -> handleAwaitPrCreation()
                    OrchestratorState.MONITOR_PR -> handleMonitorPr()
                    OrchestratorState.RESOLVE_TASK -> handleResolveTask()
                }
                saveState()
            } catch (e: Exception) {
                System.err.println("⚠️ Error in state ${context.state}: ${e.message}")
                e.printStackTrace()
                try {
                    bot?.sendMessage("⚠️ *Daemon Error in State ${context.state}:* `${e.message}`. Retrying in 2 minutes...")
                } catch (_: Exception) {}
                TimeUnit.MINUTES.sleep(2)
            }
        }
    }

    private fun handleSelectTask() {
        val allIssues = BacklogParser.parseAllIssues(backlogDir)
        println("📚 Backlog Status: ${allIssues.count { it.status == "open" }} open issues remaining.")
        val activeIssues = allIssues.filter { it.id !in context.skippedIds }
        val nextIssue = DependencyGraph.selectNextIssue(activeIssues)

        if (nextIssue == null) {
            println("💤 No unblocked open issues found. Checking again in 2 minutes...")
            TimeUnit.MINUTES.sleep(2)
            return
        }

        println("\n🎯 Next prioritized task: ${nextIssue.id} - ${nextIssue.title} (Priority: ${nextIssue.priority})")
        context.currentIssueId = nextIssue.id
        context.currentIssueTitle = nextIssue.title
        context.currentIssueFile = nextIssue.file.path
        context.githubIssueNumber = nextIssue.githubIssue?.toString()
        context.julesSessionId = null
        context.prNumber = null
        context.state = OrchestratorState.AWAIT_START_APPROVAL
    }

    private fun handleAwaitStartApproval() {
        val issueId = context.currentIssueId ?: throw IllegalStateException("currentIssueId is null")
        val issueTitle = context.currentIssueTitle ?: throw IllegalStateException("currentIssueTitle is null")
        val githubIssueNumber = context.githubIssueNumber

        val approved = if (githubIssueNumber != null) {
            println("🔄 Resuming already-in-progress task $issueId (linked to GitHub issue #$githubIssueNumber)...")
            true
        } else {
            ringTerminalBell(3)
            if (bot != null) {
                val telegramText = """
                    🤖 *Request to start task $issueId*
                    *Title:* $issueTitle

                    Please approve or skip in the inline keyboard below.
                """.trimIndent()
                bot.sendMessageWithApprovalMarkup(issueId, telegramText)
                bot.waitForApproval(issueId)
            } else {
                print("\u001B[1;31m🔔 [APPROVAL REQUIRED] Start task $issueId - $issueTitle? (y/n): \u001B[0m")
                System.out.flush()
                val input = readlnOrNull()?.trim()?.lowercase()
                input == "y" || input == "yes"
            }
        }

        if (!approved) {
            println("⏭️ Task $issueId skipped by user. Postponing.")
            context.skippedIds.add(issueId)
            context.state = OrchestratorState.SELECT_TASK
            return
        }

        println("🚀 Starting task `$issueId`...")

        // Retrieve or create GitHub issue
        var newGithubIssueNumber = context.githubIssueNumber
        if (newGithubIssueNumber == null) {
            val existingIssueNumber = GitHubCli.findExistingIssueNumber(issueId)
            if (existingIssueNumber != null) {
                println("♻️ Recovered existing GitHub issue #$existingIssueNumber for $issueId (was missing from backlog file).")
                newGithubIssueNumber = existingIssueNumber
            } else {
                println("Creating GitHub issue for $issueId...")
                val issueTitleForGit = "[$issueId] $issueTitle"
                newGithubIssueNumber = GitHubCli.createIssue(issueTitleForGit, File(context.currentIssueFile!!), "jules")
                println("Created GitHub issue #$newGithubIssueNumber")
            }
            // Write it to issue file
            val nextIssue = BacklogParser.parseAllIssues(backlogDir).firstOrNull { it.id == issueId }
            if (nextIssue != null) {
                BacklogParser.writeGithubIssue(nextIssue, newGithubIssueNumber.toInt())
            }
            context.githubIssueNumber = newGithubIssueNumber
        }

        context.state = OrchestratorState.AWAIT_JULES_START
    }

    private fun handleAwaitJulesStart() {
        val issueId = context.currentIssueId ?: throw IllegalStateException("currentIssueId is null")
        var activeSession = JulesCli.getActiveSession(issueId)
        var attempts = 0
        while (activeSession == null && attempts < 12) {
            println("Waiting for Jules session to be automatically triggered via GitHub issue label (attempt ${attempts + 1}/12)...")
            TimeUnit.SECONDS.sleep(15)
            activeSession = JulesCli.getActiveSession(issueId)
            attempts++
        }

        if (activeSession != null) {
            println("Linked Jules session: ID=${activeSession.id}, Status=${activeSession.status}")
            context.julesSessionId = activeSession.id
            context.state = OrchestratorState.AWAIT_PR_CREATION
        } else {
            println("⚠️ Jules session did not trigger. Retrying in 1 minute...")
            TimeUnit.MINUTES.sleep(1)
        }
    }

    private fun handleAwaitPrCreation() {
        val issueId = context.currentIssueId ?: throw IllegalStateException("currentIssueId is null")
        val githubIssueNumber = context.githubIssueNumber ?: throw IllegalStateException("githubIssueNumber is null")
        val sessionId = context.julesSessionId

        if (GitHubCli.isIssueClosed(githubIssueNumber)) {
            println("\n\u001B[1;33m⚠️ GitHub issue #$githubIssueNumber was closed. Canceling task $issueId.\u001B[0m")
            val nextIssue = BacklogParser.parseAllIssues(backlogDir).firstOrNull { it.id == issueId }
            if (nextIssue != null) {
                BacklogParser.removeGithubIssue(nextIssue)
            }
            context.skippedIds.add(issueId)
            clearActiveTask()
            context.state = OrchestratorState.SELECT_TASK
            return
        }

        val session = JulesCli.getActiveSession(issueId)
        if (session != null && session.status != context.lastBuildStatus) {
            println("Jules session status changed: ${session.status}")
            context.lastBuildStatus = session.status

            val sessionUrl = "https://jules.google.com/session/${session.id}"
            if (session.status.contains("Awaiting", ignoreCase = true) || session.status.contains("Feedback", ignoreCase = true)) {
                val alertMsg = "⚠️ *Jules needs feedback on task $issueId!* Status: `${session.status}`. Please check and respond here: $sessionUrl"
                bot?.sendMessage(alertMsg)
                println("\n\u001B[1;31m🔔 [FEEDBACK REQUIRED] Jules is blocked waiting for feedback on task $issueId. Status: ${session.status}\u001B[0m")
                println("👉 Respond here: $sessionUrl")
                ringTerminalBell(5)
            } else if (session.status.equals("Completed", ignoreCase = true)) {
                println("\n\u001B[1;32m🟢 [COMPLETED] Jules task $issueId is Completed! Please review and publish the PR in the UI.\u001B[0m")
                println("👉 Publish PR here: $sessionUrl")
            }
        }

        val prNumber = GitHubCli.findLinkedPR(githubIssueNumber, issueId, sessionId)
        if (prNumber != null) {
            println("Jules opened PR #$prNumber")
            context.prNumber = prNumber
            context.lastBuildStatus = null
            context.state = OrchestratorState.MONITOR_PR
        } else {
            println("Waiting for Jules to open a PR for issue #$githubIssueNumber...")
            TimeUnit.SECONDS.sleep(30)
        }
    }

    private fun handleMonitorPr() {
        val prNumber = context.prNumber ?: throw IllegalStateException("prNumber is null")
        val githubIssueNumber = context.githubIssueNumber ?: throw IllegalStateException("githubIssueNumber is null")
        val issueId = context.currentIssueId ?: throw IllegalStateException("currentIssueId is null")

        if (GitHubCli.isPrMerged(prNumber)) {
            println("🎉 PR #$prNumber merged! resolving issue locally...")
            context.state = OrchestratorState.RESOLVE_TASK
            return
        }

        val currentSha = GitHubCli.getPrHeadSha(prNumber)
        if (currentSha != context.lastHeadSha) {
            println("🔄 New commits detected on PR #$prNumber (Head SHA: $currentSha). Checking build status...")
            context.lastHeadSha = currentSha
        }

        val status = GitHubCli.checkBuildStatus(prNumber)
        if (status != context.lastBuildStatus || currentSha != context.lastCheckedSha) {
            println("PR #$prNumber build check: $status")
            context.lastBuildStatus = status
            context.lastCheckedSha = currentSha
        }

        when (status) {
            "SUCCESS" -> {
                if (currentSha != context.lastReviewedSha) {
                    val comments = GitHubCli.getPrComments(prNumber)
                    val shaPrefix = currentSha.take(7)

                    val alreadyReviewedByAgy = comments.any {
                        it.body.contains("Approved by Antigravity") ||
                        it.body.contains("Rejected by Antigravity")
                    }

                    if (alreadyReviewedByAgy) {
                        println("✨ PR #$prNumber already has an Antigravity review. Skipping.")
                        context.lastReviewedSha = currentSha
                    } else {
                        val requestComment = comments.firstOrNull {
                            (it.body.contains("@jules")) &&
                            it.body.contains(shaPrefix)
                        }

                        if (requestComment == null) {
                            println("🤖 PR #$prNumber Build Passed. Requesting Jules review for SHA: $currentSha")

                            val prompt = """
                                @jules Please perform a critical code review on this Pull Request (SHA: $currentSha) as a senior JVM security expert and staff engineer for the `mazewall` project.

                                Provide a detailed response covering:

                                1. **Overview**: Describe what this PR is doing and its main objectives.
                                2. **Rationale**: Why was the solution implemented in this specific way?
                                3. **Comparison & Alternatives**: Why is this solution better than other designs? What alternatives were considered (or should be considered) and what are their trade-offs?
                                4. **Critical & Security Analysis**:
                                   - Evaluate correctness and potential failure modes.
                                   - Check for JVM sandboxing bypasses, Landlock or Seccomp filter flaws.
                                   - Verify FFM (Foreign Function & Memory) alignment, lifecycle, and memory leak risks.
                                   - Analyze concurrency, JVM thread coordination, and Loom virtual thread carrier thread safety (preventing carrier poisoning).
                                5. **Conclusion**: Provide your final recommendation (e.g. Approved or needs changes).

                                Please be concise, extremely precise, and thorough. If you are not sure -say so. Use formatting for readability. Just leave a comment in the PR, do not commit new files!
                            """.trimIndent()

                            executeCmd("gh", "pr", "comment", prNumber, "--body", prompt)
                        } else {
                            val requestTime = java.time.Instant.parse(requestComment.createdAt)
                            val julesReply = comments.firstOrNull { comment ->
                                val author = comment.author?.login ?: ""
                                (author.contains("jules", ignoreCase = true)) &&
                                java.time.Instant.parse(comment.createdAt).isAfter(requestTime)
                            }

                            if (julesReply != null) {
                                println("🟢 Jules review received for SHA $currentSha.")
                                val prUrl = executeCmd("gh", "pr", "view", prNumber, "--json", "url").substringAfter("\"url\":\"").substringBefore("\"")
                                bot?.sendMessage("🟢 *Jules reviewed PR #$prNumber!* Ready for final manual review and merge: $prUrl")
                                context.lastReviewedSha = currentSha
                                ringTerminalBell(3)
                            } else {
                                println("⌛ Waiting for Jules (@jules) to complete review on PR #$prNumber (SHA: $shaPrefix)...")
                            }
                        }
                    }
                }

                val now = System.currentTimeMillis()
                if (now - context.lastWaitingLogTime > 60_000) {
                    val prUrl = executeCmd("gh", "pr", "view", prNumber, "--json", "url").substringAfter("\"url\":\"").substringBefore("\"")
                    println("⌛ Waiting for manual merge of PR #$prNumber at: $prUrl")
                    context.lastWaitingLogTime = now
                }
                TimeUnit.SECONDS.sleep(30)
            }
            "FAILURE" -> {
                val currentSha = GitHubCli.getPrHeadSha(prNumber)
                if (currentSha != context.lastFailedSha) {
                    println("❌ Build failed on PR #$prNumber. Fetching logs...")
                    val failedLogs = GitHubCli.getFailedBuildLogs(prNumber)
                    val feedback = """
                        ❌ **CI Build Failed.**
                        Jules, please review the failing logs and fix the implementation:

                        ```
                        $failedLogs
                        ```
                    """.trimIndent()

                    executeCmd("gh", "pr", "comment", prNumber, "--body", feedback)
                    bot?.sendMessage("❌ Build failed on PR #$prNumber. Feedback sent to Jules.")
                    context.lastFailedSha = currentSha
                } else {
                    println("❌ Build is still failing on SHA $currentSha. Waiting for a new commit...")
                }
                TimeUnit.MINUTES.sleep(5)
            }
            "CONFLICT" -> {
                val now = System.currentTimeMillis()
                if (now - context.lastWaitingLogTime > 60_000) {
                    val prUrl = executeCmd("gh", "pr", "view", prNumber, "--json", "url").substringAfter("\"url\":\"").substringBefore("\"")
                    bot?.sendMessage("⚠️ *PR #$prNumber has conflicts!* Please resolve them: $prUrl")
                    println("\u001B[1;31m🔔 [CONFLICT] PR #$prNumber has conflicts! Please resolve conflicts: $prUrl\u001B[0m")
                    ringTerminalBell(3)
                    context.lastWaitingLogTime = now
                }
                TimeUnit.SECONDS.sleep(30)
            }
            else -> {
                TimeUnit.SECONDS.sleep(30)
            }
        }
    }

    private fun handleResolveTask() {
        val issueId = context.currentIssueId ?: throw IllegalStateException("currentIssueId is null")
        val nextIssue = BacklogParser.parseAllIssues(backlogDir).firstOrNull { it.id == issueId } ?: throw IllegalStateException("nextIssue not found in backlog")

        BacklogParser.markIssueAsResolved(nextIssue, resolvedDir)

        println("Regenerating architectural maps...")
        executeCmd("./gradlew", "generateKnowledgeMap")
        println("✅ Resolved issue `$issueId`. Picking next task...")

        clearActiveTask()
        stateFile.delete()
        context.state = OrchestratorState.SELECT_TASK
    }

    private fun clearActiveTask() {
        context.currentIssueId = null
        context.currentIssueTitle = null
        context.currentIssueFile = null
        context.githubIssueNumber = null
        context.julesSessionId = null
        context.prNumber = null
        context.lastHeadSha = null
        context.lastReviewedSha = null
        context.lastBuildStatus = null
        context.lastCheckedSha = null
        context.lastWaitingLogTime = 0L
        context.lastFailedSha = null
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

    val runner = OrchestratorDaemonRunner(bot, backlogDir, resolvedDir, stateFile)
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

private fun ringTerminalBell(times: Int) {
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
                print("\u0007")
                System.out.flush()
            }
            Thread.sleep(300)
        }
    } catch (_: Exception) {}
}

private fun executeCmd(vararg command: String): String {
    val pb = ProcessBuilder(*command)
    val process = pb.redirectErrorStream(true).start()
    process.outputStream.close() // Close stdin to prevent child blocking

    val output = java.lang.StringBuilder()
    val reader = process.inputStream.bufferedReader()
    var line: String? = reader.readLine()
    while (line != null) {
        println("  [exec] $line")
        output.append(line).append("\n")
        line = reader.readLine()
    }

    process.waitFor(10, TimeUnit.MINUTES)
    return output.toString().trim()
}
