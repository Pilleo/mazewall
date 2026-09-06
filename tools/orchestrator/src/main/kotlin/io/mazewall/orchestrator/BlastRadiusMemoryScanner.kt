package io.mazewall.orchestrator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.system.exitProcess

data class MemoryHit(
    val id: String,
    val type: String,
    val title: String,
    val content: String,
    val matchedSymbol: String,
)

data class BlastRadiusReport(
    val rootSymbol: String,
    val impactedSymbols: List<String>,
    val memories: List<MemoryHit>,
) {
    fun toJson(): String {
        val symbolsJson = impactedSymbols.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val memsJson = memories.joinToString(prefix = "[\n", postfix = "\n  ]", separator = ",\n") { m ->
            """    {
      "id": "${m.id}",
      "type": "${m.type}",
      "title": "${m.title.replace("\"", "\\\"")}",
      "matchedSymbol": "${m.matchedSymbol}"
    }"""
        }
        return """{
  "rootSymbol": "$rootSymbol",
  "impactedSymbols": $symbolsJson,
  "memories": $memsJson
}"""
    }
}

object BlastRadiusMemoryScanner {

    private val json = Json { ignoreUnknownKeys = true }

    fun scan(
        rootSymbol: String,
        repoRoot: File = File("."),
        memoryFile: File = File(System.getProperty("user.home"), ".agentmemory/standalone.json"),
        runCodanna: (List<String>) -> String = { cmd -> defaultRunCodanna(cmd, repoRoot) },
    ): BlastRadiusReport {
        val impactedSymbols = extractImpactSymbols(rootSymbol, runCodanna)
        val memories = queryAgentMemory(impactedSymbols, memoryFile)
        return BlastRadiusReport(
            rootSymbol = rootSymbol,
            impactedSymbols = impactedSymbols,
            memories = memories,
        )
    }

    fun extractImpactSymbols(
        rootSymbol: String,
        runCodanna: (List<String>) -> String,
    ): List<String> {
        val output = runCodanna(listOf("mcp", "analyze_impact", rootSymbol, "--json"))
        if (output.isBlank()) return listOf(rootSymbol)

        return try {
            val root = json.parseToJsonElement(output).jsonObject
            val data = root["data"]?.jsonArray ?: return listOf(rootSymbol)
            val symbols = mutableListOf<String>()
            symbols.add(rootSymbol)
            for (element in data) {
                val obj = element.jsonObject
                obj["name"]?.jsonPrimitive?.content?.let { name ->
                    // Strip backticks or spaces if present in Kotlin test signatures
                    val clean = name.trim('`', ' ', '"')
                    if (clean.isNotBlank() && !symbols.contains(clean)) {
                        symbols.add(clean)
                    }
                }
            }
            symbols
        } catch (_: Exception) {
            listOf(rootSymbol)
        }
    }

    fun queryAgentMemory(
        symbols: List<String>,
        memoryFile: File,
    ): List<MemoryHit> {
        if (!memoryFile.exists()) return emptyList()

        val text = try {
            memoryFile.readText()
        } catch (_: Exception) {
            return emptyList()
        }

        return try {
            val root = json.parseToJsonElement(text).jsonObject
            val mems = root["mem:memories"]?.jsonObject ?: return emptyList()
            val hits = mutableMapOf<String, MemoryHit>()

            for ((_, memElement) in mems) {
                val memObj = memElement.jsonObject
                val id = memObj["id"]?.jsonPrimitive?.content ?: continue
                val type = memObj["type"]?.jsonPrimitive?.content ?: "fact"
                val title = memObj["title"]?.jsonPrimitive?.content ?: ""
                val content = memObj["content"]?.jsonPrimitive?.content ?: ""

                val combinedText = "$title $content"
                for (sym in symbols) {
                    if (sym.length >= 3 && combinedText.contains(sym, ignoreCase = true)) {
                        if (!hits.containsKey(id)) {
                            hits[id] = MemoryHit(
                                id = id,
                                type = type,
                                title = title,
                                content = content,
                                matchedSymbol = sym,
                            )
                        }
                    }
                }
            }
            hits.values.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun formatMarkdownReport(report: BlastRadiusReport): String = buildString {
        appendLine("### 🔍 Blast Radius Impact Memory: `${report.rootSymbol}`")
        appendLine()
        appendLine("**Impacted Symbols (${report.impactedSymbols.size}):**")
        appendLine(report.impactedSymbols.joinToString(", ") { "`$it`" })
        appendLine()
        if (report.memories.isEmpty()) {
            appendLine("✅ *No active anti-patterns or warnings registered in agentmemory for this blast radius.*")
        } else {
            appendLine("**Active Invariants & Past Bug Anti-Patterns (${report.memories.size}):**")
            for (mem in report.memories) {
                appendLine("- **[${mem.type.uppercase()}]** `${mem.matchedSymbol}`: **${mem.title}**")
                if (mem.content.isNotBlank()) {
                    appendLine("  > ${mem.content.take(200)}${if (mem.content.length > 200) "..." else ""}")
                }
            }
        }
    }

    private fun defaultRunCodanna(cmd: List<String>, repoRoot: File): String {
        return try {
            val process = ProcessBuilder(listOf("codanna") + cmd)
                .directory(repoRoot)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            out
        } catch (_: Exception) {
            ""
        }
    }
}

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contains("-h") || args.contains("--help")) {
        println(
            """
            Usage: BlastRadiusMemoryScanner <symbol> [--json]
            Scans Codanna's impact graph for downstream symbols and correlates with agentmemory.
            """.trimIndent(),
        )
        exitProcess(0)
    }

    val symbol = args.first { !it.startsWith("-") }
    val asJson = args.contains("--json")
    val repoRoot = File(".").canonicalFile

    val report = BlastRadiusMemoryScanner.scan(symbol, repoRoot = repoRoot)

    if (asJson) {
        println(report.toJson())
    } else {
        println(BlastRadiusMemoryScanner.formatMarkdownReport(report))
    }
}
