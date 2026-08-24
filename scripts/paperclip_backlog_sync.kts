// paperclip_backlog_sync.kts - Backlog DAG Ingester
//
// Scans docs/internals/backlog/ for issue files, performs topological sort
// to find unblocked issues, and syncs them with Paperclip REST API.
//
// This script requires Kotlin to be installed and available in PATH.
// For Kotlin 2.x, use: kotlin -Xuse-fir-lt=false scripts/paperclip_backlog_sync.kts
// For Kotlin 1.x: kotlin scripts/paperclip_backlog_sync.kts
//
// Usage:
//   kotlin scripts/paperclip_backlog_sync.kts [--dry-run] [--force]
//
// Environment variables:
//   PAPERCLIP_API_KEY      - Paperclip API key for authentication (required)
//   PAPERCLIP_API_URL      - Paperclip API base URL (default: http://127.0.0.1:3100)
//   PAPERCLIP_COMPANY_ID   - Company ID (default: auto-detected from /agents/me)
//   BACKLOG_DIR           - Backlog directory (default: docs/internals/backlog)
//   LOCKFILE              - Lock file path (default: /tmp/paperclip_backlog_sync.lock)
//   DRY_RUN               - If set, don't actually create issues
//   FORCE                - If set, re-sync even if issues already exist
//

@file:JvmName("PaperclipBacklogSync")

import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess


// ============================================================================
// Backlog Parser (inlined from :tools:orchestrator)
// ============================================================================

enum class BacklogPriority {
    HIGH,
    MEDIUM,
    LOW;

    val rank: Int
        get() = when (this) {
            HIGH -> 2
            MEDIUM -> 1
            LOW -> 0
        }

    companion object {
        fun parse(raw: String?): BacklogPriority {
            val token = raw?.removeSurrounding("\"")?.removeSurrounding("'")?.trim()?.uppercase()
            return when (token) {
                "HIGH" -> HIGH
                "MEDIUM" -> MEDIUM
                "LOW" -> LOW
                else -> throw IllegalArgumentException(
                    "Frontmatter 'priority' must be high, medium, or low (got '$raw')"
                )
            }
        }
    }
}

