package io.mazewall.orchestrator

import java.io.File

fun interface SymbolLocator {
    fun filesForSymbols(symbols: List<String>): List<String>
}

class FilesystemSymbolLocator(
    private val repoRoot: File,
    private val maxFiles: Int = 20,
) : SymbolLocator {
    private val SKIP_DIRS = setOf("build", "bin", ".git", "out", "node_modules")

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

    override fun filesForSymbols(symbols: List<String>): List<String> {
        if (symbols.isEmpty()) return emptyList()
        val found = LinkedHashSet<String>()
        for (symbol in symbols) {
            val declaration = Regex("""\b(?:class|object|interface|enum class|fun interface|fun)\s+$symbol\b""")
            for (rootName in searchRoots) {
                val root = File(repoRoot, rootName)
                if (!root.exists()) continue
                root.walkTopDown()
                    .onEnter { dir -> dir.name !in SKIP_DIRS }
                    .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                    .forEach { file ->
                        if (found.size >= maxFiles) return found.toList()
                        val relative = repoRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                        if (file.nameWithoutExtension == symbol ||
                            file.nameWithoutExtension == "${symbol}Test" ||
                            file.readText().contains(declaration)
                        ) {
                            found.add(relative)
                        }
                    }
            }
        }
        return found.sorted()
    }
}
