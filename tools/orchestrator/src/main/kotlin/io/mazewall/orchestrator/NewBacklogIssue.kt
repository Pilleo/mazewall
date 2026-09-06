package io.mazewall.orchestrator

import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contains("-h") || args.contains("--help")) {
        println(IssueCli.USAGE.trimIndent())
        exitProcess(if (args.contains("-h") || args.contains("--help")) 0 else 1)
    }
    try {
        val parsed = IssueCli.parse(args)
        val repoRoot = File(parsed.root).canonicalFile
        var request = parsed.request
        val generator = IssueTemplateGenerator(repoRoot = repoRoot)
        var result = generator.scaffold(request, write = false)
        val hits = FilesystemImpactScanner(repoRoot).scan(
            impactSymbols(result.request, result.files),
            result.files,
        )
        val codannaCallers = if (codannaOnPath()) {
            val pkg = WorkPackage.collect(result.files + result.request.symbols) { cmd ->
                try {
                    val process = ProcessBuilder(listOf("codanna") + cmd)
                        .redirectErrorStream(true)
                        .start()
                    val out = process.inputStream.bufferedReader().readText()
                    process.waitFor()
                    out
                } catch (_: Exception) {
                    ""
                }
            }
            pkg.impact.map { file ->
                WorkPackageCaller(symbol = File(file).nameWithoutExtension, file = file)
            }
        } else {
            emptyList()
        }
        val callers = (hits.map { WorkPackageCaller(it.symbol, it.file) } + codannaCallers).distinct()
        val stages = WorkPackage.decomposeDag(
            title = request.title,
            files = result.files,
            symbols = result.request.symbols,
            callers = callers,
        )

        if (parsed.interactive && !parsed.nonInteractive) {
            request = IssueInterview.complete(
                request = request,
                prompt = ConsoleLinePrompt(),
                askOpenQuestions = parsed.openQuestionsSpecified == null && !parsed.clarify,
                askKernel = !parsed.needsKernelSpecified,
                askSideEffects = parsed.sideEffectsSpecified == null,
                stages = stages,
            )
            result = generator.scaffold(request, write = false)
        }

        val pkg = WorkPackage.fromHits(result.files, result.request.symbols, hits)
        result = result.copy(
            request = result.request.copy(needsKernel = result.request.needsKernel || pkg.kernelTests),
            verifyCheap = pkg.test.ifEmpty { result.verifyCheap },
            coreLock = result.coreLock || pkg.exclusive,
        )
        result = IssueClarifier.enrichWithoutAcp(result, repoRoot, hits)
        result = IssueClarifier.hostFillPlaceholders(result, hits)
        if (stages.size > 1) {
            val mermaid = WorkPackage.formatMermaidDag(stages)
            val updatedDetails = (result.request.importantDetails + "### Work Package DAG\n$mermaid").distinct()
            val nextReq = result.request.copy(importantDetails = updatedDetails)
            result = result.copy(
                request = nextReq,
                markdown = IssueClarifier.reRender(result, nextReq),
            )
        }
        var clarify = parsed.clarify
        val (weak, strong) = if (!parsed.noClarify) {
            ClarifyModels.resolve(repoRoot) { System.getenv(it) }
        } else {
            null to null
        }
        if (weak != null && !parsed.noClarify) {
            clarify = true
        }
        if (clarify && weak != null) {
            val scratch = File(repoRoot, "build/issue-clarify-scratch").apply { mkdirs() }
            try {
                result = IssueClarifier.tryClarify(
                    draft = result,
                    repoRoot = repoRoot,
                    scratchDir = scratch,
                    weak = weak,
                    strong = strong,
                )
            } finally {
                (weak as? AutoCloseable)?.close()
                (strong as? AutoCloseable)?.close()
            }
        }
        if (!parsed.dryRun) {
            result.file.parentFile.mkdirs()
            result.file.writeText(result.markdown)
            if (!parsed.json) {
                System.err.println("Wrote ${repoRoot.toPath().relativize(result.file.toPath())}")
                System.err.println("id: ${result.id}")
                result.request.reviewVerdict?.let { System.err.println("review: $it") }
            }
        }
        if (parsed.json) {
            val stagesJson = stages.joinToString(prefix = "[\n", postfix = "\n  ]", separator = ",\n") { s ->
                """    {
      "stage": ${s.stageNumber},
      "title": "${s.title.replace("\"", "\\\"")}",
      "module": "${s.module}",
      "files": ${s.files.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},
      "dependencies": ${s.dependencies.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}
    }"""
            }
            println(
                """{
  "id": "${result.id}",
  "file": "${repoRoot.toPath().relativize(result.file.toPath())}",
  "stages": $stagesJson
}""",
            )
        } else if (parsed.dryRun) {
            print(result.markdown)
        }
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println(IssueCli.USAGE)
        exitProcess(1)
    }
}

internal data class ParsedCli(
    val request: IssueScaffoldRequest,
    val dryRun: Boolean,
    val root: String,
    val interactive: Boolean = false,
    val nonInteractive: Boolean = false,
    val clarify: Boolean = false,
    val noClarify: Boolean = false,
    val openQuestionsSpecified: Boolean? = null,
    val needsKernelSpecified: Boolean = false,
    val sideEffectsSpecified: Boolean? = null,
    val decompose: Boolean = false,
    val json: Boolean = false,
)

