package io.mazewall.profiler.hotspot

import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.profiler.internal.hotspot.HotSpotAttachOptions
import io.mazewall.profiler.internal.hotspot.HotSpotBackend
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorFailure
import io.mazewall.profiler.internal.hotspot.HotSpotSessionFailure
import io.mazewall.profiler.internal.hotspot.HotSpotSessionState
import io.mazewall.profiler.internal.hotspot.HotSpotTargetIdentity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotSpotBackendTest {
    @TempDir lateinit var directory: Path

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
