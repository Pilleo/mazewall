package io.mazewall.orchestrator

import java.io.File

fun interface ChatModel {
    fun complete(system: String, user: String): String
}

internal enum class QuestionKind { FACTUAL, OPERATOR }

internal fun questionKind(question: String): QuestionKind {
    val t = question.trim().lowercase()
    if (t.contains("should we") ||
        t.contains("trade-off") ||
        t.contains("tradeoff") ||
        t.contains("prefer") ||
        t.contains("autonomy")
    ) {
        return QuestionKind.OPERATOR
    }
    if (Regex("""\bor\b""").containsMatchIn(t) && t.contains('?')) {
        return QuestionKind.OPERATOR
    }
    return QuestionKind.FACTUAL
}

internal const val ISSUE_PAYLOAD_SCHEMA =
    """{"context":"","needed":"","has_side_effects":false,"investigation_points":[],"important_details":[],"open_questions":[],"extra_files":[],"side_effects":[]}"""

object IssueClarifier {
    const val DEFAULT_WEAK_ROUNDS = 1
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

        val hits = impactScanner.scan(
            impactSymbols(current.request, current.files),
            current.files,
        )
        current = enrichWithoutAcp(current, repoRoot, hits)

        val weakStateful = weak is AcpChatModel
        if (weak == null) {
            warn("clarify: weak ACP skipped (set ISSUE_CLARIFY_ACP, e.g. 'agy --acp')")
        } else {
            try {
                val authored = commit(authorWithWeak(current, repoRoot, weak, hits))
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
                val declared = current.request.hasSideEffects
                val needSideLlm = ClarifyPolicy.needSideEffectsLlm(declared, hits.size)
                if (needSideLlm) {
                    try {
                        if (declared == false && hits.isNotEmpty()) {
                            warn("clarify: has_side_effects=false but AST found ${hits.size} external references")
                        }
                        val dug = commit(
                            investigateSideEffectsWithWeak(
                                current,
                                repoRoot,
                                weak,
                                hits,
                                includeFullIssue = !weakStateful,
                            ),
                        )
                        val errors = IssueMarkdownVerifier.readyForReview(dug.markdown, scratchDir)
                        current = if (errors.isEmpty()) dug else {
                            warn(
                                "clarify: weak side-effect investigation failed verification: ${errors.joinToString("; ")}",
                            )
                            current
                        }
                    } catch (e: Exception) {
                        warn("clarify: side-effect investigation skipped: ${e.message ?: e::class.java.simpleName}")
                    }
                }
            }

            var round = 0
            while (round < maxWeakRounds) {
                val factual = current.request.openQuestionItems.filter { questionKind(it) == QuestionKind.FACTUAL }
                val operator = current.request.openQuestionItems.filter { questionKind(it) != QuestionKind.FACTUAL }
                if (factual.isEmpty()) break
                round++
                val before = factual.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                try {
                    val scoped = current.copy(request = current.request.copy(openQuestionItems = factual))
                    val dug = commit(investigateWithWeak(scoped, repoRoot, weak, hits, includeFullIssue = !weakStateful))
                    val errors = IssueMarkdownVerifier.readyForReview(dug.markdown, scratchDir)
                    if (errors.isNotEmpty()) {
                        warn("clarify: weak investigate round $round failed verification: ${errors.joinToString("; ")}")
                        break
                    }
                    val nextFactual = dug.request.openQuestionItems.filter { questionKind(it) == QuestionKind.FACTUAL }
                    current = commit(dug.request.copy(openQuestionItems = (nextFactual + operator).distinct()))
                    val after = nextFactual.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    if (ClarifyPolicy.noProgress(before, after)) {
                        warn("clarify: weak investigate round $round made no progress")
                        break
                    }
                } catch (e: Exception) {
                    warn("clarify: weak investigate round $round skipped: ${e.message ?: e::class.java.simpleName}")
                    break
                }
            }
        }

        val leftover = current.request.openQuestionItems
        val factual = leftover.filter { questionKind(it) == QuestionKind.FACTUAL }
        val operator = leftover.filter { questionKind(it) != QuestionKind.FACTUAL }
        val notReady = IssueMarkdownVerifier.readyForReview(current.markdown, scratchDir)
        if (notReady.isNotEmpty()) {
            warn("clarify: issue not ready for strong review: ${notReady.joinToString("; ")}")
            return commit(current.request.copy(reviewVerdict = "skipped"))
        }

