package io.mazewall.orchestrator

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CI attention for hybrid-loop PRs. The agent feedback loop is NOT our job:
 * Jules watches its own PRs and the adapter mirrors session activities onto the
 * board. This component only converts GitHub check truth into human-visible
 * signals — a board comment per failing SHA (phone-auditable, doubles as the
 * dedupe store) and a notify hook (Telegram in production).
 *
 * Deliberately stateless: dedupe derives from existing board comments, stuckness
 * from check start timestamps. Restarts cannot re-alert.
 */
data class PrRef(val repo: String, val number: Int)

@Serializable
data class GhCheck(
    val status: String = "",
    val conclusion: String? = null,
    val startedAt: String? = null,
)

@Serializable
data class GhSnapshot(
    @SerialName("headRefOid") val headSha: String? = null,
    val statusCheckRollup: List<GhCheck> = emptyList(),
) {
    private val checks get() = statusCheckRollup

    /** SUCCESS | FAILURE | PENDING | NONE */
    fun classify(): String {
        if (checks.isEmpty()) return "NONE"
        val completed = checks.filter { it.status == "COMPLETED" }
        if (completed.any { it.conclusion == "FAILURE" }) return "FAILURE"
        if (completed.isNotEmpty() && completed.size == checks.size &&
            completed.all { it.conclusion == "SUCCESS" }
        ) return "SUCCESS"
        return "PENDING"
    }

    fun oldestPendingStartedAt(): Instant? =
        checks.filter { it.status != "COMPLETED" || it.conclusion == null }
            .mapNotNull { it.startedAt }
            .mapNotNull { runCatching { Instant.parse(it) }.getOrNull() }
            .minOrNull()
}

/** Seam over the gh CLI; tests substitute canned snapshots. */
interface GhCheckSource {
    fun fetch(repo: String, prNumber: Int): GhSnapshot?
}

class ProcessGhCheckSource : GhCheckSource {
    private val json = Json { ignoreUnknownKeys = true }

    override fun fetch(repo: String, prNumber: Int): GhSnapshot? {
        return runCatching {
            val process = ProcessBuilder(
                "gh", "pr", "view", prNumber.toString(),
                "-R", repo, "--json", "statusCheckRollup,headRefOid",
            ).start()
            val stdout = process.inputStream.bufferedReader().readText()
            val ok = process.waitFor() == 0
            if (!ok) return null
            json.decodeFromString<GhSnapshot>(stdout)
        }.getOrNull()
    }
}/** Narrow seam over board interactions CiWatch needs; fakes substitute in tests. */
interface IssueSignals {
    fun comments(issueId: String): List<PaperclipComment>
    fun comment(issueId: String, body: String)
    fun workProducts(issueId: String): List<PaperclipWorkProduct>
}

class PaperclipIssueSignals(private val client: PaperclipClient) : IssueSignals {
    override fun comments(issueId: String): List<PaperclipComment> = client.listComments(issueId)
    override fun comment(issueId: String, body: String) = client.comment(issueId, body)
    override fun workProducts(issueId: String): List<PaperclipWorkProduct> =
        client.listWorkProducts(issueId)
}

class CiWatch(
    private val signals: IssueSignals,
    private val gh: GhCheckSource,
    private val notify: (String) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val stuckMinutes: Long =
        HybridSupervisor.env("PAPERCLIP_CI_STUCK_MINUTES")?.toLongOrNull() ?: 15L,
) {
    fun tick(issues: List<PaperclipIssue>) {
        for (issue in issues.filter { it.status == "in_review" }) {
            runCatching { attend(issue) }.onFailure {
                // One broken issue must not starve the rest of the board.
                System.err.println("ciWatch: ${issue.identifier}: ${it.message}")
            }
        }
    }

    private fun attend(issue: PaperclipIssue) {
        val pr = findPr(issue) ?: return
        val snapshot = gh.fetch(pr.repo, pr.number) ?: return
        when (snapshot.classify()) {
            "FAILURE" -> signalFailure(issue, pr, snapshot)
            "PENDING" -> signalStuck(issue, pr, snapshot)
            else -> Unit
        }
    }

    private fun signalFailure(issue: PaperclipIssue, pr: PrRef, snapshot: GhSnapshot) {
        val sha = snapshot.headSha.orEmpty().take(SHA_PREFIX)
        if (sha.isEmpty()) return
        val token = "$FAIL_TOKEN$sha"
        if (hasCommentMarker(issue.id, token) != false) return
        signals.comment(
            issue.id,
            "❌ CI failed on $sha — PR #${pr.number} (${pr.repo}).\n<!-- $token -->",
        )
        notify("❌ CI failed ${issue.identifier} on $sha (${pr.repo}#${pr.number})")
    }

    private fun signalStuck(issue: PaperclipIssue, pr: PrRef, snapshot: GhSnapshot) {
        val startedAt = snapshot.oldestPendingStartedAt() ?: return
        val ageMs = nowMs() - startedAt.toEpochMilli()
        if (ageMs <= stuckMinutes * 60_000) return
        val token = "$STUCK_TOKEN${pr.number}"
        if (hasCommentMarker(issue.id, token) != false) return
        signals.comment(
            issue.id,
            "⏳ CI pending >$stuckMinutes min on PR #${pr.number} (${pr.repo}). Check the runner.\n<!-- $token -->",
        )
        notify("⏳ CI stuck pending ${issue.identifier} (${pr.repo}#${pr.number})")
    }

    /**
     * Tri-state: true = marker found, false = definitively absent, null = lookup
     * failed. Callers must treat null as "skip this tick" - assuming absence on a
     * read failure would re-post duplicate comments until reads recover
     * (Codex P2, PR #513).
     */
    private fun hasCommentMarker(issueId: String, needle: String): Boolean? =
        runCatching { signals.comments(issueId) }
            .map { comments -> comments.any { it.body?.contains(needle) == true } }
            .getOrElse { null }

    private fun findPr(issue: PaperclipIssue): PrRef? {
        val candidates = runCatching { signals.workProducts(issue.id) }
            .getOrDefault(emptyList())
            .asSequence()
            .mapNotNull { it.url }
            .plus(issue.description.orEmpty())
        return candidates
            .mapNotNull { PR_URL_RE.find(it) }
            .firstOrNull()
            ?.let { PrRef("${it.groupValues[1]}/${it.groupValues[2]}", it.groupValues[3].toInt()) }
    }

    companion object {
        val PR_URL_RE = Regex("""github\.com/([^/\s"']+)/([^/\s"']+)/pull/(\d+)""")
        const val FAIL_TOKEN = "mazewall-ci-fail:"
        const val STUCK_TOKEN = "mazewall-ci-stuck:"
        const val SHA_PREFIX = 12
    }
}
