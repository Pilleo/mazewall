package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit

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
    val skippedIds = mutableSetOf<String>()

    bot?.sendMessage("🤖 *Orchestrator Daemon Online* in repo `$julesRepo`.")

    while (true) {
        try {
            val allIssues = BacklogParser.parseAllIssues(backlogDir)
            println("📚 Backlog Status: ${allIssues.count { it.status == "open" }} open issues remaining.")
            val activeIssues = allIssues.filter { it.id !in skippedIds }
            val nextIssue = DependencyGraph.selectNextIssue(activeIssues)

            if (nextIssue == null) {
                println("💤 No unblocked open issues found. Checking again in 2 minutes...")
                TimeUnit.MINUTES.sleep(2)
                continue
            }

            println("\n🎯 Next prioritized task: ${nextIssue.id} - ${nextIssue.title} (Priority: ${nextIssue.priority})")

            val approved = if (nextIssue.githubIssue != null) {
                println("🔄 Resuming already-in-progress task ${nextIssue.id} (linked to GitHub issue #${nextIssue.githubIssue})...")
                true
            } else {
                // Ring terminal bell to alert developer locally
                ringTerminalBell(3)

                if (bot != null) {
                    val telegramText = """
                        🤖 *Request to start task ${nextIssue.id}*
                        *Title:* ${nextIssue.title}
                        *Priority:* ${nextIssue.priority} (0-10)
                        
                        Please approve or skip in the inline keyboard below.
                    """.trimIndent()
                    bot.sendMessageWithApprovalMarkup(nextIssue.id, telegramText)
                    bot.waitForApproval(nextIssue.id)
                } else {
                    print("\u001B[1;31m🔔 [APPROVAL REQUIRED] Start task ${nextIssue.id} - ${nextIssue.title}? (y/n): \u001B[0m")
                    System.out.flush()
                    val input = readlnOrNull()?.trim()?.lowercase()
                    input == "y" || input == "yes"
                }
            }

            if (!approved) {
                println("⏭️ Task ${nextIssue.id} skipped by user. Postponing.")
                skippedIds.add(nextIssue.id)
                bot?.sendMessage("⏭️ Task `${nextIssue.id}` skipped. Will pick another.")
                continue
            }

            bot?.sendMessage("🚀 Starting task `${nextIssue.id}`...")
            
            // 1. Create or retrieve GitHub Issue
            var githubIssueNumber = nextIssue.githubIssue?.toString()
            val issueTitle = "[${nextIssue.id}] ${nextIssue.title}"
            if (githubIssueNumber == null) {
                // Recovery: check if an issue was already created in a previous session (e.g. script was killed mid-run)
                val existingIssueNumber = GitHubCli.findExistingIssueNumber(nextIssue.id)
                if (existingIssueNumber != null) {
                    println("♻️ Recovered existing GitHub issue #$existingIssueNumber for ${nextIssue.id} (was missing from backlog file).")
                    githubIssueNumber = existingIssueNumber
                    BacklogParser.writeGithubIssue(nextIssue, existingIssueNumber.toInt())
                } else {
                    println("Creating GitHub issue for ${nextIssue.id}...")
                    githubIssueNumber = GitHubCli.createIssue(issueTitle, nextIssue.file, "jules")
                    BacklogParser.writeGithubIssue(nextIssue, githubIssueNumber.toInt())
                    println("Created GitHub issue #$githubIssueNumber")
                }
            } else {
                println("Using existing GitHub issue #$githubIssueNumber")
            }

            val finalIssueNumber = githubIssueNumber

            // 2. Poll for the automatically triggered Jules Session via GitHub label
            var activeSession = JulesCli.getActiveSession(nextIssue.id)
            var attempts = 0
            while (activeSession == null && attempts < 12) {
                println("Waiting for Jules session to be automatically triggered via GitHub issue label (attempt ${attempts + 1}/12)...")
                TimeUnit.SECONDS.sleep(15)
                activeSession = JulesCli.getActiveSession(nextIssue.id)
                attempts++
            }
            
            if (activeSession != null) {
                println("Linked Jules session: ID=${activeSession.id}, Status=${activeSession.status}")
            }

            // 3. Monitor PR & Build status
            var prNumber = GitHubCli.findLinkedPR(finalIssueNumber, nextIssue.id, activeSession?.id)
            var lastStatus: String? = null
            var taskCanceled = false
            while (prNumber == null) {
                if (GitHubCli.isIssueClosed(finalIssueNumber)) {
                    println("\n\u001B[1;33m⚠️ GitHub issue #$finalIssueNumber was closed. Canceling task ${nextIssue.id}.\u001B[0m")
                    bot?.sendMessage("⚠️ GitHub issue #$finalIssueNumber was closed. Task `${nextIssue.id}` canceled.")
                    BacklogParser.removeGithubIssue(nextIssue)
                    skippedIds.add(nextIssue.id)
                    taskCanceled = true
                    break
                }

                val session = JulesCli.getActiveSession(nextIssue.id)
                if (session != null && session.status != lastStatus) {
                    println("Jules session status changed: ${session.status}")
                    lastStatus = session.status

                    val sessionUrl = "https://jules.google.com/session/${session.id}"
                    if (session.status.contains("Awaiting", ignoreCase = true) || session.status.contains("Feedback", ignoreCase = true)) {
                        // Alert developer!
                        val alertMsg = "⚠️ *Jules needs feedback on task ${nextIssue.id}!* Status: `${session.status}`. Please check and respond here: $sessionUrl"
                        bot?.sendMessage(alertMsg)
                        println("\n\u001B[1;31m🔔 [FEEDBACK REQUIRED] Jules is blocked waiting for feedback on task ${nextIssue.id}. Status: ${session.status}\u001B[0m")
                        println("👉 Respond here: $sessionUrl")
                        ringTerminalBell(5)
                    } else if (session.status.equals("Completed", ignoreCase = true)) {
                        val alertMsg = "🟢 *Jules task ${nextIssue.id} is Completed!* Ready to publish PR: $sessionUrl"
                        bot?.sendMessage(alertMsg)
                        println("\n\u001B[1;32m🟢 [COMPLETED] Jules task ${nextIssue.id} is Completed! Please review and publish the PR in the UI.\u001B[0m")
                        println("👉 Publish PR here: $sessionUrl")
                        ringTerminalBell(5)
                    }
                }

                println("Waiting for Jules to open a PR for issue #$finalIssueNumber...")
                TimeUnit.SECONDS.sleep(30)
                prNumber = GitHubCli.findLinkedPR(finalIssueNumber, nextIssue.id, activeSession?.id)
            }

            if (taskCanceled) {
                continue
            }
            
            val finalPrNumber = prNumber!!
            println("Jules opened PR #$finalPrNumber")

            // 4. Poll CI Checks, run Antigravity reviews, and wait for manual merge
            var lastHeadSha: String? = null
            var lastReviewedSha: String? = null
            var lastBuildStatus: String? = null
            var lastCheckedSha: String? = null
            var lastWaitingLogTime: Long = 0
            val conversationId = getEnvOrNull("CONVERSATION_ID")

            while (!GitHubCli.isPrMerged(finalPrNumber)) {
                // Get current head SHA of the PR to detect new commits
                val currentSha = GitHubCli.getPrHeadSha(finalPrNumber)
                if (currentSha != lastHeadSha) {
                    println("🔄 New commits detected on PR #$finalPrNumber (Head SHA: $currentSha). Checking build status...")
                    lastHeadSha = currentSha
                }

                val status = GitHubCli.checkBuildStatus(finalPrNumber)
                if (status != lastBuildStatus || currentSha != lastCheckedSha) {
                    println("PR #$finalPrNumber build check: $status")
                    lastBuildStatus = status
                    lastCheckedSha = currentSha
                }

                when (status) {
                    "SUCCESS" -> {
                        // If we haven't reviewed this specific SHA yet, check if it was reviewed in a previous session.
                        // Match both old format (no SHA) and new format (with SHA prefix) comment bodies.
                        if (currentSha != lastReviewedSha) {
                            val comments = executeCmd("gh", "pr", "view", finalPrNumber, "--json", "comments")
                            val alreadyReviewed = comments.contains("SHA: ${currentSha.take(7)}") ||
                                comments.contains("Approved by Antigravity") ||
                                comments.contains("Rejected by Antigravity")
                            if (alreadyReviewed) {
                                println("✨ PR #$finalPrNumber already has a review comment. Skipping re-review.")
                                lastReviewedSha = currentSha
                            }
                        }

                        if (currentSha != lastReviewedSha) {
                            println("🤖 Invoking Antigravity via agy CLI to review PR #$finalPrNumber (SHA: $currentSha)...")
                            bot?.sendMessage("🤖 *PR #$finalPrNumber Build Passed.* Asking Antigravity to review SHA `${currentSha.take(7)}`...")
                            
                            val prompt = """
                                Perform a PR review on PR #$finalPrNumber at SHA $currentSha using the 'pr_review' skill.
                                Make sure to run 'gh pr diff $finalPrNumber', inspect the diff, and format your output with <review> tags.
                            """.trimIndent()

                            val result = executeCmd("agy", "--dangerously-skip-permissions", "--print", prompt)
                            println("Antigravity Review Output:\n$result")
                            lastReviewedSha = currentSha

                            val lastLine = result.lines().lastOrNull { it.trim().isNotEmpty() }?.trim()?.uppercase() ?: ""
                            val prUrl = executeCmd("gh", "pr", "view", finalPrNumber, "--json", "url").substringAfter("\"url\":\"").substringBefore("\"")
                            
                            val rawReviewText = if (result.contains("<review>") && result.contains("</review>")) {
                                result.substringAfter("<review>").substringBefore("</review>").trim()
                            } else {
                                result.lines().filter { !it.trim().uppercase().equals("APPROVED") && !it.trim().uppercase().equals("REJECTED") }.joinToString("\n").trim()
                            }

                            if (lastLine.contains("APPROVED")) {
                                println("🟢 Antigravity approved PR #$finalPrNumber.")
                                bot?.sendMessage("🟢 *Antigravity Approved PR #$finalPrNumber!* Ready for your final manual review and merge: $prUrl")
                                executeCmd("gh", "pr", "comment", finalPrNumber, "--body", "🟢 **PR Approved by Antigravity (SHA: ${currentSha.take(7)})**\n\n$rawReviewText")
                            } else {
                                println("⚠️ Antigravity rejected or had concerns about PR #$finalPrNumber.")
                                bot?.sendMessage("⚠️ *Antigravity Rejected PR #$finalPrNumber.* Please check review details: $prUrl")
                                executeCmd("gh", "pr", "comment", finalPrNumber, "--body", "@jules ⚠️ **PR Rejected by Antigravity (SHA: ${currentSha.take(7)})**\n\n$rawReviewText")
                            }
                            
                            println("🟢 PR #$finalPrNumber is green. Ready for manual review and merge: $prUrl")
                            ringTerminalBell(3)
                        }

                        // Sleep and wait for manual developer merge
                        val now = System.currentTimeMillis()
                        if (now - lastWaitingLogTime > 60_000) {
                            val prUrl = executeCmd("gh", "pr", "view", finalPrNumber, "--json", "url").substringAfter("\"url\":\"").substringBefore("\"")
                            println("⌛ Waiting for manual merge of PR #$finalPrNumber at: $prUrl")
                            lastWaitingLogTime = now
                        }
                        TimeUnit.SECONDS.sleep(30)
                    }
                    "FAILURE" -> {
                        println("❌ Build failed on PR #$finalPrNumber. Fetching logs...")
                        val failedLogs = GitHubCli.getFailedBuildLogs(finalPrNumber)
                        val feedback = """
                            ❌ **CI Build Failed.**
                            Jules, please review the failing logs and fix the implementation:
                            
                            ```
                            $failedLogs
                            ```
                        """.trimIndent()
                        
                        // Comment on PR using gh cli
                        executeCmd("gh", "pr", "comment", finalPrNumber, "--body", feedback)
                        bot?.sendMessage("❌ Build failed on PR #$finalPrNumber. Feedback sent to Jules.")
                        
                        // Sleep 5 minutes to let Jules rebuild/re-commit
                        TimeUnit.MINUTES.sleep(5)
                    }
                    else -> {
                        // Pending / in progress, wait 30 seconds
                        TimeUnit.SECONDS.sleep(30)
                    }
                }
            }

            println("PR #$finalPrNumber merged!")
            bot?.sendMessage("🎉 PR #$finalPrNumber merged! resolving issue locally...")

            // 7. Transition issue local status
            BacklogParser.markIssueAsResolved(nextIssue, resolvedDir)

            // 8. Regenerate Knowledge Map
            println("Regenerating architectural maps...")
            executeCmd("./gradlew", "generateKnowledgeMap")
            bot?.sendMessage("✅ Resolved issue `${nextIssue.id}`. Picking next task...")

        } catch (e: Exception) {
            System.err.println("⚠️ Error in loop cycle: ${e.message}")
            e.printStackTrace()
            try {
                bot?.sendMessage("⚠️ *Daemon Error:* `${e.message}`. Retrying in 2 minutes...")
            } catch (_: Exception) {}
            TimeUnit.MINUTES.sleep(2)
        }
    }
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
    val env = pb.environment()
    val customToken = System.getProperty("GITHUB_TOKEN")
    if (customToken != null) {
        env["GITHUB_TOKEN"] = customToken
    } else {
        env.remove("GITHUB_TOKEN")
    }
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