data class BacklogIssue(
    val file: File,
    val id: String,
    val title: String,
    val priority: BacklogPriority,
    val status: String,
    val dependencies: List<String>,
    val githubIssue: Int? = null,
    val severity: String? = null,
    val component: String? = null,
    val effort: String? = null,
    val context: String? = null,
    val needed: String? = null,
    val targetFiles: List<String> = emptyList(),
    val targetModules: List<String> = emptyList(),
    val openQuestions: String? = null,
    val hasOpenQuestions: Boolean = false
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
            val priority = BacklogPriority.parse(frontmatter["priority"])
            val status = frontmatter["status"]?.removeSurrounding("\"")?.removeSurrounding("'") ?: "open"
            val githubIssue = frontmatter["github_issue"]?.toIntOrNull()
            val severity = frontmatter["severity"]?.removeSurrounding("\"")?.removeSurrounding("'")
            val component = frontmatter["component"]?.removeSurrounding("\"")?.removeSurrounding("'")
            val effort = frontmatter["effort"]?.removeSurrounding("\"")?.removeSurrounding("'")

            val dependenciesRaw = frontmatter["dependencies"] ?: ""
            val dependencies = parseList(dependenciesRaw)

            val targetFilesRaw = frontmatter["target_files"] ?: ""
            val targetFiles = parseList(targetFilesRaw)

            val targetModulesRaw = frontmatter["target_modules"] ?: ""
            val targetModules = parseList(targetModulesRaw)

            val id = frontmatter["id"]?.removeSurrounding("\"")?.removeSurrounding("'") ?: run {
                val nameWithoutExt = file.name.removeSuffix(".md")
                val parts = nameWithoutExt.split("-")
                if (parts.size >= 3 && parts[1].matches(Regex("\\d{8}"))) {
                    "${parts[0]}-${parts[1]}-${parts[2]}"
                } else if (parts.size >= 2) {
                    "${parts[0]}-${parts[1]}"
                } else {
                    nameWithoutExt
                }
            }

            val body = content.substringAfter("---", "").substringAfter("---", "").trim()
            val context = extractSection(body, "Context")
            val needed = extractSection(body, "Needed")
            val openQuestions = extractSection(body, "Open Questions") ?: extractSection(body, "❓ Open Questions")
            val openQuestionsFrontmatter = frontmatter["open_questions"]?.removeSurrounding("\"")?.removeSurrounding("'")
            val hasOpenQuestions = (openQuestionsFrontmatter?.equals("true", ignoreCase = true) == true) ||
                    (!openQuestions.isNullOrBlank())

            return BacklogIssue(
                file, id, title, priority, status, dependencies, githubIssue,
                severity, component, effort, context, needed, targetFiles, targetModules,
                openQuestions, hasOpenQuestions
            )
        } catch (e: Exception) {
            System.err.println("Error parsing issue file ${file.name}: ${e.message}")
            return null
        }
    }

    private val KEY_PATTERN = Regex("^[a-zA-Z0-9_-]+:\\s*(.*)$")

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
                currentValBuilder.append("\n").append(trimmed)
            } else if (KEY_PATTERN.matches(line)) {
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

    private fun parseList(raw: String): List<String> {
        val cleanRaw = raw.trim()
        val cleaner = { s: String ->
            var current = s.trim()
            while (true) {
                val next = current.removeSurrounding("\"").removeSurrounding("'")
                    .removeSurrounding("\\\"").removeSurrounding("\\'")
                    .trim()
                if (next == current) break
                current = next
            }
            current
        }

        if (cleanRaw.startsWith("[") && cleanRaw.endsWith("]")) {
            return cleanRaw.substring(1, cleanRaw.length - 1)
                .split(",")
                .map { cleaner(it) }
                .filter { it.isNotEmpty() }
        }

        return cleanRaw.lines()
            .map { it.trim() }
            .filter { it.startsWith("-") }
            .map { cleaner(it.removePrefix("-")) }
            .filter { it.isNotEmpty() }
    }

    private fun extractSection(body: String, sectionName: String): String? {
        val markers = listOf(
            "**$sectionName:",
            "### $sectionName",
            "## $sectionName",
            "## ❓ $sectionName",
            "### ❓ $sectionName",
            "**❓ $sectionName:"
        )
        var startIndex = -1
        for (marker in markers) {
            startIndex = body.indexOf(marker, ignoreCase = true)
            if (startIndex != -1) {
                startIndex += marker.length
                break
            }
        }
        if (startIndex == -1) return null

        val rest = body.substring(startIndex).trim()
        val lines = rest.lines()
        val contentLines = mutableListOf<String>()

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("###") || trimmedLine.startsWith("##")) {
                break
            }
            if (trimmedLine.startsWith("**") && trimmedLine.endsWith("**")) {
                val core = trimmedLine.removeSurrounding("**")
                if (core.endsWith(":") || core.lowercase().contains("needed") || core.lowercase().contains("context")) {
                    break
                }
            }
            if (trimmedLine.startsWith("**") && trimmedLine.contains(":**")) {
                break
            }
            contentLines.add(line)
        }

        return contentLines.joinToString("\n").trim()
    }
}


// ============================================================================
// Dependency Graph (inlined from :tools:orchestrator)
// ============================================================================

object DependencyGraph {
    fun selectNextIssue(issues: List<BacklogIssue>): BacklogIssue? {
        val openIssues = issues.filter { it.status == "open" }
        val allActiveIds = issues.map { it.id }.toSet()

        // An issue is unblocked if none of its dependencies are currently in any active non-resolved status
        val unblockedIssues = openIssues.filter { issue ->
            issue.dependencies.none { dep -> allActiveIds.contains(dep) }
        }

        // Sort HIGH > MEDIUM > LOW, then ID descending
        return unblockedIssues.sortedWith(
            compareByDescending<BacklogIssue> { it.priority.rank }
                .thenByDescending { it.id }
        ).firstOrNull()
    }
}


