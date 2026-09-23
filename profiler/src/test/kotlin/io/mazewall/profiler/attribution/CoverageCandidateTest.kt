package io.mazewall.profiler.internal.attribution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoverageCandidateTest {
    private fun candidate(
        sessionEpoch: Long = 19802,
        stackTraceId: Long = 7,
        syscallNumber: Int = 257,
        captureFlags: Int = 0,
        markerSequence: Long = 2,
    ) = CoverageCandidate(
        sessionEpoch = SessionEpoch(sessionEpoch),
        canonicalStackTraceId = StackTraceId(stackTraceId),
        syscallNumber = syscallNumber,
        captureFlags = captureFlags,
        representative = CoverageRepresentative(123L, 100, 101, InvocationId(55), markerSequence, listOf(-100, 1, 2, 3, 4, 5)),
        processIncarnation = ProcessIncarnation(999),
    )

    @Test
    fun `candidate exposes canonical edge identity`() {
        val edge = candidate(stackTraceId = 9, syscallNumber = 257, captureFlags = 1).edge()
        assertEquals(UniqueEdgeGate.Edge(StackTraceId(9), 257, 1), edge)
    }

    @Test
    fun `candidate rejects invalid lifecycle identity`() {
        assertFailsWith<IllegalArgumentException> { candidate(sessionEpoch = 0) }
        assertFailsWith<IllegalArgumentException> { candidate(markerSequence = 0) }
        assertFailsWith<IllegalArgumentException> {
            CoverageCandidate(
                sessionEpoch = SessionEpoch(1),
                canonicalStackTraceId = StackTraceId(1),
                syscallNumber = -1,
                captureFlags = 0,
                representative = CoverageRepresentative(1, 1, 1, InvocationId(1), 1, listOf(1, 2, 3, 4, 5, 6)),
                processIncarnation = ProcessIncarnation(1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            candidate().copy(args = listOf(1, 2, 3))
        }
    }
}
