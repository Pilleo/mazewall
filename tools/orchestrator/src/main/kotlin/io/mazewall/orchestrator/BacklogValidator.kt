package io.mazewall.orchestrator

import java.io.File
import kotlin.system.exitProcess

object BacklogValidator {

    private val VALID_SEVERITIES = setOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "ENHANCEMENT")
    private val VALID_STATUSES = setOf("open", "in_progress", "resolved", "deferred")
    private val VALID_COMPONENTS = setOf("enforcer", "profiler", "orchestrator", "docs", "ci", "testing", "platform")
    private val VALID_FILENAME_PATTERN = Regex("^issue-(?:\\d{8}[-_]\\d{6}(?:[-_]\\d{2})?|\\d{8}[-_]\\d{2,4}|\\d{1,4})[-_][a-z0-9_-]+\\.md$")
    private val VALID_GRADLE_MODULES = setOf(
        ":platform",
        ":enforcer",
        ":profiler",
        ":portal",
        ":portal-codegen",
        ":demos:cli-demo",
        ":demos:vulnerable-web-app",
        ":demos:agent-sandbox-demo",
        ":tools:orchestrator"
    )

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
            } else if (issue.status == "resolved") {
                errors.add("${file.name}: Issue has status 'resolved' but is located in '${file.parentFile.name}' instead of the 'resolved' directory")
            }

            // Priority is parsed as high/medium/low; invalid tokens fail parseIssueFile.

            if (issue.component.isNullOrBlank() || issue.component !in VALID_COMPONENTS) {
                errors.add("${file.name}: Invalid or missing component '${issue.component}'. Allowed: $VALID_COMPONENTS")
            }

            if (!content.contains("target_modules:")) {
                errors.add("${file.name}: Missing required 'target_modules' field (e.g. [\":enforcer\"])")
            } else if (issue.targetModules.isEmpty()) {
                errors.add("${file.name}: 'target_modules' must contain at least one valid Gradle module (got empty list)")
            } else {
                for (module in issue.targetModules) {
                    if (module !in VALID_GRADLE_MODULES) {
                        errors.add("${file.name}: Invalid Gradle module '$module' in target_modules. Allowed: $VALID_GRADLE_MODULES")
                    }
                }
            }

            if (!content.contains("target_files:")) {
                errors.add("${file.name}: Missing required 'target_files' field (e.g. list of file paths or [])")
            } else if (issue.targetFiles.isEmpty() && (issue.status == "open" || issue.status == "in_progress")) {
                errors.add("${file.name}: 'target_files' must contain at least one file path for ${issue.status} issues (got empty list)")
            }

            // Validate open_questions consistency
            val openQuestionsFrontmatterMatch = Regex("(?m)^open_questions:\\s*(.+)$").find(content)
            if (openQuestionsFrontmatterMatch != null) {
                val rawVal = openQuestionsFrontmatterMatch.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
                if (rawVal != "true" && rawVal != "false" && !rawVal.startsWith("[") && rawVal.isNotBlank()) {
                    errors.add("${file.name}: Invalid 'open_questions' value '$rawVal'. Allowed: true, false, or list")
                }
                if (rawVal == "true" && issue.openQuestions.isNullOrBlank()) {
                    errors.add("${file.name}: Declares 'open_questions: true' in frontmatter but is missing a non-empty '## ❓ Open Questions' section in the body")
                }
                if (rawVal == "false" && !issue.openQuestions.isNullOrBlank()) {
                    errors.add("${file.name}: Declares 'open_questions: false' in frontmatter but contains an 'Open Questions' section in the body")
                }
            } else if (!issue.openQuestions.isNullOrBlank()) {
                errors.add("${file.name}: Contains an 'Open Questions' section in body but frontmatter is missing 'open_questions: true'")
            }

            // Check dependencies existence
            for (dep in issue.dependencies) {
                if (dep.isNotBlank() && dep !in knownIssueIds) {
                    errors.add("${file.name}: References non-existent dependency '$dep'")
                }
            }
        }

        // 3. Validate resolved files (ensure they have status 'resolved')
        val resolvedFiles = backlogDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("issue-") && it.name.endsWith(".md") }
            .filter { it.absolutePath.contains("${File.separator}resolved${File.separator}") }
            .toList()

        for (file in resolvedFiles) {
            val issue = BacklogParser.parseIssueFile(file)
            if (issue != null && issue.status != "resolved") {
                errors.add("${file.name}: Issue is in 'resolved' directory but has status '${issue.status}' (expected 'resolved')")
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
