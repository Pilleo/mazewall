package io.mazewall.orchestrator

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CiWatchTest {

    private class FakeGh(var snapshot: GhSnapshot?) : GhCheckSource {
        var calls = 0
        var lastRepo: String? = null
        var lastNumber: Int? = null
        override fun fetch(repo: String, prNumber: Int): GhSnapshot? {
            calls++
            lastRepo = repo
            lastNumber = prNumber
            return snapshot
        }
    }

    private class FakeBoard : IssueSignals {
        val comments = mutableMapOf<String, MutableList<String>>()
        val workProducts = mutableMapOf<String, List<PaperclipWorkProduct>>()
        val notified = mutableListOf<String>()
        var failCommentsFor: String? = null

        override fun comments(issueId: String): List<PaperclipComment> {
            if (issueId == failCommentsFor) throw RuntimeException("board exploded")
            return (comments[issueId] ?: emptyList()).map { PaperclipComment("c", it) }
        }

        override fun comment(issueId: String, body: String) {
            comments.getOrPut(issueId) { mutableListOf() }.add(body)
            notified.add("comment:${body.take(24)}")
        }

        override fun workProducts(issueId: String): List<PaperclipWorkProduct> =
            workProducts[issueId] ?: emptyList()
    }

    private fun issueInReview(identifier: String = "MAZ-T") = PaperclipIssue(
        id = "i1",
        identifier = identifier,
        title = identifier,
        status = "in_review",
        priority = "medium",
        issueNumber = 1,
    )

    private val prWorkProduct = PaperclipWorkProduct(
        type = "pull_request",
        provider = "github",
        url = "https://github.com/Pilleo/mazewall/pull/123",
        status = "ready_for_review",
    )

    private fun watcher(
        board: FakeBoard,
        gh: FakeGh,
        nowMs: () -> Long = { 1_000_000_000_000L },
    ) = CiWatch(board, gh, notify = { board.notified.add("notify:$it") }, nowMs = nowMs, stuckMinutes = 15)

    private fun failing(sha: String) = GhSnapshot(
        headSha = sha,
        statusCheckRollup = listOf(GhCheck("COMPLETED", "FAILURE")),
    )

    @Test
    fun `failure on new sha writes one board comment and notifies`() {
        val board = FakeBoard()
        board.workProducts["i1"] = listOf(prWorkProduct)
        watcher(board, FakeGh(failing("aaaa11112222"))).tick(listOf(issueInReview()))
        assertEquals(2, board.notified.size) // comment + notify hook
        assertTrue(board.comments["i1"]!!.single().contains("aaaa11112222"))
    }

    @Test
    fun `same sha is silent on later ticks - dedupe from board comments`() {
        val board = FakeBoard()
        board.workProducts["i1"] = listOf(prWorkProduct)
        val w = watcher(board, FakeGh(failing("aaaa11112222")))
        w.tick(listOf(issueInReview()))
        val writesAfterFirst = board.notified.size
        w.tick(listOf(issueInReview()))
        assertEquals(writesAfterFirst, board.notified.size)
    }

    @Test
    fun `new failing sha re-alerts`() {
        val board = FakeBoard()
        board.workProducts["i1"] = listOf(prWorkProduct)
        val gh = FakeGh(failing("aaaa11112222"))
        val w = watcher(board, gh)
        w.tick(listOf(issueInReview()))
        gh.snapshot = failing("bbbb33334444")
        w.tick(listOf(issueInReview()))
        assertEquals(4, board.notified.size)
        assertTrue(board.comments["i1"]!!.last().contains("bbbb33334444"))
    }

    @Test
    fun `stuck pending alerts exactly once via marker comment`() {
        val started = Instant.ofEpochMilli(1_000_000_000_000L).minusSeconds(16 * 60).toString()
        val board = FakeBoard()
        board.workProducts["i1"] = listOf(prWorkProduct)
        val gh = FakeGh(
            GhSnapshot(
                headSha = "cccc55556666",
                statusCheckRollup = listOf(GhCheck("IN_PROGRESS", null, startedAt = started)),
            ),
        )
        val fixedNow = 1_000_000_000_000L + 60_000 // 1 min after tick start; pending age ~17min
        val w = watcher(board, gh, nowMs = { fixedNow })
        w.tick(listOf(issueInReview()))
        val afterFirst = board.notified.size
        assertEquals(2, afterFirst)
        w.tick(listOf(issueInReview()))
        assertEquals(afterFirst, board.notified.size, "stuck alert must fire once")
        assertTrue(board.comments["i1"]!!.any { it.contains(CiWatch.STUCK_TOKEN) && it.contains("PR #123") })
    }

    @Test
    fun `young pending stays silent`() {
        val started = Instant.ofEpochMilli(1_000_000_000_000L - 2 * 60_000).toString()
        val board = FakeBoard()
        board.workProducts["i1"] = listOf(prWorkProduct)
        val gh = FakeGh(
            GhSnapshot(
                headSha = "cccc55556666",
                statusCheckRollup = listOf(GhCheck("IN_PROGRESS", null, startedAt = started)),
            ),
        )
        watcher(board, gh).tick(listOf(issueInReview()))
        assertTrue(board.notified.isEmpty())
    }

    @Test
    fun `classifier edges`() {
        fun classify(vararg checks: GhCheck) =
            GhSnapshot("s", checks.toList()).classify()
        assertEquals("NONE", GhSnapshot("s", emptyList()).classify())
        assertEquals("SUCCESS", classify(GhCheck("COMPLETED", "SUCCESS")))
        assertEquals("FAILURE", classify(GhCheck("COMPLETED", "FAILURE")))
        assertEquals("FAILURE", classify(GhCheck("COMPLETED", "SUCCESS"), GhCheck("COMPLETED", "FAILURE")))
        assertEquals("PENDING", classify(GhCheck("COMPLETED", "TIMED_OUT")))
        assertEquals("PENDING", classify(GhCheck("IN_PROGRESS", null)))
        assertEquals("PENDING", classify(GhCheck("COMPLETED", "SUCCESS"), GhCheck("QUEUED", null)))
    }

    @Test
    fun `gh failure degrades silently and keeps watching`() {
        val board = FakeBoard()
        board.workProducts["i1"] = listOf(prWorkProduct)
        val gh = FakeGh(null)
        val w = watcher(board, gh)
        w.tick(listOf(issueInReview()))
        w.tick(listOf(issueInReview()))
        assertTrue(board.notified.isEmpty())
        assertEquals(2, gh.calls)
    }

    @Test
    fun `pr discovery uses work products then description fallback and skips bare issues`() {
        val board = FakeBoard()
        val viaDescription = PaperclipIssue(
            id = "i2", identifier = "MAZ-D", title = "d", status = "in_review",
            priority = "low", issueNumber = 2,
            description = "see https://github.com/foo/bar/pull/7 for details",
        )
        val bare = issueInReview()
        val gh = FakeGh(failing("dddd99990000"))
        watcher(board, gh).tick(listOf(viaDescription, bare))
        assertEquals("foo/bar", gh.lastRepo)
        assertEquals(7, gh.lastNumber)
        assertTrue(board.comments.containsKey("i2"))
        assertTrue(!board.comments.containsKey(bare.id))
    }

    @Test
    fun `one broken issue does not starve the rest`() {
        val board = FakeBoard()
        board.failCommentsFor = "bad"
        val good = PaperclipIssue(
            id = "good", identifier = "MAZ-GOOD", title = "g", status = "in_review",
            priority = "low", issueNumber = 3,
            description = "https://github.com/foo/bar/pull/9",
        )
        val gh = FakeGh(failing("eeee00001111"))
        watcher(board, gh).tick(listOf(issueInReview(), good))
        assertTrue(board.comments.containsKey("good"), "healthy sibling must still be attended")
        assertEquals("foo/bar", gh.lastRepo)
    }
}
