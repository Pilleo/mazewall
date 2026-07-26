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
            status: "in_progress"
            priority: 10
            component: "profiler"
            target_modules: [":profiler", ":enforcer"]
            target_files: []
            effort: "medium"
            dependencies: []
            ---
            
        """.trimIndent()

        issueFile.writeText(frontmatter + body)

        val issue = BacklogParser.parseIssueFile(issueFile)
            ?: throw IllegalStateException("Failed to parse newly created review issue file ${issueFile.name}")

        // Add to active slots for immediate execution
        var slot = context.activeSlots.firstOrNull { it.currentIssueId == issue.id }
        if (slot == null) {
            slot = SlotContext(issue.id)
            context.activeSlots.add(slot)
        }
        slot.state = OrchestratorState.PENDING_APPROVAL
        slot.currentIssueTitle = issue.title
        slot.currentIssueFile = issueFile.path

        env.println("🚀 [IMMEDIATE LAUNCH] Created and launched review task `${issue.id}` (Priority: 10).")
        env.sendNotification("🚀 *Launched Review Task immediately:* `${issue.id}`\nPriority: 10 | Target: `:profiler`, `:enforcer`")

        return issue
    }
}
