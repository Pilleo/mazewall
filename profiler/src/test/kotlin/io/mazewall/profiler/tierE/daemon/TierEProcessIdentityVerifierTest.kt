package io.mazewall.profiler.tierE.daemon

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TierEProcessIdentityVerifierTest {
    private val target = TierERegisteredTarget(
        registrationId = "orders",
        pid = 4242,
        sessionTag = 0x4d5a,
        processIncarnation = 9_876L,
        markerPath = Path.of("/collector/agent/libmazewall_stack_agent.so"),
        markerOffset = 0x20,
        artifactFileName = "orders.mzse",
    )

    @Test
    fun `attachment requires the registered process incarnation`() {
        TierEProcessIdentityVerifier { 9_876L }.requireCurrent(target)

        assertFailsWith<IllegalArgumentException> {
            TierEProcessIdentityVerifier { 9_877L }.requireCurrent(target)
        }
        assertFailsWith<IllegalArgumentException> {
            TierEProcessIdentityVerifier { null }.requireCurrent(target)
        }
    }

    @Test
    fun `terminal check distinguishes exited target from pid reuse`() {
        val exited = TierEProcessIdentityVerifier { null }
        val reused = TierEProcessIdentityVerifier { 9_877L }

        assertEquals(TierEProcessIdentityCheck.EXITED, exited.checkAtTerminal(target))
        assertEquals(TierEProcessIdentityCheck.REUSED, reused.checkAtTerminal(target))
    }
}