// ============================================================================
// HTTP + JSON helpers (no external dependencies; standalone kts)
// ============================================================================

fun jsonEscape(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.toString()
}

data class HttpResult(val status: Int, val body: String)

fun httpRequest(
    method: String,
    url: String,
    apiKey: String,
    bodyJson: String? = null
): HttpResult {
    val conn = (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 10_000
        readTimeout = 30_000
        setRequestProperty("Authorization", "Bearer $apiKey")
        setRequestProperty("Accept", "application/json")
        if (bodyJson != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
    }
    if (bodyJson != null) {
        conn.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
    }
    val status = conn.responseCode
    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    return HttpResult(status, body)
}

// ============================================================================
// Configuration
// ============================================================================

val DEFAULT_API_URL = "http://127.0.0.1:3100"
val DEFAULT_BACKLOG_DIR = "docs/internals/backlog"
val DEFAULT_LOCKFILE = "/tmp/paperclip_backlog_sync.lock"
val DEFAULT_LOCK_TIMEOUT_SECONDS = 30L

fun main(args: Array<String>) {
    println("Paperclip Backlog DAG Ingester")
    println("Note: This script requires Kotlin 1.x or use: kotlin -Xuse-fir-lt=false")
    println()
    
    // Get configuration from environment
    val apiKey = System.getenv("PAPERCLIP_API_KEY") ?: run {
        System.err.println("ERROR: PAPERCLIP_API_KEY environment variable is required")
        exitProcess(1)
    }

    val apiUrl = System.getenv("PAPERCLIP_API_URL") ?: DEFAULT_API_URL
    val companyId = System.getenv("PAPERCLIP_COMPANY_ID")
    val backlogDirPath = System.getenv("BACKLOG_DIR") ?: DEFAULT_BACKLOG_DIR
    val lockFile = System.getenv("LOCKFILE") ?: DEFAULT_LOCKFILE
    val dryRun = System.getenv("DRY_RUN")?.toBoolean() ?: false
    val force = System.getenv("FORCE")?.toBoolean() ?: false

    // Parse arguments
    var dryRunFlag = dryRun
    var forceFlag = force

    for (arg in args) {
        when (arg) {
            "--dry-run", "-n" -> dryRunFlag = true
            "--force", "-f" -> forceFlag = true
            else -> {
                println("Unknown argument: $arg")
                printUsage()
                exitProcess(1)
            }
        }
    }

    if (dryRunFlag) {
        println("[DRY RUN MODE - No changes will be made]")
    }
    if (forceFlag) {
        println("[FORCE MODE - Will re-sync existing issues]")
    }
    println()

    // Check backlog directory
    val backlogDir = File(backlogDirPath)
    if (!backlogDir.exists()) {
        System.err.println("ERROR: Backlog directory not found: ${backlogDir.absolutePath}")
        exitProcess(1)
    }

    // Get company ID: explicit env wins, else auto-detect from the API.
    val resolvedCompanyId = companyId ?: if (dryRunFlag) {
        "dry-run-company-id"
    } else {
        val res = httpRequest("GET", "$apiUrl/api/companies", apiKey)
        if (res.status != 200) {
            System.err.println("ERROR: Cannot auto-detect company id (HTTP ${res.status}). " +
                "Set PAPERCLIP_COMPANY_ID explicitly.")
            exitProcess(1)
        }
        Regex("\"id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"").find(res.body)?.groupValues?.get(1) ?: run {
            System.err.println("ERROR: No company found on the Paperclip instance.")
            exitProcess(1)
        }
    }

    println("Using Company ID: $resolvedCompanyId")
    println("Using API URL: $apiUrl")
    println("Using Backlog Dir: ${backlogDir.absolutePath}")
    println("Using Lock File: $lockFile")
    println()

    // Acquire simple file lock using mkdir (atomic on most filesystems)
    val lockDir = File(lockFile + ".lock")
    val lockAcquired = tryAcquireLock(lockDir, DEFAULT_LOCK_TIMEOUT_SECONDS)
    if (!lockAcquired) {
        System.err.println("ERROR: Could not acquire lock within ${DEFAULT_LOCK_TIMEOUT_SECONDS}s")
        exitProcess(1)
    }

    try {
        println("Lock acquired, starting sync...")
        
        // 1. Parse all backlog issues
        val backlogIssues = BacklogParser.parseAllIssues(backlogDir)
        println("Found ${backlogIssues.size} backlog issues")

        // 2. Partition by sync state (frontmatter `paperclip_issue_id:`) and build
        //    backlog-id -> paperclip-id map from already-synced files.
        val syncedIdPattern = Regex("paperclip_issue_id:\\s*([0-9a-fA-F-]{36})")
        val paperclipIdByBacklogId = mutableMapOf<String, String>()
        for (issue in backlogIssues) {
            syncedIdPattern.find(issue.file.readText())?.let {
                paperclipIdByBacklogId[issue.id] = it.groupValues[1]
            }
        }
        val unsyncedIssues = backlogIssues.filter { it.id !in paperclipIdByBacklogId }
        println("  - Already synced: ${backlogIssues.size - unsyncedIssues.size}")
        println("  - Not synced: ${unsyncedIssues.size}")

        // 3. Compute the pushable set: open issues whose dependencies are all
        //    satisfied — either already pushed to the board (pass as blockedBy)
        //    or not active in the local backlog (resolved/unknown = non-blocking).
        val activeLocalIds = backlogIssues.map { it.id }.toSet()
        val deferred = linkedMapOf<BacklogIssue, List<String>>()

        fun pushableDeps(issue: BacklogIssue): Pair<Boolean, List<String>> {
            val blockedBy = mutableListOf<String>()
            for (dep in issue.dependencies) {
                val pcId = paperclipIdByBacklogId[dep]
                when {
                    pcId != null -> blockedBy.add(pcId)
                    !activeLocalIds.contains(dep) -> { /* resolved or stale reference */ }
                    else -> return false to listOf(dep) // active but not yet pushed: defer
                }
            }
            return true to blockedBy
        }

        // 4. Push in priority order; write back frontmatter so siblings can chain
        //    and re-runs are idempotent. One batch per run keeps the operation
        //    bounded and reviewable.
        var created = 0
        var failed = 0
        val progress = mutableListOf<BacklogIssue>()
        progress.addAll(unsyncedIssues.filter { it.status == "open" })
        progress.sortWith(compareByDescending<BacklogIssue> { it.priority.rank }.thenByDescending { it.id })

        for (issue in progress) {
            val (pushable, blockedBy) = pushableDeps(issue)
            if (!pushable) {
                deferred[issue] = blockedBy
                continue
            }
            if (dryRunFlag) {
                println("[DRY RUN] Would create: ${issue.id} '${issue.title}' " +
                        "(blockedBy=${blockedBy.size})")
                paperclipIdByBacklogId[issue.id] = "dry-run-${issue.id}" // let the batch chain
                continue
            }

            // Repo-relative when possible (marker consumed by the bridge); fall back
            // to absolute for sandboxed/out-of-tree backlog dirs.
            val relPath = issue.file.relativeToOrNull(File(".").absoluteFile)
                ?.invariantSeparatorsPath ?: issue.file.invariantSeparatorsPath
            val description = buildString {
                append("<!-- mazewall:backlog-file=$relPath -->\n")
                append("**Backlog file:** `$relPath`\n\n---\n\n")
                if (!issue.context.isNullOrBlank()) {
                    append("**Context:**\n${issue.context}\n\n")
                }
                if (!issue.needed.isNullOrBlank()) {
                    append("**Needed:**\n${issue.needed}\n\n")
                }
                if (!issue.severity.isNullOrBlank()) append("**Severity:** ${issue.severity}\n")
                if (!issue.component.isNullOrBlank()) append("**Component:** ${issue.component}\n")
            }

            val payload = buildString {
                append("{\"title\":\"${jsonEscape(issue.title)}\",")
                append("\"description\":\"${jsonEscape(description)}\",")
                append("\"priority\":\"${issue.priority.name.lowercase()}\",")
                append("\"status\":\"backlog\"")
                if (blockedBy.isNotEmpty()) {
                    append(",\"blockedByIssueIds\":[")
                    append(blockedBy.joinToString(",") { "\"$it\"" })
                    append("]")
                }
                append("}")
            }

            val res = httpRequest(
                "POST", "$apiUrl/api/companies/$resolvedCompanyId/issues", apiKey, payload
            )
            if (res.status !in 200..299) {
                System.err.println("ERROR: POST failed for ${issue.id} (HTTP ${res.status}): " +
                        res.body.take(300))
                failed++
                continue
            }
            val newId = Regex("\"id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"").find(res.body)
                ?.groupValues?.get(1)
            if (newId == null) {
                System.err.println("ERROR: Created ${issue.id} but response has no id; " +
                        "not writing back frontmatter.")
                failed++
                continue
            }
            writeBackPaperclipId(issue.file, newId)
            paperclipIdByBacklogId[issue.id] = newId
            created++
            println("Created ${issue.id} -> $newId (${issue.title})")
        }

        // 5. Force mode: refresh description/priority on already-synced issues
        //    (frontmatter stays authoritative).
        if (forceFlag && !dryRunFlag) {
            for (issue in backlogIssues) {
                val pcId = paperclipIdByBacklogId[issue.id] ?: continue
                val res = httpRequest(
                    "PATCH", "$apiUrl/api/issues/$pcId", apiKey,
                    "{\"description\":\"${jsonEscape("<!-- mazewall:backlog-file=" +
                            issue.file.invariantSeparatorsPath + " -->")}\"}"
                )
                println("[FORCE] PATCH ${issue.id} -> HTTP ${res.status}")
            }
        }

        if (deferred.isNotEmpty()) {
            println("\nDeferred (${deferred.size}) — waiting for blockers to be pushed:")
            for ((issue, missing) in deferred) {
                println("  - ${issue.id} '${issue.title}' (missing: ${missing.joinToString(", ")})")
            }
        }
        println("\nSync complete: created=$created failed=$failed deferred=${deferred.size}")
    } finally {
        // Release lock
        releaseLock(lockDir)
        println("Lock released")
    }
}

