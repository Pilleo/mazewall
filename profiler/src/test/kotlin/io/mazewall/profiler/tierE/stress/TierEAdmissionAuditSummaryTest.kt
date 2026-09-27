package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.engine.TierEPreGateAuditCount
import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TierEAdmissionAuditSummaryTest {
    @Test
    fun `versioned low32 checkpoint names exactly the unobserved descriptor`() {
        val count = TierEbpfEngine.ExactAdmissionAuditCount(
            handlerEntered = 0,
            sessionMatched = 0,
            inFlightAdmitted = 0,
            markerAttached = 0,
            taskStorageAvailable = 0,
            activeInvocation = 0,
            generationValidated = 0,
            ringReserved = 0,
            submitted = 0,
            sessionUnavailable = 0,
            wrongTarget = 0,
            inFlightUnavailable = 0,
            markerDetached = 0,
            markerStateUnavailable = 0,
            taskStorageUnavailable = 0,
            inactiveInvocation = 0,
            staleMarkerGeneration = 0,
            ringReservationFailed = 0,
            low32HandlerEntered = 0,
        )
        val result = TierEAdmissionAuditSummary.render(true, mapOf(123 to count))
        assertTrue("exact_admission_audit_contract_version=2" in result)
        assertTrue("exact_admission_audit_expected_operations=1" in result)
        assertTrue("exact_admission_audit_low32_handler_entered=0" in result)
        assertTrue("exact_admission_audit_low32_missing_fd_samples=123:0" in result)
    }

    @Test
    fun `zero-count declared descriptor survives sixteen unrelated repeated samples`() {
        val empty = TierEbpfEngine.ExactAdmissionAuditCount(
            handlerEntered = 0,
            sessionMatched = 0,
            inFlightAdmitted = 0,
            markerAttached = 0,
            taskStorageAvailable = 0,
            activeInvocation = 0,
            generationValidated = 0,
            ringReserved = 0,
            submitted = 0,
            sessionUnavailable = 0,
            wrongTarget = 0,
            inFlightUnavailable = 0,
            markerDetached = 0,
            markerStateUnavailable = 0,
            taskStorageUnavailable = 0,
            inactiveInvocation = 0,
            staleMarkerGeneration = 0,
            ringReservationFailed = 0,
            low32HandlerEntered = 0,
        )
        val counts = (1..16).associateWith { empty.copy(low32HandlerEntered = 2) } + (4742 to empty)
        val report = TierEAdmissionAuditSummary.render(true, counts)
        assertTrue("exact_admission_audit_low32_missing_fd_samples=4742:0" in report)
    }

    @Test
    fun `third diagnostic contract reports raw and main pre-decode target populations separately`() {
        val report = TierEAdmissionAuditSummary.render(
            enabled = true,
            counts = emptyMap(),
            early = TierEbpfEngine.EarlyTargetOpenatCounts(main = 10115, raw = 10123),
        )
        assertTrue("exact_admission_audit_contract_version=3" in report)
        assertTrue("raw_sys_enter_audit_pre_decode_target_openat=10123" in report)
        assertTrue("exact_admission_audit_pre_decode_target_openat=10115" in report)
    }

    @Test
    fun `paired audit rejects a raw descriptor gap even when the main oracle is exact`() {
        val early = TierEbpfEngine.EarlyTargetOpenatCounts(main = 10002, raw = 9995)
        assertTrue(
            !TierEAdmissionAuditSummary.pairedAuditComplete(
            expected = 10000,
                rawObserved = 9993,
                rawMissing = 7,
                exactMissing = 0,
            rawReadFailures = 0,
                exactReadFailures = 0,
                early = early,
        ),
        )
        assertTrue(
            TierEAdmissionAuditSummary.pairedAuditComplete(
            expected = 10000,
                rawObserved = 10000,
                rawMissing = 0,
                exactMissing = 0,
            rawReadFailures = 0,
                exactReadFailures = 0,
            early = TierEbpfEngine.EarlyTargetOpenatCounts(main = 10002, raw = 10002),
        ),
        )
    }

    @Test
    fun `pre session descriptor stages distinguish main delivery boundary from target gate`() {
        val missingBefore = TierEPreGateAuditCount(
            declared = 1,
            rawBeforeSession = 1,
            mainBeforeSession = 0,
            rawAfterSession = 1,
            mainAfterSession = 0,
        )
        val missingAtGate = missingBefore.copy(mainBeforeSession = 1)
        assertTrue("MAIN_PRE_STAGE_ABSENT" in TierEAdmissionAuditSummary.preGateSummary(setOf(4410), mapOf(4410 to missingBefore)))
        assertTrue("MAIN_POST_STAGE_ABSENT" in TierEAdmissionAuditSummary.preGateSummary(setOf(4410), mapOf(4410 to missingAtGate)))
        assertTrue("INCONCLUSIVE" in TierEAdmissionAuditSummary.preGateSummary(setOf(4410), emptyMap()))
    }

    @Test
    fun `mixed population low32 deficit cannot identify a target hash boundary`() {
        val admitted = TierEPreGateAuditCount(
            declared = 1,
            rawBeforeSession = 1,
            mainBeforeSession = 1,
            rawAfterSession = 1,
            mainAfterSession = 1,
        )
        assertTrue(
            "PASS" == TierEAdmissionAuditSummary.preGateSummary(
            setOf(4410),
                mapOf(4410 to admitted),
        ),
        )
    }

    @Test
    fun `seventeen repeated ambient low32 entries cannot hide a high raw missing descriptor`() {
        val admitted = TierEPreGateAuditCount(
            declared = 1,
            rawBeforeSession = 1,
            mainBeforeSession = 1,
            rawAfterSession = 1,
            mainAfterSession = 1,
        )
        val ambient = (1..17).associateWith { admitted }
        val counts = ambient + (7746 to admitted.copy(rawBeforeSession = 0, rawAfterSession = 0))
        val summary = TierEAdmissionAuditSummary.preGateSummary(counts.keys, counts)
        assertTrue("7746:RAW_PRE_STAGE_ABSENT" in summary)
        assertTrue("MAIN_HASH_GATE" !in summary)
    }
}
