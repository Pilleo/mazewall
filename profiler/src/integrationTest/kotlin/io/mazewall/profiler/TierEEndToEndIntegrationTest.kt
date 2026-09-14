package io.mazewall.profiler.api

import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.profiler.internal.attribution.DictionaryBatchSink
import io.mazewall.profiler.internal.attribution.DictionarySinkResult
import io.mazewall.profiler.internal.attribution.TierEEmissionMode
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierEProfilerSession
import io.mazewall.profiler.tierE.daemon.ElfSymbolOffset
import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
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
            "-agentpath:$agent=definitions=$definitions,live-definitions,session=${sessionTag.toString(16)},mode=full-stream",
            "-cp",
            workloadClasspath,
            "AgentWorkload",
            "gate:$gate",
            "platform",
            "10",
        ).inheritIO().start()

        val publishedBatches = mutableListOf<ByteArray>()
        var publicationAttempts = 0
        val sink = DictionaryBatchSink {
            publishedBatches += it.bytes
            publicationAttempts += 1
            if (publicationAttempts == 1) DictionarySinkResult.RetryableFailure else DictionarySinkResult.Committed
        }
        val session = TierEProfilerSession(
            TierEbpfEngine(RealNativeEngine),
            processEpoch(process.pid()),
            TierEOptions(TierEEmissionMode.FULL_STREAM),
            sink,
        )
        try {
            session.enableLiveDictionary(definitions)
            session.installAndAttach(
                process.pid().toInt(),
                sessionTag,
                agent,
                ElfSymbolOffset.resolve(agent, "mazewall_stack_marker"),
            )
            Files.writeString(gate, "go")
            while (process.isAlive) {
                if (Files.exists(definitions) && Files.size(definitions) > 0) session.poll()
                process.waitFor(5, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            assertEquals(0, process.waitFor())

            repeat(20) {
                if (Files.exists(definitions) && Files.size(definitions) > 0) session.poll()
                if (publicationAttempts >= 2) return@repeat
            }

            val profile = session.finish(definitions)
            assertTrue(profile.syscalls.isNotEmpty())
            assertTrue(Files.size(definitions) > 0, "captured definitions must be published")
            assertTrue(publishedBatches.isNotEmpty(), "definitions must be published before process exit")
            assertTrue(publicationAttempts >= 2, "the failed dictionary write must be retried")
            assertContentEquals(publishedBatches[0], publishedBatches[1])
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
