package io.mazewall.orchestrator

import java.io.File

internal object HostFactual {
    private val FILE_TOKEN = Regex("""[\w./\-]+\.(kt|kts|java|md)""")

    fun close(
        questions: List<String>,
        repoRoot: File,
        files: List<String>,
        hits: List<ImpactHit>,
    ): Pair<List<String>, List<String>> {
        val remaining = mutableListOf<String>()
        val details = mutableListOf<String>()
        for (question in questions) {
            if (questionKind(question) == QuestionKind.OPERATOR) {
                remaining += question
                continue
            }
            val answered = answer(question, repoRoot, files, hits)
            if (answered == null) remaining += question else details += answered
        }
        return remaining to details
    }

    private fun answer(
        question: String,
        repoRoot: File,
        files: List<String>,
        hits: List<ImpactHit>,
    ): String? {
        FILE_TOKEN.findAll(question).forEach { match ->
            val resolved = resolveFile(match.value, repoRoot, files)
            if (resolved != null) return "$question → $resolved exists"
        }
        val symbols = hits.map { it.symbol }.distinct()
        for (symbol in symbols) {
            if (!Regex("""\b${Regex.escape(symbol)}\b""").containsMatchIn(question)) continue
            val sample = hits.filter { it.symbol == symbol }.take(3).joinToString("; ") { it.render() }
            if (sample.isNotEmpty()) return "$question → $sample"
        }
        return null
    }

    private fun resolveFile(token: String, repoRoot: File, files: List<String>): String? {
        val n = PathModules.normalize(token)
        val byPath = files.firstOrNull { it == n || it.endsWith("/$n") || File(it).name == File(n).name }
        if (byPath != null) return byPath
        val direct = File(repoRoot, n)
        return if (direct.isFile) n else null
    }
}
