package io.mazewall.profiler.tierE.stress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TierECoverageOracleTest {
    @Test
    fun `every declared path supplies its own frame without visiting another path`() {
        repeat(72) { path ->
            TierECoveragePaths
                .invoke(path) {
                val frames = Throwable().stackTrace.filter {
                    it.className == TierECoveragePaths::class.java.name && it.methodName.matches(Regex("path[0-9]{2}"))
                }
                assertEquals(listOf("path${path.toString().padStart(2, '0')}"), frames.map { it.methodName })
                path
            }.also { assertEquals(path, it) }
        }
    }

    @Test
    fun `declared corpus has exact frequencies and distributed singletons`() {
        val schedule = TierECoverageOracle.schedule(seed = 17)
        assertEquals(75_000, schedule.size)
        val counts = schedule.groupingBy { it }.eachCount()
        repeat(24) { path ->
            assertEquals(3_000, counts[path])
            assertEquals(124, counts[path + 24])
            assertEquals(1, counts[path + 48])
        }
        assertTrue(schedule.first() >= 48)
        assertTrue(schedule.last() >= 48)
        assertEquals(listOf(8, 8, 8), schedule.chunked(25_000).map { chunk -> chunk.count { it >= 48 } })
        assertEquals(schedule, TierECoverageOracle.schedule(seed = 17))
        assertNotEquals(schedule, TierECoverageOracle.schedule(seed = 18))
    }

    @Test
    fun `lost repetitions preserve coverage but a missing singleton does not`() {
        val expected = mapOf(10 to 0, 11 to 0, 12 to 48)
        val repeated = TierECoverageOracle.Observation(10, listOf(0), true, true)
        val singleton = TierECoverageOracle.Observation(12, listOf(48), true, true)
        assertEquals(TierECoverageOracle.Verdict.PASS, TierECoverageOracle.evaluate(expected, listOf(repeated, singleton)).verdict)
        val missing = TierECoverageOracle.evaluate(expected, listOf(repeated))
        assertEquals(TierECoverageOracle.Verdict.INCOMPLETE, missing.verdict)
        assertEquals(setOf(48), missing.missing)
    }

    @Test
    fun `unacknowledged and unresolved candidates do not recover coverage`() {
        val expected = mapOf(10 to 0, 11 to 0)
        val candidate = TierECoverageOracle.Observation(10, listOf(0), true, false)
        assertEquals(emptySet(), TierECoverageOracle.evaluate(expected, listOf(candidate)).recovered)
        assertEquals(emptySet(), TierECoverageOracle.evaluate(expected, listOf(candidate.copy(exact = false, acknowledged = true))).recovered)
        val retry = candidate.copy(operationId = 11, acknowledged = true, elapsedNanos = 20)
        val report = TierECoverageOracle.evaluate(expected, listOf(candidate, retry, retry))
        assertEquals(TierECoverageOracle.Verdict.PASS, report.verdict)
        assertEquals(setOf(0), report.recovered)
        assertEquals(mapOf(0 to 20L), report.firstDiscoveryNanos)
    }

    @Test
    fun `wrong paths syscalls and unexpected identities fail explicitly`() {
        val expected = mapOf(10 to 0)
        val observation = TierECoverageOracle.Observation(10, listOf(1), true, true)
        assertEquals(TierECoverageOracle.Verdict.FAILED, TierECoverageOracle.evaluate(expected, listOf(observation)).verdict)
        assertEquals(TierECoverageOracle.Verdict.FAILED, TierECoverageOracle.evaluate(expected, listOf(observation.copy(actualPaths = listOf(0), syscallMatches = false))).verdict)
        assertEquals(setOf(20), TierECoverageOracle.evaluate(expected, listOf(observation.copy(operationId = 20))).unexpected)
        assertEquals(TierECoverageOracle.Verdict.UNVERIFIED, TierECoverageOracle.evaluate(null, listOf(observation)).verdict)
    }

    @Test
    fun `reduced corpus retains every declared path and rejects invalid sizes`() {
        assertEquals((0 until 72).toSet(), TierECoverageOracle.schedule(2400, 3).toSet())
        assertFailsWith<IllegalArgumentException> { TierECoverageOracle.schedule(71, 3) }
        assertFailsWith<IllegalArgumentException> { TierECoverageOracle.schedule(100, 3) }
    }
}
