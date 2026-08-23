package io.mazewall.orchestrator

import java.io.File

fun interface ChatModel {
    fun complete(system: String, user: String): String
}

object IssueClarifier {
    fun tryClarify(
        draft: IssueScaffoldResult,
        repoRoot: File,
        scratchDir: File,
        weak: ChatModel?,
        strong: ChatModel?,
        warn: (String) -> Unit = { System.err.println(it) },
    ): IssueScaffoldResult {
        var current = draft
        val structural = IssueMarkdownVerifier.structural(current.markdown, scratchDir)
        if (structural.isNotEmpty()) {
            warn("clarify: draft failed structural check: ${structural.joinToString("; ")}")
        }

        if (weak == null) {
            warn("clarify: weak ACP skipped (set ISSUE_CLARIFY_ACP, e.g. 'agy --acp')")
        } else {
            try {
                val improved = authorWithWeak(current, repoRoot, weak)
                val files = (current.files + improved.explicitFiles).distinct()
                val withFiles = current.copy(request = improved, files = files)
                val markdown = reRender(withFiles, improved)
                val errors = IssueMarkdownVerifier.readyForReview(markdown, scratchDir)
                if (errors.isEmpty()) {
                    current = withFiles.copy(markdown = markdown)
                } else {
                    warn("clarify: weak ACP output failed verification, keeping draft: ${errors.joinToString("; ")}")
                }
            } catch (e: Exception) {
                warn("clarify: weak ACP skipped: ${e.message ?: e::class.java.simpleName}")
            }
        }

        if (strong == null) {
            warn("clarify: strong ACP review skipped (set ISSUE_CLARIFY_STRONG_ACP or ISSUE_CLARIFY_ACP)")
            return current.copy(
                request = current.request.copy(reviewVerdict = "skipped"),
                markdown = reRender(current, current.request.copy(reviewVerdict = "skipped")),
            )
        }
        return try {
            val reviewed = reviewWithStrong(current, strong)
            current.copy(
                request = reviewed,
                markdown = reRender(current, reviewed),
            )
        } catch (e: Exception) {
            warn("clarify: strong ACP review skipped: ${e.message ?: e::class.java.simpleName}")
            val skipped = current.request.copy(reviewVerdict = "skipped")
            current.copy(request = skipped, markdown = reRender(current, skipped))
        }
    }

    private fun authorWithWeak(
        draft: IssueScaffoldResult,
        repoRoot: File,
        weak: ChatModel,
        maxExcerptChars: Int = 4000,
    ): IssueScaffoldRequest {
        val excerpts = draft.files.take(6).joinToString("\n\n") { path ->
            val file = File(repoRoot, path)
            val body = if (file.isFile) file.readText().take(maxExcerptChars) else "(missing)"
            "### $path\n$body"
        }
        val raw = weak.complete(
            "You author mazewall backlog issues. Output JSON only. Never suggest silent EPERM/EACCES bypasses.",
            """
            Improve this issue: fill Context and Needed, collect implementation facts from excerpts, fix formatting.
            Needed must be numbered testable steps. Do not leave FILL placeholders.
            Return JSON only:
            {"context":"...","needed":"...","extra_files":[]}

            Issue:
            ${draft.markdown}

            Excerpts:
            $excerpts
            """.trimIndent(),
        )
        val context = parseJsonStringField(raw, "context")
        val needed = parseJsonStringField(raw, "needed")
        require(context.isNotBlank() && needed.isNotBlank()) { "weak ACP returned empty context/needed" }
        val extra = parseStringList(raw, "extra_files").map { PathModules.normalize(it) }
        return draft.request.copy(
            explicitFiles = (draft.request.explicitFiles + extra).distinct(),
            contextBody = context,
            neededBody = needed,
            openQuestionItems = emptyList(),
        )
    }

