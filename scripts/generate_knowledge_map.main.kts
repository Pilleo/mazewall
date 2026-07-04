#!/usr/bin/env kotlin
import java.io.File
import java.util.regex.Pattern

// Fallback to finding root directory based on current working directory or script path
val rootDir = File(".").absoluteFile.let { dir ->
    if (File(dir, "docs").isDirectory) dir else dir.parentFile
}
val docsDir = File(rootDir, "docs/internals")
val mapsDir = File(docsDir, "maps")
val backlogDir = File(docsDir, "backlog")
val targetMapFile = File(docsDir, "architectural_map.md")

fun parseYamlFrontmatter(file: File): Map<String, Any>? {
    val content = file.readText()
    val pattern = Pattern.compile("^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n", Pattern.DOTALL)
    val matcher = pattern.matcher(content)
    if (!matcher.find()) {
        return null
    }
    val yamlText = matcher.group(1)
    val metadata = mutableMapOf<String, Any>()
    for (line in yamlText.split("\n")) {
        val parts = line.split(":", limit = 2)
        if (parts.size == 2) {
            val key = parts[0].trim()
            val rawVal = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
            if (rawVal.startsWith("[") && rawVal.endsWith("]")) {
                val items = rawVal.removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                    .filter { it.isNotEmpty() }
                metadata[key] = items
            } else {
                metadata[key] = rawVal
            }
        }
    }
    return metadata
}

fun generateMermaidForScope(scopeName: String, designDocs: List<File>, backlogIssues: List<File>): String? {
    val nodes = mutableSetOf<String>()
    val edges = mutableSetOf<String>()
    val clicks = mutableSetOf<String>()

    for (doc in designDocs) {
        val filename = doc.name
        if (filename in listOf("architectural_map.md", "README.md", "documentation_standards.md")) {
            continue
        }

        val meta = parseYamlFrontmatter(doc) ?: continue
        val title = meta["title"] as? String ?: continue
        val docScope = (meta["scope"] as? String)?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""

        if (scopeName != "all" && docScope != scopeName) {
            continue
        }

        val docId = filename.replace(".md", "").replace("-", "_")
        nodes.add("    $docId[\"📄 Design: $title\"]")
        // Link relative from maps/ subdir
        clicks.add("    click $docId \"../$filename\"")

        val targetsRaw = meta["target_files"]
        val targets = when (targetsRaw) {
            is List<*> -> targetsRaw.filterIsInstance<String>()
            is String -> listOf(targetsRaw)
            else -> emptyList()
        }

        for (target in targets) {
            val cleanTarget = File(target).name
            val targetId = cleanTarget.replace(".", "_").replace("-", "_")
            nodes.add("    $targetId[\"💻 Source: $cleanTarget\"]")
            edges.add("    $targetId -->|Governed by| $docId")
        }
    }

    for (issue in backlogIssues) {
        val filename = issue.name
        val meta = parseYamlFrontmatter(issue) ?: continue
        val title = meta["title"] as? String ?: continue
        if (meta["status"] != "open") {
            continue
        }

        val issueScope = (meta["scope"] as? String)?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
        if (scopeName != "all" && issueScope.isNotEmpty() && issueScope != scopeName) {
            continue
        }

        val issueId = filename.replace(".md", "").replace("-", "_")
        val severity = meta["severity"] as? String ?: "MEDIUM"
        nodes.add("    $issueId[\"🔴 Issue: $title ($severity)\"]")
        val relPath = issue.relativeTo(backlogDir).path
        clicks.add("    click $issueId \"../backlog/$relPath\"")

        val issueContent = issue.readText()
        val targetPattern = Pattern.compile("\\*\\*Target( Area)?:\\*\\*\\s*`(.*?)`")
        val targetMatcher = targetPattern.matcher(issueContent)
        if (targetMatcher.find()) {
            val targetPath = targetMatcher.group(2)
            val cleanTarget = File(targetPath).name
            val targetId = cleanTarget.replace(".", "_").replace("-", "_")
            nodes.add("    $targetId[\"💻 Source: $cleanTarget\"]")
            edges.add("    $issueId -->|Affects| $targetId")
        }
    }

    if (nodes.isEmpty()) {
        return null
    }

    val result = mutableListOf("```mermaid", "graph TD")
    result.addAll(nodes.sorted())
    result.addAll(edges.sorted())
    result.addAll(clicks.sorted())
    result.add("```")
    return result.joinToString("\n")
}

