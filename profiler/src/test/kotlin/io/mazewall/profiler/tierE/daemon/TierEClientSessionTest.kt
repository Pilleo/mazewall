package io.mazewall.profiler.tierE.daemon

import kotlin.test.Test
import kotlin.test.assertEquals

class TierEClientSessionTest {
    @Test
    fun `accepted client receives a bounded idle deadline`() {
        assertEquals(30_000, TIER_E_CLIENT_RECEIVE_TIMEOUT_MS)
    }
}
