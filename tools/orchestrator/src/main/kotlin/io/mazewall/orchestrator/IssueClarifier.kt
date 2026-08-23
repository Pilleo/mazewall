package io.mazewall.orchestrator

import java.io.File

fun interface ChatModel {
    fun complete(system: String, user: String): String
}

object IssueClarifier {
    const val DEFAULT_WEAK_ROUNDS = 3
    const val DEFAULT_REVIEW_LOOPS = 2

    fun tryClarify(
        draft: IssueScaffoldResult,
        repoRoot: File,
        scratchDir: File,
        weak: ChatModel?,
        strong: ChatModel?,
        warn: (String) -> Unit = { System.err.println(it) },
        maxWeakRounds: Int = DEFAULT_WEAK_ROUNDS,
        maxReviewLoops: Int = DEFAULT_REVIEW_LOOPS,
        impactScanner: ImpactScanner = FilesystemImpactScanner(repoRoot),
    ): IssueScaffoldResult {
        var current = draft
        fun commit(request: IssueScaffoldRequest): IssueScaffoldResult {
            val files = (current.files + request.explicitFiles).distinct()
            val next = current.copy(request = request, files = files)
            return next.copy(markdown = reRender(next, request))
        }

        val structural = IssueMarkdownVerifier.structural(current.markdown, scratchDir)
        if (structural.isNotEmpty()) {
            warn("clarify: draft failed structural check: ${structural.joinToString("; ")}")
        }

        if (weak == null) {
            warn("clarify: weak ACP skipped (set ISSUE_CLARIFY_ACP, e.g. 'agy --acp')")
        } else {
            try {
                val authored = commit(authorWithWeak(current, repoRoot, weak))
                val errors = IssueMarkdownVerifier.readyForReview(authored.markdown, scratchDir)
                if (errors.isEmpty()) {
                    current = authored
                } else {
                    warn("clarify: weak ACP author failed verification, keeping draft: ${errors.joinToString("; ")}")
                }
            } catch (e: Exception) {
                warn("clarify: weak ACP author skipped: ${e.message ?: e::class.java.simpleName}")
            }

            val authoredReady = IssueMarkdownVerifier.readyForReview(current.markdown, scratchDir).isEmpty()
            if (authoredReady) {
                try {
                    val hits = impactScanner.scan(
                        impactSymbols(current.request, current.files),
                        current.files,
                    )
                    val declared = current.request.hasSideEffects
                    when {
                        declared == false && hits.isNotEmpty() -> {
                            warn("clarify: has_side_effects=false but AST found ${hits.size} external references")
                            current = commit(
                                current.request.copy(
                                    investigationPoints = (
                                        current.request.investigationPoints +
                                            "AST found ${hits.size} external refs while has_side_effects=false"
                                        ).distinct(),
                                    sideEffectImpacts = (current.request.sideEffectImpacts + hits.map { it.render() })
                                        .distinct(),
                                ),
                            )
                        }

                        declared == true || hits.isNotEmpty() -> {
                            current = withImpactHits(current, hits)
                            val dug = commit(investigateSideEffectsWithWeak(current, repoRoot, weak, hits))
                            val errors = IssueMarkdownVerifier.readyForReview(dug.markdown, scratchDir)
                            current = if (errors.isEmpty()) dug else {
                                warn(
                                    "clarify: weak side-effect investigation failed verification: ${errors.joinToString("; ")}",
                                )
                                current
                            }
                        }
                    }
                } catch (e: Exception) {
                    warn("clarify: side-effect investigation skipped: ${e.message ?: e::class.java.simpleName}")
                }
            }

            var round = 0
            while (current.request.openQuestionItems.isNotEmpty() && round < maxWeakRounds) {
                round++
                try {
                    val dug = commit(investigateWithWeak(current, repoRoot, weak))
                    val errors = IssueMarkdownVerifier.readyForReview(dug.markdown, scratchDir)
                    current = if (errors.isEmpty()) dug else {
                        warn("clarify: weak investigate round $round failed verification: ${errors.joinToString("; ")}")
                        current
                    }
                } catch (e: Exception) {
                    warn("clarify: weak investigate round $round skipped: ${e.message ?: e::class.java.simpleName}")
                    break
                }
            }
        }

        val leftover = current.request.openQuestionItems
        if (leftover.isNotEmpty() && strong != null) {
            try {
                current = commit(answerLeftoversWithStrong(current, strong))
            } catch (e: Exception) {
                warn("clarify: strong leftover-answers skipped: ${e.message ?: e::class.java.simpleName}")
            }
        } else if (leftover.isNotEmpty()) {
            warn("clarify: open questions remain; no strong ACP to resolve them")
        }

        val notReady = IssueMarkdownVerifier.readyForReview(current.markdown, scratchDir)
        if (notReady.isNotEmpty()) {
            warn("clarify: issue not ready for strong review: ${notReady.joinToString("; ")}")
            return commit(current.request.copy(reviewVerdict = "skipped"))
        }

        if (strong == null) {
            warn("clarify: strong ACP final review skipped")
            return commit(current.request.copy(reviewVerdict = "skipped"))
        }

        repeat(maxReviewLoops) { loop ->
            val reviewed = try {
                reviewWithStrong(current, strong)
            } catch (e: Exception) {
                warn("clarify: strong review skipped: ${e.message ?: e::class.java.simpleName}")
                return commit(current.request.copy(reviewVerdict = "skipped"))
            }
            current = commit(reviewed)
            if (reviewed.reviewVerdict == "approved") return current
            if (weak == null || loop == maxReviewLoops - 1) return current
            try {
                current = commit(applyReviewWithWeak(current, repoRoot, weak, reviewed.reviewComments))
            } catch (e: Exception) {
                warn("clarify: weak review-fix skipped: ${e.message ?: e::class.java.simpleName}")
                return current
            }
        }
        return current
    }

