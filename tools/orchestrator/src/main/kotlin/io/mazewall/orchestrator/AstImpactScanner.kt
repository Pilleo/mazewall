package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit

data class ImpactHit(
    val file: String,
    val line: Int,
    val symbol: String,
    val snippet: String,
) {
    fun render(): String = "$file:$line $symbol  ${snippet.take(160)}"
}

fun interface ImpactScanner {
    fun scan(symbols: List<String>, originFiles: Collection<String>): List<ImpactHit>
}

internal val IMPACT_NOISE = setOf(
    "main",
    "index",
    "package",
    "Test",
    "Companion",
    "kt",
    "java",
)

internal fun fileOutlines(repoRoot: File, files: List<String>, maxLinesPerFile: Int = 40): String {
    return files.take(8).joinToString("\n\n") { path ->
        val file = File(repoRoot, path)
        val lines = if (!file.isFile) {
            listOf("(missing)")
        } else {
            file.readLines()
                .map { it.trimStart() }
                .filter { t ->
                    t.startsWith("class ") ||
                        t.startsWith("object ") ||
                        t.startsWith("interface ") ||
                        t.startsWith("enum ") ||
                        t.startsWith("fun ") ||
                        t.startsWith("fun interface ")
                }
                .map { t ->
                    val brace = t.indexOf('{')
                    if (brace >= 0) t.take(brace).trimEnd() else t
                }
                .take(maxLinesPerFile)
                .ifEmpty { listOf("(no class/fun signatures)") }
        }
        "### $path (outline)\n" + lines.joinToString("\n")
    }
}

internal fun impactSymbols(request: IssueScaffoldRequest, files: List<String>): List<String> {
    val fromFiles = files.map { File(it).nameWithoutExtension.removeSuffix("Test") }
    return (request.symbols + fromFiles)
        .map { it.trim() }
        .filter { name ->
            name.length >= 2 &&
                name[0].isLetter() &&
                name !in IMPACT_NOISE
        }
        .distinct()
}

/**
 * Compact identifier-hit list for planning, not a dumped syntax tree.
 * Prefers `scripts/sg.sh` (ast-grep) when present; otherwise walks Kotlin/Java
 * sources with word-boundary matches. Origin files from the issue are omitted
 * so the artifact is *external* impact.
 */
class FilesystemImpactScanner(
    private val repoRoot: File,
    private val maxHits: Int = 40,
    private val astGrep: ((symbol: String) -> List<ImpactHit>?)? = null,
) : ImpactScanner {
    private val skipDirs = setOf("build", "bin", ".git", "out", "node_modules")
    private val searchRoots = listOf(
        "enforcer",
        "profiler",
        "platform",
        "portal",
        "portal-codegen",
        "portal-worker",
        "tools/orchestrator",
        "demos",
    )

    override fun scan(symbols: List<String>, originFiles: Collection<String>): List<ImpactHit> {
        val origins = originFiles.map { PathModules.normalize(it) }.toSet()
        val found = LinkedHashSet<ImpactHit>()
        for (symbol in symbols) {
            if (found.size >= maxHits) break
            val batch = astGrep?.invoke(symbol) ?: defaultAstGrep(symbol) ?: regexHits(symbol)
            found.addAll(batch)
        }
        return found
            .filter { PathModules.normalize(it.file) !in origins }
            .take(maxHits)
    }

    private fun defaultAstGrep(symbol: String): List<ImpactHit>? {
        val script = File(repoRoot, "scripts/sg.sh")
        if (!script.isFile) return null
        return try {
            val process = ProcessBuilder(script.absolutePath, "run", "--pattern", symbol, "--lang", "kotlin")
                .directory(repoRoot)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            parseSgOutput(output, symbol)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSgOutput(output: String, symbol: String): List<ImpactHit>? {
        if (output.contains("ast-grep binary not found")) return null
        val hits = mutableListOf<ImpactHit>()
        val linePattern = Regex("""^(.+):(\d+):\d+:(.*)$""")
        output.lineSequence().forEach { raw ->
            val match = linePattern.matchEntire(raw.trim()) ?: return@forEach
            val file = PathModules.normalize(match.groupValues[1])
            val line = match.groupValues[2].toIntOrNull() ?: return@forEach
            val snippet = match.groupValues[3].trim()
            hits += ImpactHit(file = file, line = line, symbol = symbol, snippet = snippet)
        }
        return hits
    }

    private fun regexHits(symbol: String): List<ImpactHit> {
        val needle = Regex("""\b${Regex.escape(symbol)}\b""")
        val hits = mutableListOf<ImpactHit>()
        for (rootName in searchRoots) {
            val root = File(repoRoot, rootName)
            if (!root.exists()) continue
            root.walkTopDown()
                .onEnter { dir -> dir.name !in skipDirs }
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    val relative = repoRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                    file.readLines().forEachIndexed { index, line ->
                        if (hits.size >= maxHits) return hits
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                        if (needle.containsMatchIn(line)) {
                            hits += ImpactHit(
                                file = relative,
                                line = index + 1,
                                symbol = symbol,
                                snippet = line.trim(),
                            )
                        }
                    }
                }
        }
        return hits
    }
}
