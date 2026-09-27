package io.mazewall.profiler.tierE.stress

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
}
