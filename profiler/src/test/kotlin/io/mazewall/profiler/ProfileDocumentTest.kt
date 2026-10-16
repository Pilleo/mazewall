package io.mazewall.profiler

import io.mazewall.profiler.api.BillOfBehavior
import io.mazewall.profiler.api.EbpfLoad
import io.mazewall.profiler.api.IncompleteProfileException
import io.mazewall.profiler.api.ProfileDocument
import io.mazewall.profiler.api.ProfileEnvironment
import io.mazewall.profiler.api.ProfileStrategy
import io.mazewall.profiler.api.ProfilingCoverage
import io.mazewall.profiler.api.ProfilingResult
import io.mazewall.profiler.api.StackAttribution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfileDocumentTest {
    @Test
    fun `measured complete coverage round trips with its bill of behavior`() {
        val coverage = completeCoverage()
        val result = ProfilingResult(Unit, BillOfBehavior(opens = setOf("/tmp/profile-document-test/app.conf")), emptyMap(), coverage)

        val document = ProfileDocument.fromJson(result.toProfileDocumentJson())

        assertEquals(1, document.formatVersion)
        assertEquals(result.behavior, document.behavior)
        assertEquals(coverage, document.coverage)
        assertTrue(document.coverage.complete)
    }

    @Test
    fun `dedicated worker policy rejects a legacy completion attestation`() {
        assertFailsWith<IncompleteProfileException> {
            ProfilingResult.dedicatedWorkerPoliciesFromDocument("""{"mazewallComplete": true, "syscalls": []}""")
        }
    }

    @Test
    fun `dedicated worker policy rejects missing plain and incomplete evidence`() {
        val incomplete =
            ProfilingResult(Unit, BillOfBehavior(syscalls = emptySet()), emptyMap(), ProfilingCoverage.absent())
                .toProfileDocumentJson()

        listOf("{}", BillOfBehavior(syscalls = emptySet()).toJson(), incomplete).forEach { input ->
            assertFailsWith<IncompleteProfileException> {
                ProfilingResult.dedicatedWorkerPoliciesFromDocument(input)
            }
        }
    }

    private fun completeCoverage(): ProfilingCoverage =
        ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "test measurement",
            processWide = true,
            observations = emptyList(),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("test", EbpfLoad.Denied("not enabled")),
        )
}
