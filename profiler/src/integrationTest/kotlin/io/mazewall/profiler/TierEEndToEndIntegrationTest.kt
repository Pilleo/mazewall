package io.mazewall.profiler

import io.mazewall.NeedsFreshJvm
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.profiler.attribution.DictionaryBatchSink
import io.mazewall.profiler.attribution.DictionarySinkResult
import io.mazewall.profiler.attribution.TierEEmissionMode
import io.mazewall.profiler.attribution.TierEOptions
import io.mazewall.profiler.attribution.TierEProfilerSession
import io.mazewall.profiler.tierE.daemon.ElfSymbolOffset
import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@NeedsFreshJvm
class TierEEndToEndIntegrationTest {
    @Test
    fun `native session captures definitions and becomes terminal after finish`() {
        val agent = (System.getProperty("tier.e.agent") ?: System.getenv("TIER_E_AGENT_LIBRARY"))?.let(Path::of)
        val workloadClasspath = System.getProperty("tier.e.workloadClasspath")
            ?: System.getenv("TIER_E_WORKLOAD_CLASSPATH")
        assumeTrue(agent != null && workloadClasspath != null, "rootful Tier E fixture environment is required")
        requireNotNull(agent)
        requireNotNull(workloadClasspath)

        val definitions = Files.createTempFile("tier-e-end-to-end", ".bin")
        val gate = Path.of("$definitions.gate")
        Files.deleteIfExists(gate)
        val sessionTag = 0x4d5a
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-agentpath:$agent=definitions=$definitions,session=${sessionTag.toString(16)},mode=full-stream",
            "-cp",
            workloadClasspath,
            "AgentWorkload",
            "gate:$gate",
            "platform",
            "10",
        ).inheritIO().start()

        val sink = DictionaryBatchSink { DictionarySinkResult.Committed }
        val session = TierEProfilerSession(
            TierEbpfEngine(RealNativeEngine),
            processEpoch(process.pid()),
            TierEOptions(TierEEmissionMode.FULL_STREAM),
            sink,
        )
        try {
            session.installAndAttach(
                process.pid().toInt(),
                sessionTag,
                agent,
                ElfSymbolOffset.resolve(agent, "mazewall_stack_marker"),
            )
            Files.writeString(gate, "go")
            while (process.isAlive) {
                session.poll()
                Thread.sleep(5)
            }
            assertEquals(0, process.waitFor())

            val profile = session.finish(definitions)
            assertTrue(profile.syscalls.isNotEmpty())
            assertTrue(Files.size(definitions) > 0, "captured definitions must be published")
            assertEquals(0L, profile.integrity.observedLosses)

            assertTrue(session.markerTransitionCount() > 0)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            session.close()
            Files.deleteIfExists(gate)
            Files.deleteIfExists(definitions)
        }
    }

    private fun processEpoch(pid: Long): Long {
        val stat = Files.readString(Path.of("/proc/$pid/stat"))
        return stat.substringAfterLast(") ").split(' ')[19].toLong()
    }
}