internal object IssueCli {
    const val USAGE = """
Usage: ./scripts/new_backlog_issue.sh --title "..." [options]

Required:
  --title TEXT

Options:
  --category code_health|security|performance|testing|implementation
  --severity CRITICAL|HIGH|MEDIUM|LOW|ENHANCEMENT
  --priority high|medium|low
  --component enforcer|profiler|orchestrator|docs|ci|testing|platform
  --module :enforcer          (repeatable)
  --file path/to/File.kt      (repeatable; also fills target_files)
  --symbol ClassOrFn          (repeatable; walks source for definitions + *Test)
  --dep issue-YYYYMMDD-HHMMSS (repeatable; must already exist)
  --effort small|medium|large|huge
  --autonomy autonomous|supervised
  --needs-kernel / --no-kernel
  --open-question TEXT        (repeatable; sets open_questions: true)
  --no-open-questions         force open_questions: false
  --side-effects              change may impact callers / other modules / ABI / tests
  --no-side-effects           declare no external impact
  --side-effect TEXT          (repeatable; known impact line; implies --side-effects)
  --decompose                 decompose cross-module work packages into a dependency DAG
  --json                      output machine-readable JSON plan to stdout
  --interactive               prompt for open questions / kernel / side effects / context
  --non-interactive           never prompt (default for agents / non-TTY)
  --clarify                   optional ACP loop (never aborts the file).
                              Without ACP (Jules): scan + host-close factuals still run.
                              With ACP: weak author → factual investigate (1 round) →
                              leftover factuals to strong → strong review unless cheap.
                              ISSUE_CLARIFY_ACP='vibe-acp'
                              ISSUE_CLARIFY_STRONG_ACP='...' for a separate reviewer
  --dry-run                   print markdown, do not write
  --root DIR                  repository root (default: cwd)

Agents: pass flags, no TTY. Humans: TTY auto-adds --interactive unless --non-interactive.
"""

    fun parse(args: Array<String>): ParsedCli {
        var title: String? = null
        var category = "code_health"
        var severity = "MEDIUM"
        var priority = "high"
        var component: String? = null
        var effort = "medium"
        var autonomy = "supervised"
        var needsKernel = false
        var needsKernelSpecified = false
        var dryRun = false
        var interactive = false
        var nonInteractive = false
        var clarify = false
        var noClarify = false
        var openQuestionsSpecified: Boolean? = null
        var sideEffectsSpecified: Boolean? = null
        var hasSideEffects: Boolean? = null
        var decompose = false
        var json = false
        var root = System.getProperty("user.dir")
        val files = mutableListOf<String>()
        val modules = mutableListOf<String>()
        val symbols = mutableListOf<String>()
        val deps = mutableListOf<String>()
        val openQuestions = mutableListOf<String>()
        val sideEffects = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "--title" -> title = args.getOrNull(++i)
                "--category" -> category = args.getOrNull(++i) ?: category
                "--severity" -> severity = args.getOrNull(++i) ?: severity
                "--priority" -> priority = args.getOrNull(++i) ?: priority
                "--component" -> component = args.getOrNull(++i)
                "--effort" -> effort = args.getOrNull(++i) ?: effort
                "--autonomy" -> autonomy = args.getOrNull(++i) ?: autonomy
                "--module" -> args.getOrNull(++i)?.let { modules += it }
                "--file" -> args.getOrNull(++i)?.let { files += it }
                "--symbol" -> args.getOrNull(++i)?.let { symbols += it }
                "--dep" -> args.getOrNull(++i)?.let { deps += it }
                "--open-question" -> {
                    args.getOrNull(++i)?.let { openQuestions += it }
                    openQuestionsSpecified = true
                }

                "--needs-kernel" -> {
                    needsKernel = true
                    needsKernelSpecified = true
                }

                "--no-kernel" -> {
                    needsKernel = false
                    needsKernelSpecified = true
                }

                "--no-open-questions" -> openQuestionsSpecified = false
                "--side-effects" -> {
                    hasSideEffects = true
                    sideEffectsSpecified = true
                }

                "--no-side-effects" -> {
                    hasSideEffects = false
                    sideEffectsSpecified = true
                }

                "--side-effect" -> {
                    args.getOrNull(++i)?.let { sideEffects += it }
                    hasSideEffects = true
                    sideEffectsSpecified = true
                }

                "--decompose" -> decompose = true
                "--json" -> json = true
                "--interactive" -> interactive = true
                "--non-interactive" -> nonInteractive = true
                "--clarify" -> clarify = true
                "--no-clarify" -> noClarify = true
                "--dry-run" -> dryRun = true
                "--root" -> root = args.getOrNull(++i) ?: root
                "-h", "--help" -> throw IllegalArgumentException("help")
                else -> throw IllegalArgumentException("unknown argument: $a")
            }
            i++
        }
        val resolvedTitle = title?.trim().orEmpty()
        require(resolvedTitle.isNotEmpty()) { "--title is required" }
        val items = if (openQuestionsSpecified == false) emptyList() else openQuestions
        return ParsedCli(
            request = IssueScaffoldRequest(
                title = resolvedTitle,
                category = category,
                severity = severity.uppercase(),
                priority = priority.lowercase(),
                component = component?.lowercase(),
                explicitFiles = files,
                explicitModules = modules,
                symbols = symbols,
                dependencies = deps,
                effort = effort.lowercase(),
                autonomy = autonomy.lowercase(),
                needsKernel = needsKernel,
                openQuestionItems = items,
                hasSideEffects = hasSideEffects,
                sideEffectImpacts = sideEffects,
            ),
            dryRun = dryRun,
            root = root,
            interactive = interactive,
            nonInteractive = nonInteractive,
            clarify = clarify,
            noClarify = noClarify,
            openQuestionsSpecified = openQuestionsSpecified,
            needsKernelSpecified = needsKernelSpecified,
            sideEffectsSpecified = sideEffectsSpecified,
            decompose = decompose,
            json = json,
        )
    }
}
