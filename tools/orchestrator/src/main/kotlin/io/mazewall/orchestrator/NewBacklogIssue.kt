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
        if (parsed.interactive && !parsed.nonInteractive) {
            request = IssueInterview.complete(
                request = request,
                prompt = ConsoleLinePrompt(),
                askOpenQuestions = parsed.openQuestionsSpecified == null && !parsed.clarify,
                askKernel = !parsed.needsKernelSpecified,
            )
        }
        if (parsed.clarify) {
            val apiKey = System.getenv("XAI_API_KEY") ?: System.getenv("GROK_API_KEY")
            require(!apiKey.isNullOrBlank()) {
                "--clarify needs XAI_API_KEY or GROK_API_KEY"
            }
            val files = (
                request.explicitFiles +
                    FilesystemSymbolLocator(repoRoot).filesForSymbols(request.symbols)
                ).distinct()
            val weakModel = System.getenv("ISSUE_CLARIFY_WEAK_MODEL") ?: "grok-3-mini"
            val strongModel = System.getenv("ISSUE_CLARIFY_STRONG_MODEL") ?: "grok-4"
            request = IssueClarifier.clarify(
                request = request,
                files = files,
                repoRoot = repoRoot,
                weak = XaiChatModel(apiKey, weakModel),
                strong = XaiChatModel(apiKey, strongModel),
            )
        }
        val generator = IssueTemplateGenerator(repoRoot = repoRoot)
        val result = generator.scaffold(request, write = !parsed.dryRun)
        if (parsed.dryRun) {
            print(result.markdown)
        } else {
            System.err.println("Wrote ${repoRoot.toPath().relativize(result.file.toPath())}")
            System.err.println("id: ${result.id}")
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
    val openQuestionsSpecified: Boolean? = null,
    val needsKernelSpecified: Boolean = false,
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
  --interactive               prompt for open questions / kernel / context
  --non-interactive           never prompt (default for agents / non-TTY)
  --clarify                   weak model asks questions, strong model answers them
                              (requires XAI_API_KEY or GROK_API_KEY; leaves open_questions: false)
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
        var openQuestionsSpecified: Boolean? = null
        var root = System.getProperty("user.dir")
        val files = mutableListOf<String>()
        val modules = mutableListOf<String>()
        val symbols = mutableListOf<String>()
        val deps = mutableListOf<String>()
        val openQuestions = mutableListOf<String>()

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
                "--interactive" -> interactive = true
                "--non-interactive" -> nonInteractive = true
                "--clarify" -> clarify = true
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
            ),
            dryRun = dryRun,
            root = root,
            interactive = interactive,
            nonInteractive = nonInteractive,
            clarify = clarify,
            openQuestionsSpecified = openQuestionsSpecified,
            needsKernelSpecified = needsKernelSpecified,
        )
    }
}
