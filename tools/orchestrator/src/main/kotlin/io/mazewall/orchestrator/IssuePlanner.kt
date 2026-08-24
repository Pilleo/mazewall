package io.mazewall.orchestrator

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Deterministic planning loop used by the scaffold **and** by Jules dispatch.
 * No ACP. Jules consumes the markdown this produces.
 */
object IssuePlanner {
    private val ID_INSTANT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun planFile(file: File, repoRoot: File, write: Boolean = true): String {
        if (!file.isFile) return ""
        val raw = file.readText()
        if (!raw.contains("**Context:**") || !raw.contains("**Needed:**")) return raw
        val parsed = BacklogParser.parseIssueFile(file) ?: return raw
        val request = requestFrom(parsed, raw)
        val files = parsed.targetFiles.ifEmpty { request.explicitFiles }
        val modules = parsed.targetModules.ifEmpty { files.mapNotNull { PathModules.moduleFor(it) }.distinct() }
        val slug = file.name
            .removePrefix("${parsed.id}-")
            .removeSuffix(".md")
            .ifBlank { IssueTemplateGenerator.slugify(parsed.title) }
        val draft = IssueScaffoldResult(
            file = file,
            markdown = raw,
            id = parsed.id,
            request = request,
            files = files,
            modules = modules,
            verifyCheap = files.mapNotNull { PathModules.verifyCheapCommand(it) }.distinct(),
            coreLock = files.any { PathModules.isCoreLock(it) },
            instant = instantFrom(parsed.id),
            slug = slug,
        )
        val hits = FilesystemImpactScanner(repoRoot).scan(impactSymbols(request, files), files)
        val pkg = WorkPackage.fromHits(files, request.symbols, hits)
        val packed = draft.copy(
            request = request.copy(needsKernel = request.needsKernel || pkg.kernelTests),
            verifyCheap = pkg.test.ifEmpty { draft.verifyCheap },
            coreLock = draft.coreLock || pkg.exclusive,
        )
        var planned = IssueClarifier.enrichWithoutAcp(packed, repoRoot, hits)
        planned = IssueClarifier.hostFillPlaceholders(planned, hits)
        if (write && planned.markdown != raw) {
            file.writeText(planned.markdown)
        }
        return planned.markdown
    }

    fun ensureDispatchable(file: File, repoRoot: File): Boolean {
        if (!file.isFile) return true
        val planned = planFile(file, repoRoot, write = true)
        if (planned.contains("FILL:")) return false
        return Regex("""(?m)^\s*1[.\)]""").containsMatchIn(planned)
    }

    internal fun requestFrom(issue: BacklogIssue, raw: String): IssueScaffoldRequest {
        val symbols = yamlStringList(raw, "target_symbols")
        val hasSide = when {
            Regex("""has_side_effects:\s*true""").containsMatchIn(raw) -> true
            Regex("""has_side_effects:\s*false""").containsMatchIn(raw) -> false
            else -> null
        }
        val openQs = issue.openQuestions.orEmpty()
            .lineSequence()
            .map { it.replace(Regex("""^\s*\d+\.\s*"""), "").trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return IssueScaffoldRequest(
            title = issue.title,
            category = categoryFrom(issue.file),
            severity = issue.severity?.uppercase() ?: "MEDIUM",
            priority = issue.priority.name.lowercase().let {
                if (it in setOf("high", "medium", "low")) it else "medium"
            },
            component = issue.component,
            explicitFiles = issue.targetFiles,
            explicitModules = issue.targetModules,
            symbols = symbols,
            dependencies = issue.dependencies,
            effort = issue.effort ?: "medium",
            needsKernel = Regex("""needs_kernel:\s*true""").containsMatchIn(raw),
            openQuestionItems = openQs,
            contextBody = issue.context,
            neededBody = issue.needed,
            hasSideEffects = hasSide,
        )
    }

    private fun categoryFrom(file: File): String {
        val parent = file.parentFile?.name ?: "code_health"
        return if (parent in setOf("code_health", "security", "performance", "testing", "implementation")) {
            parent
        } else {
            "code_health"
        }
    }

    private fun yamlStringList(raw: String, key: String): List<String> {
        val block = Regex("""^$key:\s*\n((?:[ \t]+- .+\n?)+)""", RegexOption.MULTILINE).find(raw)
            ?: return emptyList()
        return block.groupValues[1].lineSequence()
            .map { it.trim().removePrefix("-").trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun instantFrom(id: String): java.time.ZonedDateTime {
        val match = Regex("""issue-(\d{8})-(\d{6})""").find(id)
            ?: Regex("""issue-(\d{8})-(\d{4})""").find(id)
        val stamp = match?.let { it.groupValues[1] + "-" + it.groupValues[2].padEnd(6, '0') }
        return try {
            LocalDateTime.parse(stamp, ID_INSTANT).atZone(ZoneOffset.UTC)
        } catch (_: Exception) {
            java.time.Instant.EPOCH.atZone(ZoneOffset.UTC)
        }
    }
}
