package io.mazewall.orchestrator

import java.io.File

data class BacklogIssue(
    val file: File,
    val id: String,
    val title: String,
    val priority: Int,
    val status: String,
    val dependencies: List<String>,
    val githubIssue: Int? = null
)

object BacklogParser {
    fun parseAllIssues(backlogDir: File): List<BacklogIssue> {
        if (!backlogDir.exists() || !backlogDir.isDirectory) {
            return emptyList()
        }

        return backlogDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("issue-") && it.name.endsWith(".md") }
            .filter { !it.absolutePath.contains("${File.separator}resolved${File.separator}") }
            .mapNotNull { parseIssueFile(it) }
            .toList()
    }

    fun parseIssueFile(file: File): BacklogIssue? {
        try {
            val content = file.readText()
            val frontmatter = extractFrontmatter(content) ?: return null

            val title = frontmatter["title"]?.removeSurrounding("\"")?.removeSurrounding("'") ?: file.name
            val priority = frontmatter["priority"]?.toIntOrNull() ?: 0
            val status = frontmatter["status"]?.removeSurrounding("\"")?.removeSurrounding("'") ?: "open"
            val githubIssue = frontmatter["github_issue"]?.toIntOrNull()
            
            // Extract dependencies list from the raw value
            val dependenciesRaw = frontmatter["dependencies"] ?: ""
            val dependencies = parseList(dependenciesRaw, content)

            val id = file.name.substringBefore("-").let { part1 ->
                val part2 = file.name.substringAfter("-").substringBefore("-")
                "$part1-$part2"
            }

            return BacklogIssue(file, id, title, priority, status, dependencies, githubIssue)
        } catch (e: Exception) {
            System.err.println("Error parsing issue file ${file.name}: ${e.message}")
            return null
        }
    }

    private fun extractFrontmatter(content: String): Map<String, String>? {
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return null

        val frontmatter = mutableMapOf<String, String>()
        var inFrontmatter = true
        var currentKey: String? = null
        var currentValBuilder = StringBuilder()

        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.trim() == "---") {
                inFrontmatter = false
                break
            }
            if (!inFrontmatter) break

            val trimmed = line.trim()
            if (trimmed.startsWith("-") && currentKey != null) {
                // Multiline list item (e.g. for dependencies)
                currentValBuilder.append("\n").append(trimmed)
            } else if (line.contains(":")) {
                // Key-value pair
                if (currentKey != null) {
                    frontmatter[currentKey] = currentValBuilder.toString().trim()
                }
                val parts = line.split(":", limit = 2)
                currentKey = parts[0].trim()
                currentValBuilder = StringBuilder(parts[1].trim())
            } else if (currentKey != null) {
                currentValBuilder.append("\n").append(line)
            }
        }
        if (currentKey != null) {
            frontmatter[currentKey] = currentValBuilder.toString().trim()
        }

        return frontmatter
    }

    private fun parseList(raw: String, fullContent: String): List<String> {
        val cleanRaw = raw.trim()
        if (cleanRaw.startsWith("[") && cleanRaw.endsWith("]")) {
            return cleanRaw.substring(1, cleanRaw.length - 1)
                .split(",")
                .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                .filter { it.isNotEmpty() }
        }

        // If it was multiline, split by lines starting with '-'
        return cleanRaw.lines()
            .map { it.trim() }
            .filter { it.startsWith("-") }
            .map { it.removePrefix("-").trim().removeSurrounding("\"").removeSurrounding("'") }
            .filter { it.isNotEmpty() }
    }

    fun writeGithubIssue(issue: BacklogIssue, issueNumber: Int) {
        val content = issue.file.readText()
        val lines = content.lines().toMutableList()
        val index = lines.indexOfFirst { it.trim() == "---" }
        if (index != -1) {
            val nextDashIndex = lines.subList(index + 1, lines.size).indexOfFirst { it.trim() == "---" }
            if (nextDashIndex != -1) {
                // Insert github_issue key before the closing ---
                val insertAt = index + 1 + nextDashIndex
                lines.add(insertAt, "github_issue: $issueNumber")
            }
        }
        issue.file.writeText(lines.joinToString("\n"))
    }

    fun removeGithubIssue(issue: BacklogIssue) {
        val content = issue.file.readText()
        val lines = content.lines().filter { !it.trim().startsWith("github_issue:") }
        issue.file.writeText(lines.joinToString("\n"))
    }

    fun markIssueAsResolved(issue: BacklogIssue, resolvedDir: File) {
        val content = issue.file.readText()
        // Replace status: "open" with status: "resolved" in frontmatter
        val updatedContent = content.replaceFirst("status: \"open\"", "status: \"resolved\"")
            .replaceFirst("status: 'open'", "status: 'resolved'")

        // Write the updated file back
        issue.file.writeText(updatedContent)

        // Move to resolved directory
        if (!resolvedDir.exists()) {
            resolvedDir.mkdirs()
        }
        val targetFile = File(resolvedDir, issue.file.name)
        if (issue.file.renameTo(targetFile)) {
            println("Moved resolved issue ${issue.id} to ${targetFile.absolutePath}")
        } else {
            System.err.println("Failed to move resolved issue ${issue.id} to ${targetFile.absolutePath}")
        }
    }
}