private fun writeBackPaperclipId(file: File, paperclipId: String) {
    val content = file.readText()
    val updated = if (content.contains("paperclip_issue_id:")) {
        content.replace(Regex("paperclip_issue_id:\\s*.*"), "paperclip_issue_id: $paperclipId")
    } else {
        // Insert as the last line of the frontmatter block.
        val end = content.indexOf("\n---", 3)
        require(end != -1) { "No frontmatter terminator in ${file.name}" }
        content.substring(0, end) + "\npaperclip_issue_id: $paperclipId" + content.substring(end)
    }
    file.writeText(updated)
}

private fun tryAcquireLock(lockDir: File, timeoutSeconds: Long): Boolean {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < timeoutSeconds * 1000) {
        if (lockDir.mkdir()) {
            return true
        }
        Thread.sleep(100)
    }
    return false
}

private fun releaseLock(lockDir: File) {
    lockDir.delete()
}

fun printUsage() {
    println("Usage: kotlin scripts/paperclip_backlog_sync.kts [OPTIONS]")
    println()
    println("Options:")
    println("  --dry-run, -n    Don't actually create issues")
    println("  --force, -f      Re-sync even if issues already exist")
    println()
    println("Environment Variables:")
    println("  PAPERCLIP_API_KEY    Required: Paperclip API key")
    println("  PAPERCLIP_API_URL    Paperclip API base URL (default: $DEFAULT_API_URL)")
    println("  PAPERCLIP_COMPANY_ID Paperclip company ID (required in non-dry-run mode)")
    println("  BACKLOG_DIR          Backlog directory (default: $DEFAULT_BACKLOG_DIR)")
    println("  LOCKFILE             Lock file path (default: $DEFAULT_LOCKFILE)")
    println("  DRY_RUN              Set to true for dry-run mode")
    println("  FORCE               Set to true to force re-sync")
}

// Kotlin 2.x script runners do not auto-invoke a declared main(); call it
// explicitly with the implicit script argument array.
main(args)