    fun withImpactHits(draft: IssueScaffoldResult, hits: List<ImpactHit>): IssueScaffoldResult {
        val lines = hits.map { it.render() }
        val point = if (hits.isEmpty()) {
            "AST identifier scan: 0 hits outside origin files"
        } else {
            "AST identifier scan: ${hits.size} hits outside origin files"
        }
        val request = draft.request.copy(
            hasSideEffects = draft.request.hasSideEffects ?: hits.isNotEmpty(),
            investigationPoints = (draft.request.investigationPoints + point).distinct(),
            sideEffectImpacts = (draft.request.sideEffectImpacts + lines).distinct(),
        )
        val next = draft.copy(request = request)
        return next.copy(markdown = reRender(next, request))
    }

    private fun excerpts(draft: IssueScaffoldResult, repoRoot: File, maxExcerptChars: Int = 4000): String {
        val extraDocs = extraDesignDocs(repoRoot)
        val paths = (draft.files + extraDocs).distinct().take(8)
        return paths.joinToString("\n\n") { path ->
            val file = File(repoRoot, path)
            val body = if (file.isFile) file.readText().take(maxExcerptChars) else "(missing)"
            "### $path\n$body"
        }
    }

    private fun extraDesignDocs(repoRoot: File): List<String> {
        val docsHint = File(repoRoot, "docs/internals/designs")
        if (!docsHint.isDirectory) return emptyList()
        return docsHint.walkTopDown()
            .filter { file -> file.isFile && file.extension.equals("md", ignoreCase = true) }
            .take(3)
            .map { file ->
                repoRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            }
            .toList()
    }