    private fun reviewWithStrong(draft: IssueScaffoldResult, strong: ChatModel): IssueScaffoldRequest {
        val raw = strong.complete(
            "You are an independent reviewer. You did not write this issue. Output JSON only.",
            """
            Review this backlog issue on its own. Check: no FILL placeholders, Needed is testable,
            target files match the work, fail-closed (no silent EPERM bypass).
            Return JSON only:
            {"verdict":"approved"|"needs_changes","comments":["..."]}

            Issue:
            ${draft.markdown}
            """.trimIndent(),
        )
        val verdict = parseJsonStringField(raw, "verdict").lowercase().ifBlank { "needs_changes" }
        val comments = parseStringList(raw, "comments")
        val normalized = if (verdict == "approved") "approved" else "needs_changes"
        return draft.request.copy(reviewVerdict = normalized, reviewComments = comments)
    }

    private fun reRender(draft: IssueScaffoldResult, request: IssueScaffoldRequest): String {
        return IssueTemplateGenerator.render(
            idInstant = draft.instant,
            slug = draft.slug,
            request = request,
            files = draft.files,
            modules = draft.modules,
            verifyCheap = draft.verifyCheap,
            coreLock = draft.coreLock,
        )
    }
}

object IssueMarkdownVerifier {
    fun structural(markdown: String, scratchDir: File): List<String> {
        val issue = parse(markdown, scratchDir) ?: return listOf("unparseable markdown")
        val errors = mutableListOf<String>()
        if (issue.title.isBlank()) errors += "missing title"
        if (issue.targetFiles.isEmpty()) errors += "missing target_files"
        if (issue.targetModules.isEmpty()) errors += "missing target_modules"
        if (issue.component.isNullOrBlank()) errors += "missing component"
        return errors
    }

    fun readyForReview(markdown: String, scratchDir: File): List<String> {
        val errors = structural(markdown, scratchDir).toMutableList()
        val issue = parse(markdown, scratchDir) ?: return errors
        if (issue.context.isNullOrBlank() || issue.context.contains("FILL:")) {
            errors += "context still FILL"
        }
        if (issue.needed.isNullOrBlank() || issue.needed.contains("FILL:")) {
            errors += "needed still FILL"
        }
        return errors
    }

    private fun parse(markdown: String, scratchDir: File): BacklogIssue? {
        scratchDir.mkdirs()
        val file = File(scratchDir, "issue-20200101-000001-verify.md")
        file.writeText(markdown)
        return BacklogParser.parseIssueFile(file)
    }
}

internal fun jsonString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return "\"$escaped\""
}

internal fun parseJsonObject(raw: String): String {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    require(start >= 0 && end > start) { "expected JSON object in model output" }
    return raw.substring(start, end + 1)
}

internal fun parseStringList(raw: String, key: String): List<String> {
    val obj = parseJsonObject(raw)
    val keyMarker = "\"$key\""
    val keyAt = obj.indexOf(keyMarker)
    if (keyAt < 0) return emptyList()
    val bracket = obj.indexOf('[', keyAt)
    val close = obj.indexOf(']', bracket)
    if (bracket < 0 || close < 0) return emptyList()
    val inner = obj.substring(bracket + 1, close)
    return Regex("\"((?:\\\\.|[^\"])*)\"")
        .findAll(inner)
        .map { it.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n") }
        .filter { it.isNotBlank() }
        .toList()
}

internal object ClarifyModels {
    fun resolve(env: (String) -> String?): Pair<ChatModel?, ChatModel?> {
        val acp = AcpCommandResolver.resolvePair(env) ?: return null to null
        return AcpChatModel(acp.first) to AcpChatModel(acp.second)
    }
}

internal fun parseJsonStringField(raw: String, key: String): String {
    val obj = parseJsonObject(raw)
    val keyMarker = "\"$key\""
    val keyAt = obj.indexOf(keyMarker)
    require(keyAt >= 0) { "JSON missing $key" }
    val colon = obj.indexOf(':', keyAt + keyMarker.length)
    var i = colon + 1
    while (i < obj.length && obj[i].isWhitespace()) i++
    require(i < obj.length && obj[i] == '"') { "$key was not a string" }
    i++
    val out = StringBuilder()
    while (i < obj.length) {
        val c = obj[i]
        if (c == '\\' && i + 1 < obj.length) {
            val n = obj[i + 1]
            out.append(
                when (n) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    '"' -> '"'
                    '\\' -> '\\'
                    else -> n
                },
            )
            i += 2
            continue
        }
        if (c == '"') break
        out.append(c)
        i++
    }
    return out.toString().trim()
}