fun writeSubMap(scopeName: String, title: String, designDocs: List<File>, backlogIssues: List<File>, description: String): Boolean {
    val mermaid = generateMermaidForScope(scopeName, designDocs, backlogIssues) ?: return false

    mapsDir.mkdirs()
    val outPath = File(mapsDir, "${scopeName}_map.md")
    val content = """# $title

$description

> Auto-generated by `scripts/generate_knowledge_map.main.kts`. Do not edit manually.
> Root map: [architectural_map.md](../architectural_map.md)

<!-- KNOWLEDGE_MAP_START -->

$mermaid

<!-- KNOWLEDGE_MAP_END -->
"""
    outPath.writeText(content)
    println("  Wrote ${outPath.absolutePath}")
    return true
}

fun updateRootIndex(enforcerOk: Boolean, profilerOk: Boolean) {
    if (!targetMapFile.exists()) {
        println("Error: ${targetMapFile.absolutePath} not found.")
        return
    }

    val content = targetMapFile.readText()
    val startMarker = "<!-- KNOWLEDGE_MAP_START -->"
    val endMarker = "<!-- KNOWLEDGE_MAP_END -->"

    val links = mutableListOf<String>()
    if (enforcerOk) {
        links.add("- [enforcer_map.md](maps/enforcer_map.md) — BPF filter, containment, FFM bindings")
    }
    if (profilerOk) {
        links.add("- [profiler_map.md](maps/profiler_map.md) — USER_NOTIF daemon, trace events, iterative profiler")
    }

    val indexSection = listOf(
        "### Sub-Maps (auto-generated per module):",
        "",
        links.joinToString("\n"),
        "",
        "> Each sub-map links design documents to source files and open backlog issues for that scope."
    ).joinToString("\n")

    val newContent = if (content.contains(startMarker) && content.contains(endMarker)) {
        val before = content.substringBefore(startMarker)
        val after = content.substringAfter(endMarker)
        "$before$startMarker\n\n$indexSection\n\n$endMarker$after"
    } else {
        "$content\n\n## Dynamic Knowledge Map Index\n\n$startMarker\n\n$indexSection\n\n$endMarker\n"
    }

    targetMapFile.writeText(newContent)
    println("  Updated root index in ${targetMapFile.absolutePath}")
}

fun main() {
    println("Generating scoped knowledge sub-maps...")
    if (!docsDir.isDirectory) {
        println("Error: docs directory not found at $docsDir")
        System.exit(1)
    }

    val designDocs = docsDir.listFiles()?.filter { it.isFile && it.extension == "md" }?.sortedBy { it.name } ?: emptyList()
    val backlogIssues = backlogDir.walkTopDown().filter { it.isFile && it.name.startsWith("issue-") && it.extension == "md" }.toList().sortedBy { it.name }

    val enforcerOk = writeSubMap(
        scopeName = "enforcer",
        title = "Enforcer Module Knowledge Map",
        designDocs = designDocs,
        backlogIssues = backlogIssues,
        description = "Maps design documents, source files, and open issues for the `:enforcer` module (Seccomp-BPF, Landlock, FFM bindings)."
    )

    val profilerOk = writeSubMap(
        scopeName = "profiler",
        title = "Profiler Module Knowledge Map",
        designDocs = designDocs,
        backlogIssues = backlogIssues,
        description = "Maps design documents, source files, and open issues for the `:profiler` module (USER_NOTIF, strace, iterative Landlock)."
    )

    updateRootIndex(enforcerOk, profilerOk)
    println("Done.")
}

main()
