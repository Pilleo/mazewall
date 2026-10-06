package io.mazewall.profiler.api

import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.profiler.internal.attribution.DictionaryBatchSink
import io.mazewall.profiler.internal.attribution.DictionarySinkResult
import io.mazewall.profiler.internal.attribution.ResolutionStatus
import io.mazewall.profiler.internal.attribution.SessionEpoch
import io.mazewall.profiler.internal.attribution.TierEEmissionMode
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierEProfilerSession
import io.mazewall.profiler.internal.attribution.TierETerminalReason
import io.mazewall.profiler.tierE.daemon.ElfSymbolOffset
import io.mazewall.profiler.tierE.daemon.TierEEventJsonlFileSink
import io.mazewall.profiler.tierE.daemon.TierERegisteredTarget
import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import io.mazewall.testing.awaitCondition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@NeedsFreshJvm
class TierEEndToEndIntegrationTest {
    @Test
    fun `all target captures distinct managed paths and ambient syscall rows`() {
        val agent = (System.getProperty("tier.e.agent") ?: System.getenv("TIER_E_AGENT_LIBRARY"))?.let(Path::of)
        val classpath = System.getProperty("tier.e.workloadClasspath") ?: System.getenv("TIER_E_WORKLOAD_CLASSPATH")
        assumeTrue(agent != null && classpath != null, "rootful native fixture is required")
        requireNotNull(agent)
        requireNotNull(classpath)
        val root = Files.createTempDirectory("tier-e-all-target-")
        val first = root.resolve("first.gate")
        val second = root.resolve("second.gate")
        val ready = root.resolve("ready")
        val input = root.resolve("input")
        val output = root.resolve("output")
        val definitions = root.resolve("definitions.mzse")
        Files.writeString(input, "example")
        val sessionTag = 0x4d5a
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-agentpath:$agent=session=${sessionTag.toString(16)},session-bound-definitions,definitions=$definitions,mode=full-stream",
            "-cp",
            classpath,
            "TierEAllTargetWorkload",
            first.toString(),
            second.toString(),
            ready.toString(),
            input.toString(),
            output.toString(),
        ).inheritIO().start()
        val pid = process.pid().toInt()
        val epoch = processEpoch(process.pid())
        val session = TierEProfilerSession(
            TierEbpfEngine(RealNativeEngine),
            epoch,
            TierEOptions(allTargetCapture = true),
            SessionEpoch(sessionTag.toLong()),
        )
        try {
            session.installAndAttach(pid, sessionTag, agent, ElfSymbolOffset.resolve(agent, "mazewall_stack_marker"))
            session.enableCaptureAdmission()
            Files.writeString(first, "go")
            awaitCondition(Duration.ofSeconds(20), "both managed first-stage paths") {
                if (Files.exists(ready)) {
                    true
                } else {
                    assertTrue(process.isAlive, "workload exited before its first-stage readiness marker")
                    session.poll()
                    Files.exists(ready)
                }
            }
            assertTrue(Files.exists(ready), "both managed paths must finish before releasing the next workload stage")
            Files.writeString(second, "go")
            while (process.isAlive) {
                session.poll()
                process.waitFor(5, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            assertEquals(0, process.waitFor())
            val profile = session.finish(definitions, producerClosed = true)
            assertTrue(Files.exists(output))
            assertEquals(0, profile.integrity.observedLosses)
            val methods = profile.syscalls
                .filter { it.resolutionStatus == ResolutionStatus.RESOLVED }
                .groupBy { it.observation.syscallNumber }
                .values
                .map { rows -> rows.flatMap { row -> row.stackDefinition?.frames.orEmpty() }.map { it.methodName }.toSet() }
            assertTrue(methods.any { "readConfiguration" in it && "loadTemplate" in it }, "same syscall needs both distinct managed paths")
            assertTrue(profile.syscalls.any { it.resolutionStatus == ResolutionStatus.NO_ACTIVE_INVOCATION }, "ambient target syscalls remain individual")
            val target = TierERegisteredTarget("all-target", pid, sessionTag, epoch, agent, ElfSymbolOffset.resolve(agent, "mazewall_stack_marker"), "definitions.mzse")
            val jsonl = TierEEventJsonlFileSink(root).publish(target, profile, TierETerminalReason.COMPLETE)
            assertEquals(profile.syscalls.size + 2, Files.readAllLines(jsonl).size)
            val terminal = Json.parseToJsonElement(Files.readAllLines(jsonl).last()).jsonObject
            assertEquals("true", terminal.getValue("capture_complete").jsonPrimitive.content)
            assertEquals(profile.syscalls.size.toString(), terminal.getValue("capture_admitted_count").jsonPrimitive.content)
            assertEquals(profile.syscalls.size.toString(), terminal.getValue("capture_submitted_count").jsonPrimitive.content)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            session.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native session captures definitions and becomes terminal after finish`() {
        runNativeSession(TierEEmissionMode.FULL_STREAM)
    }

    @Test
    fun `unique edge native session attaches and captures definitions`() {
        runNativeSession(TierEEmissionMode.UNIQUE_STACK_SYSCALL)
    }

    private fun runNativeSession(emissionMode: TierEEmissionMode) {
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
            "-agentpath:$agent=definitions=$definitions,live-definitions,session=${sessionTag.toString(16)},mode=${emissionMode.agentOption}",
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
            TierEOptions(emissionMode),
            sink,
            SessionEpoch(sessionTag.toLong()),
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

            val markerTransitions = session.markerTransitionCount()
            val profile = session.finish(definitions)
            assertTrue(profile.syscalls.isNotEmpty())
            assertTrue(Files.size(definitions) > 0, "captured definitions must be published")
            assertTrue(publishedBatches.isNotEmpty(), "definitions must be published before process exit")
            assertTrue(publicationAttempts >= 2, "the failed dictionary write must be retried")
            assertContentEquals(publishedBatches[0], publishedBatches[1])
            assertEquals(0L, profile.integrity.observedLosses)

            assertTrue(markerTransitions > 0)
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

    private val TierEEmissionMode.agentOption: String
        get() = when (this) {
            TierEEmissionMode.FULL_STREAM -> "full-stream"
            TierEEmissionMode.UNIQUE_STACK_SYSCALL -> "unique-stack-syscall"
        }
}
