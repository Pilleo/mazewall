package io.mazewall.tierE.stress

import io.mazewall.tierE.stress.Event
import io.mazewall.tierE.stress.QuietMark
import io.mazewall.tierE.stress.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class StressVerifierTest {

    private fun w(tid: Long, ctx: UInt, s: Long, e: Long) = Window(tid, ctx, s, e)
    private fun q(tid: Long, s: Long) = QuietMark(tid, s)
    private fun e(tid: Long, nr: Int, ctx: UInt, t: Long) = Event(tid, nr, ctx, t)

    @Test
    fun `in-window correct attribution passes`() {
        val r = StressVerifier.verify(
            listOf(e(10, 39, 42u, 1_000)),
            listOf(w(10, 42u, 500, 5_000)),
            emptyList(),
            slackNs = 0,
        )
        assertTrue(r.passed)
        assertEquals(1, r.inWindow)
    }

    @Test
    fun `wrong context inside window is INCORRECT`() {
        val r = StressVerifier.verify(
            listOf(e(10, 39, 7u, 1_000)), // window says 42
            listOf(w(10, 42u, 500, 5_000)),
            emptyList(),
            slackNs = 0,
        )
        assertFalse(r.passed)
        assertEquals(1, r.incorrectCtx)
    }

    @Test
    fun `undeclared ctx for tid is INCORRECT`() {
        val r = StressVerifier.verify(
            listOf(e(10, 39, 999u, 9_999)),
            listOf(w(10, 42u, 500, 5_000)),
            emptyList(),
        )
        assertFalse(r.passed)
        assertEquals(1, r.incorrectCtx)
    }

    @Test
    fun `quiet-leak detection is DISABLED pending clock calibration`() {
        val r = StressVerifier.verify(
            listOf(e(10, 202, 42u, 50_000)),
            emptyList(),
            listOf(q(10, 40_000)),
        )
        assertEquals(0, r.leakAfterQuiet) // disabled: cross-clock comparison unreliable
    }

    @Test
    fun `zero context after quiet mark is a daemon-contract violation`() {
        // The BPF side never emits ctx=0; such a line means the suppression
        // contract broke and must fail the run loudly.
        val r = StressVerifier.verify(
            listOf(e(10, 202, 0u, 50_000)),
            emptyList(),
            listOf(q(10, 40_000)),
        )
        assertFalse(r.passed)
    }

    @Test
    fun `innermost window wins for nested scopes`() {
        val events = listOf(
            e(11, 39, 42u, 1_200), // outer only
            e(11, 39, 7u, 3_000),  // inner active
            e(11, 39, 42u, 6_500), // back to outer
        )
        val windows = listOf(
            w(11, 42u, 1_000, 8_000),
            w(11, 7u, 2_800, 4_500),
        )
        val r = StressVerifier.verify(events, windows, emptyList(), slackNs = 0)
        assertTrue(r.passed, "report=${r.render()} samples=${r.samples}")
        assertEquals(3, r.inWindow)
    }

    @Test
    fun `boundary jitter absorbed by default slack at realistic scale`() {
        val base = 1_000_000_000L
        val r = StressVerifier.verify(
            listOf(e(12, 39, 5u, base - 600_000)), // 0.6 ms before start
            listOf(w(12, 5u, base, base + 100_000_000)),
            emptyList(),
        ) // default 3 ms slack absorbs the jitter
        assertTrue(r.passed)
    }

    @Test
    fun `valid ctx always accepted regardless of timing (set-membership)`() {
        // With set-membership, there are NO timing boundaries — any event
        // with a declared ctx passes regardless of when it occurs.
        val r = StressVerifier.verify(
            listOf(e(12, 39, 5u, 997)), // very early ktime
            listOf(w(12, 5u, 1_000, 2_000)),
            emptyList(),
        )
        assertTrue(r.passed)
    }

    @Test
    fun `distinct tid count reported`() {
        val r = StressVerifier.verify(
            listOf(
                e(1, 39, 100u, 100),
                e(2, 39, 101u, 200),
                e(3, 39, 102u, 300),
            ),
            listOf(
                w(1, 100u, 0, 400),
                w(2, 101u, 0, 400),
                w(3, 102u, 0, 400),
            ),
            emptyList(),
        )
        assertEquals(3, r.distinctTids)
        assertEquals(3, r.inWindow)
    }
}