        val cheapView = ClarifyView(
            ready = true,
            questions = leftover,
            hitCount = hits.size,
            hasSideEffects = current.request.hasSideEffects,
            maxWeakRounds = maxWeakRounds,
            reviewVerdict = current.request.reviewVerdict,
            needsKernel = current.request.needsKernel,
            coreLock = current.coreLock,
        )
        val reviewWouldSkipWithoutFactual = ClarifyPolicy.skipStrongReview(
            cheapView.copy(questions = operator),
        )

        if (factual.isNotEmpty() && strong != null && reviewWouldSkipWithoutFactual) {
            try {
                val answered = commit(
                    answerLeftoversWithStrong(
                        current.copy(request = current.request.copy(openQuestionItems = factual)),
                        strong,
                    ),
                )
                current = commit(
                    answered.request.copy(
                        openQuestionItems = (answered.request.openQuestionItems + operator).distinct(),
                    ),
                )
            } catch (e: Exception) {
                warn("clarify: strong leftover-answers skipped: ${e.message ?: e::class.java.simpleName}")
            }
            warn("clarify: cheap path skipped strong review")
            return commit(current.request.copy(reviewVerdict = "skipped"))
        }

        if (factual.isEmpty() && ClarifyPolicy.skipStrongReview(cheapView.copy(questions = leftover))) {
            warn("clarify: cheap path skipped strong review")
            return commit(current.request.copy(reviewVerdict = "skipped"))
        }

        if (strong == null) {
            if (factual.isNotEmpty()) {
                warn("clarify: open questions remain; no strong ACP to resolve them")
            }
            warn("clarify: strong ACP final review skipped")
            return commit(current.request.copy(reviewVerdict = "skipped"))
        }

        if (factual.isNotEmpty()) {
            try {
                val merged = leftoversAndReviewWithStrong(
                    current.copy(request = current.request.copy(openQuestionItems = factual)),
                    strong,
                )
                current = commit(
                    merged.copy(openQuestionItems = (merged.openQuestionItems + operator).distinct()),
                )
                if (merged.reviewVerdict == "approved") return current
            } catch (e: Exception) {
                warn("clarify: strong leftover+review skipped: ${e.message ?: e::class.java.simpleName}")
            }
        }

