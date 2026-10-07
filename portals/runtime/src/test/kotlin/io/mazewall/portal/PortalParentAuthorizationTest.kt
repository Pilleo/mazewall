package io.mazewall.portal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PortalParentAuthorizationTest {
    @Test
    fun `permit requires exact session child and control frame before startup may proceed`() {
        val expected = PortalParentAuthorization("0123456789abcdef0123456789abcdef", 42)
        val frame = expected.frame()
        assertEquals(expected, PortalParentAuthorization.accept(frame, expected.session, 42))
        assertEquals(expected, PortalParentAuthorization.acceptAcknowledgement(expected.acknowledgement(), expected.session, 42))
        assertFailsWith<IllegalArgumentException> { PortalParentAuthorization.acceptAcknowledgement(frame, expected.session, 42) }
        assertFailsWith<IllegalArgumentException> { PortalParentAuthorization.accept(frame, "fedcba9876543210fedcba9876543210", 42) }
        assertFailsWith<IllegalArgumentException> { PortalParentAuthorization.accept(frame, expected.session, 43) }
        assertFailsWith<IllegalArgumentException> { PortalParentAuthorization.accept(PortalFrame(PortalKind.Request, 1, PortalMethod.Echo, frame.payload, 0), expected.session, 42) }
    }

    @Test
    fun `authorization cannot be repeated or resurrected after rejection`() {
        assertEquals(PortalParentGatePhase.AUTHORIZED, PortalParentGate.evaluate(PortalParentGatePhase.WAITING, true))
        assertEquals(PortalParentGatePhase.REJECTED, PortalParentGate.evaluate(PortalParentGatePhase.WAITING, false))
        assertEquals(PortalParentGatePhase.REJECTED, PortalParentGate.evaluate(PortalParentGatePhase.REJECTED, true))
        assertFailsWith<IllegalStateException> { PortalParentGate.evaluate(PortalParentGatePhase.AUTHORIZED, true) }
    }
}