    private fun parseIssuePayload(raw: String, base: IssueScaffoldRequest): IssueScaffoldRequest {
        val context = parseJsonStringField(raw, "context")
        val needed = parseJsonStringField(raw, "needed")
        require(context.isNotBlank() && needed.isNotBlank()) { "ACP returned empty context/needed" }
        val extra = parseStringList(raw, "extra_files").map { PathModules.normalize(it) }
        val parsedSide = parseJsonBooleanField(raw, "has_side_effects")
        val hasSideEffects = when {
            base.hasSideEffects == true -> true
            parsedSide != null -> parsedSide
            else -> base.hasSideEffects
        }
        return base.copy(
            explicitFiles = (base.explicitFiles + extra).distinct(),
            contextBody = context,
            neededBody = needed,
            openQuestionItems = parseStringList(raw, "open_questions"),
            investigationPoints = (base.investigationPoints + parseStringList(raw, "investigation_points"))
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            importantDetails = (base.importantDetails + parseStringList(raw, "important_details"))
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            hasSideEffects = hasSideEffects,
            sideEffectImpacts = (base.sideEffectImpacts + parseStringList(raw, "side_effects"))
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
    }

    private fun authorWithWeak(draft: IssueScaffoldResult, repoRoot: File, weak: ChatModel): IssueScaffoldRequest {
        val raw = weak.complete(
            "You author mazewall backlog issues. Output JSON only. Never suggest silent EPERM/EACCES bypasses.",
            """
            ROLE: author. Fill Context and Needed, collect implementation facts, fix formatting.
            Document investigation_points (what you inspected) and important_details (invariants, APIs, edge cases).
            Needed must be numbered testable steps. Do not leave FILL placeholders.
            Set has_side_effects true if callers, other modules, ABI, tests, or shared types may be affected.
            Put unresolved blockers in open_questions (may be empty).
            Return JSON only:
            {"context":"...","needed":"...","has_side_effects":false,"investigation_points":["..."],"important_details":["..."],"open_questions":[],"extra_files":[],"side_effects":[]}

            Issue:
            ${draft.markdown}

            Excerpts:
            ${excerpts(draft, repoRoot)}
            """.trimIndent(),
        )
        return parseIssuePayload(raw, draft.request)
    }

    private fun investigateWithWeak(draft: IssueScaffoldResult, repoRoot: File, weak: ChatModel): IssueScaffoldRequest {
        val questions = draft.request.openQuestionItems.joinToString("\n") { "- $it" }
        val raw = weak.complete(
            "You investigate mazewall issues in code, design docs, and (if you have tools) the public web. Output JSON only.",
            """
            ROLE: investigator. Answer these open questions by digging in the repo (and the web if needed).
            Record every search as investigation_points. Record answers as important_details.
            Update context/needed if the answers change the plan. Leave only still-unknown items in open_questions.
            Return JSON only:
            {"context":"...","needed":"...","has_side_effects":false,"investigation_points":["..."],"important_details":["..."],"open_questions":[],"extra_files":[],"side_effects":[]}

            Open questions:
            $questions

            Issue:
            ${draft.markdown}

            Excerpts:
            ${excerpts(draft, repoRoot)}
            """.trimIndent(),
        )
        return parseIssuePayload(raw, draft.request)
    }

    private fun investigateSideEffectsWithWeak(
        draft: IssueScaffoldResult,
        repoRoot: File,
        weak: ChatModel,
        hits: List<ImpactHit>,
    ): IssueScaffoldRequest {
        val artifact = if (hits.isEmpty()) {
            "(no external identifier hits; still explain possible ABI/test/kernel impact)"
        } else {
            hits.joinToString("\n") { "- ${it.render()}" }
        }
        val raw = weak.complete(
            "You investigate side effects of a mazewall change. Output JSON only.",
            """
            ROLE: side-effects. The planner said this change has (or may have) side effects.
            Use the AST identifier scan below (compact hits, not a syntax-tree dump) to say
            where and how callers, tests, other modules, or ABI may be affected.
            Record searches as investigation_points and concrete impact as side_effects and important_details.
            Add files that must change to extra_files. Keep has_side_effects true if any impact remains.
            Return JSON only:
            {"context":"...","needed":"...","has_side_effects":true,"investigation_points":["..."],"important_details":["..."],"open_questions":[],"extra_files":[],"side_effects":["..."]}

            AST identifier scan (external hits):
            $artifact

            Issue:
            ${draft.markdown}

            Excerpts:
            ${excerpts(draft, repoRoot)}
            """.trimIndent(),
        )
        return parseIssuePayload(raw, draft.request.copy(hasSideEffects = true))
    }

    private fun answerLeftoversWithStrong(draft: IssueScaffoldResult, strong: ChatModel): IssueScaffoldRequest {
        val questions = draft.request.openQuestionItems.joinToString("\n") { "- $it" }
        val raw = strong.complete(
            "You only answer leftover questions a weaker pass could not resolve. Output JSON only.",
            """
            ROLE: leftover-answers. These questions remain after a weak investigation.
            Answer them if you can; otherwise keep them in open_questions.
            Return JSON only:
            {"context":"...","needed":"...","has_side_effects":false,"investigation_points":["..."],"important_details":["..."],"open_questions":[],"extra_files":[],"side_effects":[]}

            Leftover questions:
            $questions

            Issue:
            ${draft.markdown}
            """.trimIndent(),
        )
        return parseIssuePayload(raw, draft.request)
    }

    private fun applyReviewWithWeak(
        draft: IssueScaffoldResult,
        repoRoot: File,
        weak: ChatModel,
        comments: List<String>,
    ): IssueScaffoldRequest {
        val raw = weak.complete(
            "You apply independent review comments to a mazewall backlog issue. Output JSON only.",
            """
            ROLE: review-fix. Apply these review comments. Keep investigation_points, important_details, and side_effects.
            Return JSON only:
            {"context":"...","needed":"...","has_side_effects":false,"investigation_points":["..."],"important_details":["..."],"open_questions":[],"extra_files":[],"side_effects":[]}

            Comments:
            ${comments.joinToString("\n") { "- $it" }}

            Issue:
            ${draft.markdown}

            Excerpts:
            ${excerpts(draft, repoRoot)}
            """.trimIndent(),
        )
        return parseIssuePayload(raw, draft.request.copy(reviewComments = emptyList(), reviewVerdict = null))
    }

    private fun reviewWithStrong(draft: IssueScaffoldResult, strong: ChatModel): IssueScaffoldRequest {
        val raw = strong.complete(
            "You are an independent final reviewer. You did not write this issue. Output JSON only.",
            """
            ROLE: final-review. Check: no FILL, Needed is testable, investigation is documented,
            important details are present, remaining open_questions are truly unanswerable from the repo,
            has_side_effects is answered, Side effects names where/how if true,
            fail-closed (no silent EPERM bypass).
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
    fun resolve(
        workingDirectory: File = File(System.getProperty("java.io.tmpdir")),
        env: (String) -> String?,
    ): Pair<ChatModel?, ChatModel?> {
        val acp = AcpCommandResolver.resolvePair(env) ?: return null to null
        return AcpChatModel(acp.first, workingDirectory = workingDirectory) to
            AcpChatModel(acp.second, workingDirectory = workingDirectory)
    }
}

internal fun parseJsonBooleanField(raw: String, key: String): Boolean? {
    val obj = parseJsonObject(raw)
    val keyMarker = "\"$key\""
    val keyAt = obj.indexOf(keyMarker)
    if (keyAt < 0) return null
    val colon = obj.indexOf(':', keyAt + keyMarker.length)
    if (colon < 0) return null
    var i = colon + 1
    while (i < obj.length && obj[i].isWhitespace()) i++
    return when {
        obj.startsWith("true", i) -> true
        obj.startsWith("false", i) -> false
        else -> null
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