        if (current.request.reviewVerdict == "approved") return current

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
        val declaredFalse = draft.request.hasSideEffects == false
        val request = if (declaredFalse) {
            draft.request.copy(
                investigationPoints = (draft.request.investigationPoints + point + lines).distinct(),
            )
        } else {
            draft.request.copy(
                hasSideEffects = draft.request.hasSideEffects ?: hits.isNotEmpty(),
                investigationPoints = (draft.request.investigationPoints + point).distinct(),
                sideEffectImpacts = (draft.request.sideEffectImpacts + lines).distinct(),
            )
        }
        val next = draft.copy(request = request)
        return next.copy(markdown = reRender(next, request))
    }

    fun enrichWithoutAcp(
        draft: IssueScaffoldResult,
        repoRoot: File,
        hits: List<ImpactHit>,
    ): IssueScaffoldResult {
        var current = if (hits.isNotEmpty() || draft.request.hasSideEffects != null) {
            withImpactHits(draft, hits)
        } else {
            draft
        }
        val (remaining, details) = HostFactual.close(
            questions = current.request.openQuestionItems,
            repoRoot = repoRoot,
            files = current.files,
            hits = hits,
        )
        if (remaining == current.request.openQuestionItems && details.isEmpty()) return current
        val request = current.request.copy(
            openQuestionItems = remaining,
            importantDetails = (current.request.importantDetails + details).distinct(),
            investigationPoints = (current.request.investigationPoints + details.map { "host: $it" }).distinct(),
        )
        current = current.copy(request = request)
        return current.copy(markdown = reRender(current, request))
    }

    fun hostFillPlaceholders(draft: IssueScaffoldResult, hits: List<ImpactHit>): IssueScaffoldResult {
        var req = draft.request
        var changed = false
        if (isIssuePlaceholder(req.contextBody)) {
            val callers = hits.take(8).joinToString("; ") { it.render() }
            req = req.copy(
                contextBody = buildString {
                    append("Change '${draft.request.title}' in ${draft.files.joinToString()}. ")
                    if (callers.isNotEmpty()) append("External identifier hits: $callers. ")
                    append("Deterministic planner (no ACP). Replace if a human writes a tighter Context.")
                },
            )
            changed = true
        }
        if (isIssuePlaceholder(req.neededBody)) {
            req = req.copy(neededBody = hostNeededSteps(draft, hits))
            changed = true
        }
        if (!changed) return draft
        val next = draft.copy(request = req)
        return next.copy(markdown = reRender(next, req))
    }

    internal fun hostNeededSteps(draft: IssueScaffoldResult, hits: List<ImpactHit>): String {
        val steps = mutableListOf<String>()
        var n = 1
        for (file in draft.files) {
            steps += "$n. Change `$file` for '${draft.request.title}'. Fail closed (no silent EPERM/EACCES bypass)."
            n++
        }
        for (caller in hits.map { it.file }.distinct()) {
            val sample = hits.filter { it.file == caller }.take(2)
                .joinToString { "${it.symbol}:${it.line}" }
            steps += "$n. Update caller `$caller` ($sample)."
            n++
        }
        val verify = draft.verifyCheap.ifEmpty {
            draft.modules.map { "./gradlew $it:test" }
        }
        for (cmd in verify) {
            steps += "$n. Run `$cmd`."
            n++
        }
        if (steps.isEmpty()) {
            return "1. Implement '${draft.request.title}'. Fail closed (no silent EPERM/EACCES bypass)."
        }
        return steps.joinToString("\n")
    }

    private fun planningContext(
        draft: IssueScaffoldResult,
        repoRoot: File,
        hits: List<ImpactHit>,
    ): String {
        val hitLines = if (hits.isEmpty()) {
            "(none)"
        } else {
            hits.joinToString("\n") { "- ${it.render()}" }
        }
        return """
            AST identifier scan (external hits):
            $hitLines

            File outlines (signatures only; use fs/read_text_file for bodies):
            ${fileOutlines(repoRoot, draft.files)}
        """.trimIndent()
    }

    private fun parseIssuePayload(raw: String, base: IssueScaffoldRequest): IssueScaffoldRequest {
        val context = parseJsonStringField(raw, "context")
        val needed = parseJsonStringField(raw, "needed")
        require(context.isNotBlank() && needed.isNotBlank()) { "ACP returned empty context/needed" }
        require(!isIssuePlaceholder(context) && !isIssuePlaceholder(needed)) {
            "ACP returned placeholder context/needed"
        }
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

    private fun completeIssuePayload(
        model: ChatModel,
        system: String,
        user: String,
        base: IssueScaffoldRequest,
    ): IssueScaffoldRequest {
        val first = model.complete(system, user)
        return try {
            parseIssuePayload(first, base)
        } catch (firstError: Exception) {
            val second = model.complete(
                system,
                "$user\n\nPrevious output was invalid (${firstError.message}). Return one JSON object only, no ellipsis, do not echo the schema.",
            )
            parseIssuePayload(second, base)
        }
    }

    private fun authorWithWeak(
        draft: IssueScaffoldResult,
        repoRoot: File,
        weak: ChatModel,
        hits: List<ImpactHit>,
    ): IssueScaffoldRequest {
        val system = "You author mazewall backlog issues. Output JSON only. Never suggest silent EPERM/EACCES bypasses."
        val user = """
            ROLE: author. Fill Context and Needed, collect implementation facts, fix formatting.
            Document investigation_points (what you inspected) and important_details (invariants, APIs, edge cases).
            Needed must be numbered testable steps. Do not leave FILL placeholders or ellipsis-only bodies.
            Set has_side_effects true if callers, other modules, ABI, tests, or shared types may be affected.
            Put unresolved blockers in open_questions (may be empty).
            Return JSON only (empty strings/arrays, never ellipsis):
            $ISSUE_PAYLOAD_SCHEMA

            Issue:
            ${draft.markdown}

            ${planningContext(draft, repoRoot, hits)}
        """.trimIndent()
        return completeIssuePayload(weak, system, user, draft.request)
    }

    private fun investigateWithWeak(
        draft: IssueScaffoldResult,
        repoRoot: File,
        weak: ChatModel,
        hits: List<ImpactHit>,
        includeFullIssue: Boolean = true,
    ): IssueScaffoldRequest {
        val questions = draft.request.openQuestionItems.joinToString("\n") { "- $it" }
        return completeIssuePayload(
            weak,
            "You investigate mazewall issues in code, design docs, and (if you have tools) the public web. Output JSON only.",
            """
            ROLE: investigator. Answer these open questions by digging in the repo (and the web if needed).
            Record every search as investigation_points. Record answers as important_details.
            Update context/needed if the answers change the plan. Leave only still-unknown items in open_questions.
            Return JSON only (empty strings/arrays, never ellipsis):
            $ISSUE_PAYLOAD_SCHEMA

            Open questions:
            $questions

            ${issueSection(draft, includeFullIssue)}

            ${planningContext(draft, repoRoot, hits)}
            """.trimIndent(),
            draft.request,
        )
    }

    private fun issueSection(draft: IssueScaffoldResult, includeFullIssue: Boolean): String =
        if (includeFullIssue) {
            "Issue:\n${draft.markdown}"
        } else {
            "Continue this ACP session. Do not repeat the full issue."
        }

    private fun investigateSideEffectsWithWeak(
        draft: IssueScaffoldResult,
        repoRoot: File,
        weak: ChatModel,
        hits: List<ImpactHit>,
        includeFullIssue: Boolean = true,
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
            Return JSON only (empty strings/arrays, never ellipsis):
            $ISSUE_PAYLOAD_SCHEMA

            AST identifier scan (external hits):
            $artifact

            ${issueSection(draft, includeFullIssue)}

            ${planningContext(draft, repoRoot, hits)}
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
            Answer only leftover factual questions. Do not decide operator trade-offs.
            Return JSON only (empty strings/arrays, never ellipsis):
            $ISSUE_PAYLOAD_SCHEMA

            Leftover questions:
            $questions

            Issue:
            ${draft.markdown}
            """.trimIndent(),
        )
        return parseIssuePayload(raw, draft.request)
    }

    private fun leftoversAndReviewWithStrong(draft: IssueScaffoldResult, strong: ChatModel): IssueScaffoldRequest {
        val questions = draft.request.openQuestionItems.joinToString("\n") { "- $it" }
        val raw = strong.complete(
            "You answer leftover factual questions and independently review the issue. Output JSON only.",
            """
            ROLE: leftover-and-review. Answer leftover factual questions if you can.
            Then set verdict approved or needs_changes. Do not decide operator trade-offs.
            Return JSON only:
            {"context":"","needed":"","has_side_effects":false,"investigation_points":[],"important_details":[],"open_questions":[],"extra_files":[],"side_effects":[],"verdict":"approved","comments":[]}

            Leftover factual questions:
            $questions

            Issue:
            ${draft.markdown}
            """.trimIndent(),
        )
        val parsed = parseIssuePayload(raw, draft.request)
        val verdict = parseJsonStringField(raw, "verdict").lowercase().ifBlank { "needs_changes" }
        val comments = parseStringList(raw, "comments")
        val normalized = if (verdict == "approved") "approved" else "needs_changes"
        return parsed.copy(reviewVerdict = normalized, reviewComments = comments)
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
            Return JSON only (empty strings/arrays, never ellipsis):
            $ISSUE_PAYLOAD_SCHEMA

            Comments:
            ${comments.joinToString("\n") { "- $it" }}

            Issue:
            ${draft.markdown}

            ${planningContext(draft, repoRoot, emptyList())}
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
            {"verdict":"approved","comments":[]}

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
    private val NUMBERED_STEP = Regex("""(?m)^\s*1[.\)]""")
    private val HAS_SIDE_EFFECTS = Regex("""has_side_effects:\s*(true|false)""")

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
        if (isIssuePlaceholder(issue.context)) errors += "context still FILL"
        if (isIssuePlaceholder(issue.needed)) errors += "needed still FILL"
        val needed = issue.needed.orEmpty()
        if (!isIssuePlaceholder(needed) && !NUMBERED_STEP.containsMatchIn(needed)) {
            errors += "needed missing numbered steps"
        }
        val hasSide = HAS_SIDE_EFFECTS.find(markdown)
        if (hasSide == null) {
            errors += "has_side_effects unanswered"
        } else if (hasSide.groupValues[1] == "true") {
            val section = markdown.substringAfter("## Side effects", missingDelimiterValue = "")
            val bullets = section.lineSequence().filter { it.trim().startsWith("- ") }.toList()
            if (bullets.isEmpty() || bullets.all { it.contains("FILL:") || isIssuePlaceholder(it.removePrefix("-").trim()) }) {
                errors += "side effects missing"
            }
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

internal fun isIssuePlaceholder(text: String?): Boolean {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return true
    if (t.contains("FILL:")) return true
    val stripped = t.replace(".", "").replace("…", "").trim()
    return stripped.isEmpty()
}

internal fun parseJsonObject(raw: String): String {
    var lastStart = -1
    var lastEnd = -1
    var start = -1
    var depth = 0
    var inString = false
    var escape = false
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (inString) {
            if (escape) {
                escape = false
            } else if (c == '\\') {
                escape = true
            } else if (c == '"') {
                inString = false
            }
            i++
            continue
        }
        when (c) {
            '"' -> inString = true
            '{' -> {
                if (depth == 0) start = i
                depth++
            }

            '}' -> {
                if (depth > 0) depth--
                if (depth == 0 && start >= 0) {
                    lastStart = start
                    lastEnd = i
                    start = -1
                }
            }
        }
        i++
    }
    require(lastStart >= 0 && lastEnd > lastStart) { "expected JSON object in model output" }
    return raw.substring(lastStart, lastEnd + 1)
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
