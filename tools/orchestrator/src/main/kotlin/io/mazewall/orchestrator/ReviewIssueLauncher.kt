package io.mazewall.orchestrator

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ReviewIssueLauncher {

    fun launchReviewTask(
        focusComments: String,
        backlogDir: File,
        env: OrchestratorEnvironment,
        context: OrchestratorContext
    ): BacklogIssue {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val issueId = "issue-$timestamp-review-task"
        val fileName = "$issueId.md"

        val targetDir = File(backlogDir, "code_health")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val issueFile = File(targetDir, fileName)
        val body = OrchestratorPrompts.reviewTaskIssueBody(focusComments)

        val frontmatter = """
            ---
            title: "Review Task: Profiler Module & Security Audit"
            severity: "HIGH"
            status: "open"
            priority: high
            component: "profiler"
            target_modules: [":profiler", ":enforcer"]
            target_files: ["docs/internals/backlog/code_health/$fileName"]
            effort: "medium"
            dependencies: []
            ---

        """.trimIndent()

        issueFile.writeText(frontmatter + body)

        val issue = BacklogParser.parseIssueFile(issueFile)
            ?: throw IllegalStateException("Failed to parse newly created review issue file ${issueFile.name}")

        // 1. Create GitHub issue immediately with "jules-start" label so Jules triggers on GitHub
        val issueTitleForGit = "[$issueId] ${issue.title}"
        val enhancedBody = OrchestratorPrompts.taskPrompt(issueFile.readText())
        val githubIssueNumber = try {
            env.gitHubClient.createIssue(issueTitleForGit, enhancedBody, "jules-start")
        } catch (e: Exception) {
            env.errPrintln("Error creating GitHub issue for review task $issueId: ${e.message}")
            null
        }

        val updatedIssue = if (githubIssueNumber != null) {
            env.println("Created GitHub issue #$githubIssueNumber for review task $issueId")
            env.writeGithubIssue(issue, githubIssueNumber.toInt())
            BacklogParser.parseIssueFile(issueFile) ?: issue
        } else {
            issue
        }

        // 2. Add slot and set state directly to AWAITING_JULES_START (or PENDING_APPROVAL if issue creation failed)
        var slot = context.activeSlots.firstOrNull { it.currentIssueId == updatedIssue.id }
        if (slot == null) {
            slot = SlotContext(updatedIssue.id)
            context.activeSlots.add(slot)
        }
        slot.currentIssueTitle = updatedIssue.title
        slot.currentIssueFile = issueFile.path
        slot.githubIssueNumber = githubIssueNumber
        slot.state = if (githubIssueNumber != null) {
            AwaitingJulesStartState(updatedIssue.id, githubIssueNumber)
        } else {
            PendingApprovalState(updatedIssue.id, updatedIssue.title, issueFile.path, githubIssueNumber)
        }

        env.println("🚀 [IMMEDIATE LAUNCH] Created GitHub issue #${githubIssueNumber ?: "N/A"} and launched review task `${updatedIssue.id}` (Priority: 10).")
        env.sendNotification("🚀 *Launched Review Task immediately on GitHub (Issue #${githubIssueNumber ?: "N/A"}):* `${updatedIssue.id}`\nPriority: 10 | Target: `:profiler`, `:enforcer`")

        return updatedIssue
    }
}
