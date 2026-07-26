package io.mazewall.orchestrator

import java.io.File
import kotlin.system.exitProcess

object BacklogValidator {

    private val VALID_SEVERITIES = setOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "ENHANCEMENT")
    private val VALID_STATUSES = setOf("open", "in_progress", "resolved", "deferred")
    private val VALID_COMPONENTS = setOf("enforcer", "profiler", "orchestrator", "docs", "ci", "testing")
    private val VALID_FILENAME_PATTERN = Regex("^issue-(?:\\d{8}[-_]\\d{6}(?:[-_]\\d{2})?|\\d{8}[-_]\\d{2,4}|\\d{1,4})[-_][a-z0-9_-]+\\.md$")

    fun validateBacklog(backlogDir: File): List<String> {
        if (!backlogDir.exists() || !backlogDir.isDirectory) {
            return listOf("Backlog directory does not exist: ${backlogDir.absolutePath}")
        }

        val errors = mutableListOf<String>()

        // 1. Gather all issues (including resolved for dependency validation)
        val allIssues = backlogDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("issue-") && it.name.endsWith(".md") }
            .mapNotNull { BacklogParser.parseIssueFile(it) }
            .toList()

        val knownIssueIds = allIssues.map { it.id }.toSet()

        // 2. Validate open/active backlog files
        val activeFiles = backlogDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("issue-") && it.name.endsWith(".md") }
            .filter { !it.absolutePath.contains("${File.separator}resolved${File.separator}") }
            .toList()

        for (file in activeFiles) {
            if (!VALID_FILENAME_PATTERN.matches(file.name)) {
                errors.add("${file.name}: Invalid filename format. Date-based issue filenames must include 6-digit seconds 'issue-YYYYMMDD-HHMMSS-slug.md'")
            }

            val content = file.readText()
            if (!content.startsWith("---")) {
                errors.add("${file.name}: Missing YAML frontmatter header (must start with '---')")
                continue
            }

            val issue = BacklogParser.parseIssueFile(file)
            if (issue == null) {
                errors.add("${file.name}: Failed to parse YAML frontmatter or issue structure")
                continue
            }

            if (issue.title.isBlank()) {
                errors.add("${file.name}: Missing or empty 'title' in frontmatter")
            }

            if (issue.severity != null && issue.severity !in VALID_SEVERITIES) {
                errors.add("${file.name}: Invalid severity '${issue.severity}'. Allowed: $VALID_SEVERITIES")
            }

            if (issue.status !in VALID_STATUSES) {
                errors.add("${file.name}: Invalid status '${issue.status}'. Allowed: $VALID_STATUSES")
            }

            if (issue.priority < 0 || issue.priority > 10) {
                errors.add("${file.name}: Priority '${issue.priority}' out of bounds (must be 0..10)")
            }

            if (issue.component.isNullOrBlank() || issue.component !in VALID_COMPONENTS) {
                errors.add("${file.name}: Invalid or missing component '${issue.component}'. Allowed: $VALID_COMPONENTS")
            }

            if (!content.contains("target_modules:")) {
                errors.add("${file.name}: Missing required 'target_modules' field (e.g. [\":enforcer\"])")
            }

            if (!content.contains("target_files:")) {
                errors.add("${file.name}: Missing required 'target_files' field (e.g. list of file paths or [])")
            }

            if ((issue.status == "open" || issue.status == "in_progress") && issue.targetFiles.isEmpty()) {
                errors.add("${file.name}: Missing or empty 'target_files' list for active issue")
            }

            // Check dependencies existence
            for (dep in issue.dependencies) {
                if (dep.isNotBlank() && dep !in knownIssueIds) {
                    errors.add("${file.name}: References non-existent dependency '$dep'")
                }
            }
        }

        return errors
    }
}

fun main(args: Array<String>) {
    val rootDir = File(args.getOrNull(0) ?: ".")
    val backlogDir = File(rootDir, "docs/internals/backlog")
    val errors = BacklogValidator.validateBacklog(backlogDir)

    if (errors.isNotEmpty()) {
        System.err.println("❌ Backlog Validation Failed (${errors.size} error(s)):")
        errors.forEach { System.err.println("  - $it") }
        exitProcess(1)
    }

    println("✅ Backlog Schema & Dependencies Validation Passed cleanly (${backlogDir.path}).")
}
