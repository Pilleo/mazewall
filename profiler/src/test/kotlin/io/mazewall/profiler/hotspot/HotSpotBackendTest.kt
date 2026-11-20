package io.mazewall.profiler.hotspot

import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.profiler.api.HotSpotObservationTarget
import io.mazewall.profiler.api.HotSpotOptions
import io.mazewall.profiler.api.HotSpotProfilingException
import io.mazewall.profiler.api.MazewallProfiler
import io.mazewall.profiler.internal.hotspot.HotSpotAttachOptions
import io.mazewall.profiler.internal.hotspot.HotSpotBackend
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorFailure
import io.mazewall.profiler.internal.hotspot.HotSpotSessionFailure
import io.mazewall.profiler.internal.hotspot.HotSpotSessionState
import io.mazewall.profiler.internal.hotspot.HotSpotTargetIdentity
import io.mazewall.profiler.internal.hotspot.ProcHotSpotTargetIdentity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotSpotBackendTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `observed launch binds independent target without changing operator arguments`() {
        HotSpotAssetsTest().also { it.directory = directory }.fixture(observed = true)
        val target = HotSpotObservationTarget(Pid(123), 456uL, 789uL, 42uL)
        val child = HotSpotChildFixture.argv("success", directory, observedTarget = target)
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                assertFalse(redirectErrorStream)
                assertEquals(listOf("operator launcher", "argument with spaces"), args.take(2))
                assertTrue(
                    args.containsAll(
                        listOf(
                    "--protocol-version=3",
                            "--runtime-profile=otel_hotspot25_observed_v1",
                            "--hotspot-identity=unknown",
                    "--expected-start-ticks=456",
                            "--expected-cgroup-id=789",
                            "--expected-mntns=42",
                            "--control-stdin",
                            "--drain-full",
                ),
                    ),
                )
                assertFalse(args.contains("--hotspot-identity=safepoint-epoch"))
                return RealProcessLauncher.startProcess(child, redirectErrorStream)
            }
        }
        HotSpotBackend(launcher, HotSpotTargetIdentity { 456uL }).observe(target, HotSpotAttachOptions(directory, listOf("operator launcher", "argument with spaces"))).use { session ->
            assertEquals(1, requireNotNull(session.finish().evidence).records.size)
            assertFalse(session.snapshot().pendingOwnership)
        }
    }

    @Test
    fun `changed observed birth fails before collector acquisition`() {
        HotSpotAssetsTest().also { it.directory = directory }.fixture(observed = true)
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process = error("unexpected child acquisition")
        }
        assertFailsWith<IllegalArgumentException> {
            HotSpotBackend(launcher, HotSpotTargetIdentity { 457uL }).observe(
                HotSpotObservationTarget(Pid(123), 456uL, 789uL, 42uL),
                HotSpotAttachOptions(directory),
            )
        }
    }

    @Test
    fun `public observed session preserves provenance and compound failures with a synthetic child`() {
        HotSpotAssetsTest().also { it.directory = directory }.fixture(observed = true)
        val pid = Pid(ProcessHandle.current().pid().toInt())
        val target = HotSpotObservationTarget(pid, ProcHotSpotTargetIdentity.startTicks(pid), 789uL, 42uL)
        val prefix = HotSpotChildFixture.argv("success", directory, observedTarget = target)
        MazewallProfiler.observeHotSpot(target, HotSpotOptions(directory, prefix)).use { session ->
            val result = session.finish()
            val evidence = requireNotNull(result.structuredEvidence)
            assertEquals("otel_hotspot25_observed_v1", evidence.runtimeProfile)
            assertFalse(evidence.policyEligibility || result.coverage.complete || session.pendingOwnership)
            assertEquals(
                listOf("leaf", "caller"),
                evidence.records
                .single()
                .evidence.frames
                .map { it.functionName },
            )
        }
        val failure = assertFailsWith<HotSpotProfilingException> {
            MazewallProfiler.observeHotSpot(target, HotSpotOptions(directory, HotSpotChildFixture.argv("denied", directory, observedTarget = target)))
        }
        assertEquals(listOf(5u, 13u), failure.nativeErrnos)
        assertFalse(failure.pendingOwnership)
    }

    @Test
    fun `missing assets fail before any child is acquired`() {
        val invoked = AtomicBoolean()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                invoked.set(true)
                error("unexpected launch")
            }
        }
        assertFailsWith<IllegalArgumentException> { HotSpotBackend(launcher, HotSpotTargetIdentity { 456uL }).attach(Pid(123), HotSpotAttachOptions(directory)) }
        assertFalse(invoked.get())
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MW_HOTSPOT_ASSET_TEST_DIR", matches = ".+")
    fun `validated installed assets preserve operator argv prefix and split pipes`() {
        val fixture = HotSpotChildFixture.argv("success", directory)
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                assertFalse(redirectErrorStream)
                assertTrue(args.take(2) == listOf("operator launcher", "argument with spaces"))
                assertTrue(args.containsAll(listOf("--protocol-version=2", "--control-stdin", "--drain-full")))
                return RealProcessLauncher.startProcess(fixture, redirectErrorStream)
            }
        }
        val assets = Path.of(requireNotNull(System.getenv("MW_HOTSPOT_ASSET_TEST_DIR")))
        HotSpotBackend(launcher, HotSpotTargetIdentity { 456uL }).attach(Pid(123), HotSpotAttachOptions(assets, listOf("operator launcher", "argument with spaces"))).use { session ->
            assertTrue(requireNotNull(session.finish().evidence).finalized)
            assertFalse(session.snapshot().pendingOwnership)
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MW_HOTSPOT_NATIVE_REFUSAL_TEST", matches = "true")
    fun `actual external collector refusal preserves native errno and retires child pipes`() {
        val assets = Path.of(requireNotNull(System.getenv("MW_HOTSPOT_ASSET_TEST_DIR")))
        val pid = Pid(ProcessHandle.current().pid().toInt())
        val failure = assertFailsWith<HotSpotSessionFailure> { HotSpotBackend().attach(pid, HotSpotAttachOptions(assets)) }
        val failed = failure.snapshot.state as HotSpotSessionState.Failed
        assertTrue(failed.failures.any { it is HotSpotCollectorFailure && it.nativeErrno in listOf(1u, 13u) })
        assertFalse(failure.owner.snapshot().pendingOwnership)
    }
}
