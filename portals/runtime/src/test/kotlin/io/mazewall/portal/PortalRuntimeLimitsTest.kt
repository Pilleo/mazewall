package io.mazewall.portal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PortalRuntimeLimitsTest {
    @Test
    fun `finite admission remains held until its lease ends`() {
        val limits = PortalRuntimeLimits(workers = 1, maxAdmissions = 1, maxRequestBytes = 16, maxResponseBytes = 16, maxCapabilities = 1)
        val admission = PortalCallAdmission(limits)
        val active = admission.enter(8, 0)
        val rejected = assertFailsWith<PortalCallException> { admission.enter(8, 0) }
        assertEquals(PortalInvocationEvidence.NOT_STARTED, rejected.evidence)
        active.close()
        active.close()
        admission.enter(8, 0).close()
    }

    @Test
    fun `oversized request or capability count rejects before admission and transport`() {
        val admission = PortalCallAdmission(PortalRuntimeLimits(maxRequestBytes = 8, maxCapabilities = 1))
        assertEquals(PortalInvocationEvidence.NOT_STARTED, assertFailsWith<PortalCallException> { admission.enter(9, 0) }.evidence)
        assertEquals(PortalInvocationEvidence.NOT_STARTED, assertFailsWith<PortalCallException> { admission.enter(8, 2) }.evidence)
        admission.enter(8, 1).close()
    }

    @Test
    fun `invalid capacity deadlines and protocol caps cannot create a runtime contract`() {
        assertFailsWith<IllegalArgumentException> { PortalRuntimeLimits(workers = 0) }
        assertFailsWith<IllegalArgumentException> { PortalRuntimeLimits(maxAdmissions = 0) }
        assertFailsWith<IllegalArgumentException> { PortalRuntimeLimits(callTimeoutMs = 0L) }
        assertFailsWith<IllegalArgumentException> { PortalRuntimeLimits(maxResponseBytes = PortalFrame.MAX_PAYLOAD + 1) }
        assertFailsWith<IllegalArgumentException> { PortalRuntimeLimits(maxCapabilities = PortalFrame.MAX_FDS + 1) }
    }
}
