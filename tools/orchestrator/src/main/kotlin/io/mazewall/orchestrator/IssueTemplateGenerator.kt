package io.mazewall.orchestrator

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class IssueScaffoldRequest(
    val title: String,
    val category: String,
    val severity: String,
    val priority: String,
    val component: String?,
    val explicitFiles: List<String>,
    val explicitModules: List<String>,
    val symbols: List<String>,
    val dependencies: List<String>,
    val effort: String = "medium",
    val autonomy: String = "supervised",
    val needsKernel: Boolean = false,
    val openQuestionItems: List<String> = emptyList(),
    val contextBody: String? = null,
    val neededBody: String? = null,
)

data class IssueScaffoldResult(
    val file: File,
    val markdown: String,
    val id: String,
)

class IssueTemplateGenerator(
    private val repoRoot: File,
    private val backlogRoot: File = File(repoRoot, "docs/internals/backlog"),
    private val clock: () -> Instant = Instant::now,
    private val symbolLocator: SymbolLocator = FilesystemSymbolLocator(repoRoot),
) {
    fun write(request: IssueScaffoldRequest): File =
        scaffold(request, write = true).file

    fun scaffold(request: IssueScaffoldRequest, write: Boolean): IssueScaffoldResult {
        val title = request.title.trim()
        require(title.isNotEmpty()) { "title must not be blank" }
        require(request.severity in VALID_SEVERITIES) {
            "severity must be one of $VALID_SEVERITIES (got '${request.severity}')"
        }
        require(request.priority in VALID_PRIORITIES) {
            "priority must be high, medium, or low (got '${request.priority}')"
        }
        require(request.category in VALID_CATEGORIES) {
            "category must be one of $VALID_CATEGORIES (got '${request.category}')"
        }

        val located = symbolLocator.filesForSymbols(request.symbols)
        val files = (request.explicitFiles + located)
            .map { PathModules.normalize(it) }
            .filter { it.isNotBlank() }
            .distinct()
        require(files.isNotEmpty() || request.explicitModules.isNotEmpty()) {
            "need --file, --symbol (matching source), or --module so target_files/target_modules are non-empty"
        }

        val modules = (request.explicitModules + files.mapNotNull { PathModules.moduleFor(it) })
            .distinct()
        require(modules.isNotEmpty()) {
            "could not infer target_modules from files $files; pass --module"
        }
        modules.forEach { module ->
            require(module in VALID_MODULES) { "invalid Gradle module '$module'" }
        }

        val component = request.component?.takeIf { it.isNotBlank() } ?: PathModules.componentFor(modules.first())
        require(component in VALID_COMPONENTS) {
            "component must be one of $VALID_COMPONENTS (got '$component')"
        }

        request.dependencies.forEach { dep ->
            require(dep.isBlank() || knownIssueIds().contains(dep)) {
                "dependency '$dep' does not exist in $backlogRoot"
            }
        }

        val resolvedFiles = files.ifEmpty {
            listOf(placeholderFileFor(modules.first()))
        }

        val slug = slugify(title)
        val (instant, dest) = allocateFile(request.category, slug)
        val id = "issue-" + instant.format(ID)
        val verifyCheap = resolvedFiles.mapNotNull { PathModules.verifyCheapCommand(it) }.distinct()
        val markdown = render(
            idInstant = instant,
            slug = slug,
            request = request.copy(component = component),
            files = resolvedFiles,
            modules = modules,
            verifyCheap = verifyCheap,
            coreLock = resolvedFiles.any { PathModules.isCoreLock(it) },
        )
        if (write) {
            dest.parentFile.mkdirs()
            dest.writeText(markdown)
        }
        return IssueScaffoldResult(file = dest, markdown = markdown, id = id)
    }

    private fun allocateFile(category: String, slug: String): Pair<ZonedDateTime, File> {
        var instant = clock().atZone(ZoneOffset.UTC)
        repeat(120) {
            val name = "issue-${instant.format(ID)}-$slug.md"
            val dest = File(File(backlogRoot, category), name)
            if (!dest.exists()) return instant to dest
            instant = instant.plusSeconds(1)
        }
        error("could not allocate a unique issue filename in $category")
    }

    private fun knownIssueIds(): Set<String> {
        if (!backlogRoot.exists()) return emptySet()
        return backlogRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("issue-") && it.name.endsWith(".md") }
            .mapNotNull { BacklogParser.parseIssueFile(it)?.id }
            .toSet()
    }

    private fun placeholderFileFor(module: String): String = when (module) {
        ":enforcer" -> "enforcer/src/main/kotlin/io/mazewall/Policy.kt"
        ":profiler" -> "profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt"
        ":platform" -> "platform/src/main/kotlin/io/mazewall/core/Syscall.kt"
        ":tools:orchestrator" -> "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogParser.kt"
        ":portal" -> "portal/build.gradle.kts"
        ":portal-codegen" -> "portal-codegen/build.gradle.kts"
        ":portal-worker" -> "portal-worker/build.gradle.kts"
        ":demos:cli-demo" -> "demos/cli-demo/build.gradle.kts"
        ":demos:vulnerable-web-app" -> "demos/vulnerable-web-app/build.gradle.kts"
        ":demos:agent-sandbox-demo" -> "demos/agent-sandbox-demo/build.gradle.kts"
        else -> error("no placeholder file for $module")
    }

    companion object {
        private val ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val VALID_SEVERITIES = setOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "ENHANCEMENT")
        private val VALID_PRIORITIES = setOf("high", "medium", "low")
        private val VALID_CATEGORIES = setOf(
            "code_health", "security", "performance", "testing", "implementation",
        )
        private val VALID_COMPONENTS = setOf(
            "enforcer", "profiler", "orchestrator", "docs", "ci", "testing", "platform",
        )
        private val VALID_MODULES = setOf(
            ":platform",
            ":enforcer",
            ":profiler",
            ":portal",
            ":portal-codegen",
            ":portal-worker",
            ":demos:cli-demo",
            ":demos:vulnerable-web-app",
            ":demos:agent-sandbox-demo",
            ":tools:orchestrator",
        )

        fun slugify(title: String): String {
            val slug = title.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(60)
                .trim('-')
            return slug.ifEmpty { "untitled" }
        }

        fun render(
            idInstant: ZonedDateTime,
            slug: String,
            request: IssueScaffoldRequest,
            files: List<String>,
            modules: List<String>,
            verifyCheap: List<String>,
            coreLock: Boolean,
        ): String {
            val severity = request.severity
            val badge = when (severity) {
                "CRITICAL", "HIGH" -> "🔴"
                "MEDIUM" -> "🟡"
                else -> "🟢"
            }
            return buildString {
                appendLine("---")
                appendLine("title: \"${escapeYaml(request.title.trim())}\"")
                appendLine("severity: \"$severity\"")
                appendLine("status: \"open\"")
                appendLine("priority: ${request.priority}")
                appendYamlList("dependencies", request.dependencies)
                appendLine("component: \"${request.component}\"")
                appendYamlList("target_modules", modules)
                appendYamlList("target_files", files)
                if (request.symbols.isNotEmpty()) {
                    appendYamlList("target_symbols", request.symbols)
                }
                if (verifyCheap.isNotEmpty()) {
                    appendYamlList("verify_cheap", verifyCheap)
                }
                appendLine("needs_kernel: ${request.needsKernel}")
                appendLine("core_lock: $coreLock")
                appendLine("effort: \"${request.effort}\"")
                appendLine("autonomy: \"${request.autonomy}\"")
                val open = request.openQuestionItems.filter { it.isNotBlank() }
                appendLine("open_questions: ${open.isNotEmpty()}")
                appendLine("---")
                appendLine()
                appendLine("# $badge [Severity: $severity]: ${request.title.trim()}")
                appendLine()
                appendLine("**Context:**")
                appendLine(
                    request.contextBody?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "FILL: what is wrong or missing, and why it exists now. Replace this sentence.",
                )
                appendLine()
                appendLine("**Needed:**")
                appendLine(
                    request.neededBody?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "FILL: numbered, testable steps. Replace this sentence.",
                )
                if (open.isNotEmpty()) {
                    appendLine()
                    appendLine("## ❓ Open Questions")
                    open.forEachIndexed { i, q -> appendLine("${i + 1}. $q") }
                }
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).")
                appendLine()
                appendLine("<!-- id: issue-${idInstant.format(ID)}  file: issue-${idInstant.format(ID)}-$slug.md -->")
                appendLine("<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->")
            }
        }

        private fun escapeYaml(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun StringBuilder.appendYamlList(key: String, items: List<String>) {
            if (items.isEmpty()) {
                appendLine("$key: []")
            } else {
                appendLine("$key:")
                items.forEach { appendLine("  - \"${escapeYaml(it)}\"") }
            }
        }
    }
}
