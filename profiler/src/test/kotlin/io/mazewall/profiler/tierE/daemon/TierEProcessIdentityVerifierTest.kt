package io.mazewall.profiler.tierE.daemon

import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

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

    @Test
    fun `proc stat reports absence separately from observation failures`() {
        assertNull(TierEProcessIdentityVerifier.observeLinuxIncarnation { throw NoSuchFileException("stat") })
        val denied = AccessDeniedException("stat")
        val deniedResult = assertFailsWith<AccessDeniedException> {
            TierEProcessIdentityVerifier.observeLinuxIncarnation { throw denied }
        }
        assertSame(denied, deniedResult)
        val broken = IOException("fixture read failed")
        val brokenResult = assertFailsWith<IOException> {
            TierEProcessIdentityVerifier.observeLinuxIncarnation { throw broken }
        }
        assertSame(broken, brokenResult)
        val fatal = AssertionError("fixture fatal")
        val fatalResult = assertFailsWith<AssertionError> {
            TierEProcessIdentityVerifier.observeLinuxIncarnation { throw fatal }
        }
        assertSame(fatal, fatalResult)
        assertFailsWith<IllegalArgumentException> {
            TierEProcessIdentityVerifier.observeLinuxIncarnation { "42 (malformed) S" }
        }
    }
}
