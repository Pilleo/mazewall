package io.mazewall.orchestrator

import java.io.File
import kotlin.system.exitProcess

data class WorkPackageCaller(
    val symbol: String,
    val file: String,
)

data class WorkPackage(
    val edit: List<String>,
    val impact: List<String>,
    val test: List<String>,
    val exclusive: Boolean,
    val kernelTests: Boolean,
) {
    fun toJson(): String {
        return buildString {
            appendLine("{")
            appendLine("  \"edit\": ${jsonStringArray(edit)},")
            appendLine("  \"impact\": ${jsonStringArray(impact)},")
            appendLine("  \"test\": ${jsonStringArray(test)},")
            appendLine("  \"exclusive\": $exclusive,")
            appendLine("  \"kernel_tests\": $kernelTests")
            append("}")
        }
    }

    companion object {
        fun assemble(
            files: List<String>,
            symbols: List<String> = emptyList(),
            callers: List<WorkPackageCaller> = emptyList(),
        ): WorkPackage {
            val edit = files.map { PathModules.normalize(it) }.filter { it.isNotBlank() }.distinct()
            val impact = callers.map { PathModules.normalize(it.file) }
                .filter { it.isNotBlank() && it !in edit }
                .distinct()
            val allFiles = (edit + impact).distinct()
            val test = allFiles.mapNotNull { PathModules.verifyCheapCommand(it) }.distinct()
            return WorkPackage(
                edit = edit,
                impact = impact,
                test = test,
                exclusive = allFiles.any { PathModules.isCoreLock(it) },
                kernelTests = needsKernel(allFiles),
            )
        }

        fun needsKernel(files: List<String>): Boolean {
            return files.any { path ->
                val n = PathModules.normalize(path).lowercase()
                n.endsWith("/syscall.kt") ||
                    n.endsWith("/platform.kt") ||
                    n.contains("landlock") ||
                    n.contains("seccomp") ||
                    n.contains("/bpf")
            }
        }

        fun collect(
            inputs: List<String>,
            runCodanna: (List<String>) -> String,
        ): WorkPackage {
            val files = linkedSetOf<String>()
            val symbols = linkedSetOf<String>()
            val callers = linkedSetOf<WorkPackageCaller>()
            for (raw in inputs) {
                val token = raw.trim()
                if (token.isEmpty()) continue
                if (looksLikePath(token)) {
                    files += PathModules.normalize(token)
                    symbols += File(token).nameWithoutExtension.removeSuffix("Test")
                } else {
                    symbols += token
                    val described = runCodanna(listOf("retrieve", "describe", token))
                    files += CodannaOutput.parseFiles(described)
                    var found = runCodanna(listOf("mcp", "find_callers", token))
                    var parsedCallers = CodannaOutput.parseCallers(found)
                    if (parsedCallers.isEmpty()) {
                        val ids = CodannaOutput.parseSymbolIds(described)
                            .ifEmpty { CodannaOutput.parseSymbolIds(found) }
                            .take(4)
                        for (id in ids) {
                            found = runCodanna(listOf("mcp", "find_callers", "symbol_id:$id"))
                            parsedCallers = parsedCallers + CodannaOutput.parseCallers(found)
                        }
                    }
                    callers += parsedCallers
                }
            }
            return assemble(files.toList(), symbols.toList(), callers.toList())
        }

        fun fromHits(
            files: List<String>,
            symbols: List<String>,
            hits: List<ImpactHit>,
        ): WorkPackage = assemble(
            files = files,
            symbols = symbols,
            callers = hits.map { WorkPackageCaller(it.symbol, it.file) },
        )
    }
}

internal object CodannaOutput {
    private val AT_FILE = Regex("""at\s+\.?/?([\w./\-]+\.(?:kt|kts|java))(?::\d+)?""")
    private val CALLER = Regex("""<-\s+\w+\s+(\S+)\s+at\s+\.?/?([\w./\-]+\.(?:kt|kts|java))(?::\d+)?""")
    private val SYMBOL_ID = Regex("""symbol_id:(\d+)""")

    fun parseSymbolIds(output: String): List<String> =
        SYMBOL_ID.findAll(output).map { it.groupValues[1] }.distinct().toList()

    fun parseFiles(output: String): List<String> {
        return AT_FILE.findAll(output)
            .map { PathModules.normalize(it.groupValues[1]) }
            .distinct()
            .toList()
    }

    fun parseCallers(output: String): List<WorkPackageCaller> {
        return CALLER.findAll(output)
            .map { match ->
                WorkPackageCaller(
                    symbol = match.groupValues[1],
                    file = PathModules.normalize(match.groupValues[2]),
                )
            }
            .distinct()
            .toList()
    }
}

private fun looksLikePath(token: String): Boolean {
    val n = PathModules.normalize(token)
    return n.contains('/') || n.endsWith(".kt") || n.endsWith(".java") || n.endsWith(".kts")
}

private fun jsonStringArray(items: List<String>): String {
    if (items.isEmpty()) return "[]"
    return items.joinToString(prefix = "[", postfix = "]") { jsonString(it) }
}

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contains("-h") || args.contains("--help")) {
        System.err.println("Usage: ./scripts/code_atlas.sh work-package <SymbolOrFile>…")
        exitProcess(if (args.contains("-h") || args.contains("--help")) 0 else 1)
    }
    if (!codannaOnPath()) {
        System.err.println("codanna not found")
        exitProcess(1)
    }
    val pkg = WorkPackage.collect(args.toList()) { cmd ->
        val process = ProcessBuilder(listOf("codanna") + cmd)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        out
    }
    println(pkg.toJson())
}

internal fun codannaOnPath(path: String? = System.getenv("PATH")): Boolean {
    if (path.isNullOrBlank()) return false
    return path.split(':').any { dir -> File(dir, "codanna").canExecute() }
}
