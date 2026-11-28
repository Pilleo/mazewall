package io.mazewall.profiler.hotspot

import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorProcess
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorTiming
import io.mazewall.profiler.internal.hotspot.HotSpotSessionFailure
import io.mazewall.profiler.internal.hotspot.HotSpotSessionState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HotSpotCollectorProcessTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `real child ready snapshot stop and joined cleanup preserve immutable evidence`() {
        val owner = HotSpotCollectorProcess.attach(HotSpotChildFixture.argv("success", directory), 123, 456uL)
        val partial = owner.snapshot()
        assertFalse(requireNotNull(partial.evidence).finalized)
        val final = owner.finish()
        assertTrue(requireNotNull(final.evidence).finalized)
        assertEquals(1, final.evidence.records.size)
        assertFalse(final.pendingOwnership)
        assertFalse(requireNotNull(partial.evidence).finalized)
        assertEquals(final, owner.finish())
        owner.close()
    }

    @Test
    fun `stderr floods are drained independently and retention remains bounded`() {
        val owner = HotSpotCollectorProcess.attach(HotSpotChildFixture.argv("stderr", directory), 123, 456uL)
        val final = owner.finish()
        assertTrue(final.stderr.length <= 65536)
        assertTrue(final.discardedStderrBytes > 0)
        assertFalse(final.pendingOwnership)
    }

    @Test
    fun `bad generation early exit and native denial fail before attachment returns`() {
        for (mode in listOf("success", "early", "denied")) {
            val failure = assertFailsWith<HotSpotSessionFailure> {
                HotSpotCollectorProcess.attach(HotSpotChildFixture.argv(mode, directory, wrongGeneration = mode == "success"), 123, 456uL)
            }
            assertNotNull(failure.cause)
            assertFalse(failure.owner.snapshot().pendingOwnership)
        }
    }

    @Test
    fun `ready and stop deadlines abort actual owned children`() {
        val timing = HotSpotCollectorTiming(startupNanos = 200_000_000, stopNanos = 200_000_000)
        val startup = assertFailsWith<HotSpotSessionFailure> {
            HotSpotCollectorProcess.attach(HotSpotChildFixture.argv("no-ready", directory), 123, 456uL, timing = timing)
        }
        assertFalse(startup.owner.snapshot().pendingOwnership)
        val owner = HotSpotCollectorProcess.attach(HotSpotChildFixture.argv("hang", directory), 123, 456uL, timing = HotSpotCollectorTiming(stopNanos = 200_000_000))
        val stopped = assertFailsWith<HotSpotSessionFailure> { owner.finish() }
        assertNotNull(stopped.snapshot.evidence)
        assertTrue(stopped.snapshot.pendingOwnership)
        assertFailsWith<HotSpotSessionFailure> { stopped.owner.close() }
        assertFalse(stopped.owner.snapshot().pendingOwnership)
    }

    @Test
    fun `automatic session expiry aborts a hanging collector without caller activity`() {
        val child = CompletableFuture<Process>()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process = RealProcessLauncher.startProcess(args, redirectErrorStream).also { child.complete(it) }
        }
        val owner = HotSpotCollectorProcess.attach(
            HotSpotChildFixture.argv("hang", directory),
            123,
            456uL,
            launcher = launcher,
            timing = HotSpotCollectorTiming(sessionNanos = 2_000_000_000, stopNanos = 500_000_000),
        )

        val exitedWithoutCaller = child.join().waitFor(5, TimeUnit.SECONDS)
        val beforeCleanup = owner.snapshot()
        val failure = assertFailsWith<HotSpotSessionFailure> { owner.close() }

        assertTrue(exitedWithoutCaller, "The autonomous timer must abort the child before close")
        val failed = assertIs<HotSpotSessionState.Failed>(beforeCleanup.state)
        assertEquals("HotSpot startup/stop deadline expired", failed.failures.first().message)
        assertSame(failed.failures.first(), failure.cause)
        assertTrue(beforeCleanup.pendingOwnership)
        assertFalse(failure.snapshot.pendingOwnership)
    }

    @Test
    fun `malformed output retains pending evidence and cannot produce successful EOF`() {
        val owner = HotSpotCollectorProcess.attach(HotSpotChildFixture.argv("malformed", directory), 123, 456uL)
        val failure = assertFailsWith<HotSpotSessionFailure> { owner.finish() }
        assertEquals(1uL, requireNotNull(failure.snapshot.evidence).pendingOccurrences)
        assertFalse(failure.owner.snapshot().pendingOwnership)
    }
}
