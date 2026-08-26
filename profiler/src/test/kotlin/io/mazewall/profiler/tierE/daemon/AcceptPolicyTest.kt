package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.tierE.ffi.Ucred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

internal class AcceptPolicyTest {

    @Test
    fun `root with no active session is accepted`() {
        val d = decideAccept(sessionActive = false, cred = Ucred(100, 0, 0))
        assertIs<AcceptDecision.Accept>(d)
    }

    @Test
    fun `active session rejects second controller with BUSY`() {
        val d = decideAccept(sessionActive = true, cred = Ucred(200, 0, 0))
        assertIs<AcceptDecision.Reject>(d)
        assertEquals("ERR BUSY", (d as AcceptDecision.Reject).reply)
    }

    @Test
    fun `non-root uid rejected`() {
        val d = decideAccept(sessionActive = false, cred = Ucred(300, 1000, 1000))
        assertIs<AcceptDecision.Reject>(d)
        assertEquals("ERR PEER_UID 1000", (d as AcceptDecision.Reject).reply)
    }

    @Test
    fun `null credentials rejected`() {
        val d = decideAccept(sessionActive = false, cred = null)
        assertIs<AcceptDecision.Reject>(d)
        assertEquals("ERR PEER_UID -1", (d as AcceptDecision.Reject).reply)
    }
}
