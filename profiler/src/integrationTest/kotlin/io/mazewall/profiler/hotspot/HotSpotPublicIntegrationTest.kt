package io.mazewall.profiler.hotspot

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.profiler.api.HotSpotProfilingException
import io.mazewall.profiler.api.IncompleteProfileException
import io.mazewall.profiler.api.MazewallProfiler
import io.mazewall.profiler.api.ProfileOptions
import io.mazewall.profiler.api.ProfileStrategy
import io.mazewall.profiler.api.StructuredProfileEvidence
import io.mazewall.profiler.testing.tierE.EnabledIfTierERootfulAvailable
import io.mazewall.testing.containers.ContainerEndpoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@EnabledIfLinuxAndSupported
@EnabledIfTierERootfulAvailable
@EnabledIfEnvironmentVariable(named = "MW_HOTSPOT_NATIVE_ACCEPTANCE", matches = "true")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
@Suppress("FunctionNaming", "MagicNumber") // Descriptive backtick test names and small deterministic fixture token/operation counts.
class HotSpotPublicIntegrationTest {
    @Test
    fun `installed public strategy attributes unique operations in exact application order`(
        endpoint: ContainerEndpoint,
        @TempDir directory: Path,
    ) {
        HotSpotAcceptanceHarness(endpoint, directory).use { fixture ->
            fixture.start()
            val pid = fixture.pid
            profiler().use { profiler ->
                profiler.attach(Pid(pid), fixture.options()).use { session ->
                    fixture.prepare()
                    fixture.measure()
                    val final = session.finish()
                    val evidence = requireNotNull(final.structuredEvidence)
                    report(fixture, "public", evidence)
                    verifyCorpus(evidence, fixture.run, pid)
                    assertFalse(final.coverage.complete || session.pendingOwnership)
                    assertFailsWith<IncompleteProfileException> { final.toDedicatedWorkerPolicies() }
                }
            }
            fixture.assertCollectorsGone()
        }
    }

    @Test
    fun `retention pressure keeps all terminal accounting but discards excess retained payload`(
        endpoint: ContainerEndpoint,
        @TempDir directory: Path,
    ) {
        HotSpotAcceptanceHarness(endpoint, directory).use { fixture ->
            fixture.start(100)
            profiler().use { profiler ->
                profiler.attach(Pid(fixture.pid), fixture.options(retained = 1)).use { session ->
                    fixture.prepare()
                    fixture.measure()
                    val final = session.finish()
                    val evidence = requireNotNull(final.structuredEvidence)
                    assertEquals(1, evidence.records.size)
                    assertTrue(evidence.discardedOccurrences >= 99uL)
                    assertTrue(evidence.terminalAccountingComplete)
                    assertFalse(evidence.retentionComplete || final.coverage.complete || session.pendingOwnership)
                    report(fixture, "retention", evidence)
                }
            }
            fixture.assertCollectorsGone()
        }
    }

    @Test
    fun `real helper permission refusal and startup deadline never yield a usable session`(
        endpoint: ContainerEndpoint,
        @TempDir directory: Path,
    ) {
        HotSpotAcceptanceHarness(endpoint, directory).use { fixture ->
            fixture.start(10)
            profiler().use { profiler ->
                val denied = assertFailsWith<HotSpotProfilingException> { profiler.attach(Pid(fixture.pid), fixture.options(privileged = false)) }
                assertTrue(denied.nativeErrnos.any { it == 1u || it == 13u })
                assertFalse(denied.pendingOwnership)
                val timeout = assertFailsWith<HotSpotProfilingException> { profiler.attach(Pid(fixture.pid), fixture.options(timeout = Duration.ofMillis(25))) }
                if (timeout.pendingOwnership) timeout.retryCleanup()
                assertFalse(timeout.pendingOwnership)
            }
            fixture.assertCollectorsGone()
        }
    }

    @Test
    fun `target exit aborts public session without replacing its event stacks`(
        endpoint: ContainerEndpoint,
        @TempDir directory: Path,
    ) {
        HotSpotAcceptanceHarness(endpoint, directory).use { fixture ->
            fixture.start(100)
            profiler().use { profiler ->
                val session = profiler.attach(Pid(fixture.pid), fixture.options())
                val original = fixture.pid
                fixture.prepare()
                fixture.measure()
                fixture.killTarget()
                val restarted = fixture.restartTarget()
                assertTrue(restarted != original)
                val failed = assertFailsWith<HotSpotProfilingException> { session.finish() }
                if (failed.pendingOwnership) failed.retryCleanup()
                assertFalse(failed.pendingOwnership)
                assertFalse(failed.partialResult?.coverage?.complete == true)
            }
            fixture.assertCollectorsGone()
        }
    }

    @Test
    fun `supported ordinary current JVM executes the public workload facade`(
        endpoint: ContainerEndpoint,
        @TempDir directory: Path,
    ) = currentJvmMeasurement(endpoint, directory, dense = false)

    @Test
    fun `dense interpreter diagnostic retains checked success or rejection`(
        endpoint: ContainerEndpoint,
        @TempDir directory: Path,
    ) = currentJvmMeasurement(endpoint, directory, dense = true)

    private fun currentJvmMeasurement(
        endpoint: ContainerEndpoint,
        directory: Path,
        dense: Boolean,
    ) {
        val assets = Path.of(requireNotNull(System.getenv("MW_HOTSPOT_ASSET_TEST_DIR")))
        val root = Path.of(".").toAbsolutePath().normalize()
        val dist = root.resolve("build/install/tier-e-daemon/lib")
        val classes = root.resolve("build/classes/kotlin/integrationTest")
        val uri = requireNotNull(endpoint.resolvedUri)
        require(uri.startsWith("unix://"))
        val socket = Path.of(uri.removePrefix("unix://"))
        val gid = Files.getAttribute(socket, "unix:gid").toString()
        val uid = Files.getAttribute(directory, "unix:uid").toString()
        val collector = "hs-current-${java.util.UUID.randomUUID()}"
        val client = "$collector-client"
        val docker = Path.of("/usr/bin/docker")
        try {
            val commandResult = HotSpotAcceptanceHarness.runCommand(
                endpoint.command(
                    "run",
                    "--pull=never",
                    "--rm",
                    "--name",
                    client,
                    "--user",
                    uid,
                    "--group-add",
                    gid,
                    "--pid=host",
                "--mount",
                    "type=bind,src=$directory,dst=$directory",
                    "--mount",
                    "type=bind,src=$assets,dst=$assets,readonly",
                "--mount",
                    "type=bind,src=$dist,dst=$dist,readonly",
                    "--mount",
                    "type=bind,src=$classes,dst=$classes,readonly",
                "--mount",
                    "type=bind,src=$docker,dst=$docker,readonly",
                    "--mount",
                    "type=bind,src=$socket,dst=$socket",
                HotSpotAcceptanceHarness.JDK,
                    "java",
                    "-Xint",
                    "-XX:+UseSerialGC",
                    "-cp",
                    "$dist/*:$classes",
                "io.mazewall.profiler.hotspot.HotSpotSupportedWorkloadMain",
                    assets.toString(),
                    directory.toString(),
                    uri,
                    collector,
                    if (dense) "600" else "20",
                    "40",
                    if (dense) "1" else "100",
                ),
                timeoutSeconds = 120,
            )
            val measurement = Json.parseToJsonElement(Files.readString(directory.resolve("current-measurement.json"))).jsonObject
            assertEquals(if (dense) "600" else "20", measurement.getValue("operations").jsonPrimitive.content)
            assertEquals("false", measurement.getValue("pending_ownership").jsonPrimitive.content)
            val accepted = measurement.getValue("outcome").jsonPrimitive.content == "accepted"
            if (!dense) assertTrue(accepted, commandResult.second)
            val evidenceName = if (accepted) "current-evidence.json" else "current-failed-evidence.json"
            val evidence = StructuredProfileEvidence.fromJson(Files.readString(directory.resolve(evidenceName)))
            if (accepted) {
                assertEquals(0, commandResult.first)
                assertTrue(Files.readString(directory.resolve("current-result.txt")).startsWith("workload-value"))
                assertTrue(evidence.finalized && evidence.summary.physicalDrainComplete)
                assertEquals(0uL, evidence.pendingOccurrences)
            } else {
                assertTrue(commandResult.first != 0, "Rejected collection must retain a failing exit")
                assertTrue(Files.exists(directory.resolve("current-failure.txt")))
            }
            assertFalse(evidence.collectionLossComplete)
            assertTrue(HotSpotAcceptanceHarness.command(endpoint.command("ps", "-a", "--filter", "name=$collector", "--format", "{{.Names}}")).isBlank())
            val destination = Path.of("build/reports/hotspot-acceptance/$collector-${if (dense) "dense" else "current"}")
            Files.createDirectories(destination)
            Files.copy(directory.resolve(evidenceName), destination.resolve("evidence.json"))
            Files.copy(directory.resolve("current-measurement.json"), destination.resolve("measurement.json"))
            Files.writeString(destination.resolve("client-output.txt"), commandResult.second)
            Files.copy(directory.resolve(if (accepted) "current-result.txt" else "current-failure.txt"), destination.resolve("result.txt"))
            Files.copy(assets.resolve("manifest.json"), destination.resolve("assets-manifest.json"))
        } finally {
            val progress = directory.resolve("current-progress.txt")
            if (Files.exists(progress)) println("Current-JVM progress: ${Files.readString(progress)}")
            val failed = directory.resolve("current-failed-evidence.json")
            if (Files.exists(failed)) {
                val evidence = StructuredProfileEvidence.fromJson(Files.readString(failed))
                println("Current-JVM failed readout received=${evidence.summary.received} paired=${evidence.records.size} pending=${evidence.pendingOccurrences} raw=${evidence.summary.rawJson}")
            }
            HotSpotAcceptanceHarness.runCommand(endpoint.command("rm", "-f", collector))
            HotSpotAcceptanceHarness.runCommand(endpoint.command("rm", "-f", client))
        }
    }

    private fun verifyCorpus(
        evidence: StructuredProfileEvidence,
        run: String,
        pid: Int,
    ) {
        val marked = evidence.records.filter { it.event.filename.contains("hs-$run-") }
        assertEquals(400, marked.size)
        assertEquals(400, marked.map { it.event.filename }.toSet().size)
        assertEquals(1, marked.map { it.event.processStartTicks }.toSet().size)
        var useful = 0
        for (record in marked) {
            assertEquals(pid, record.event.pid.value)
            if (record.evidence.attribution != "event_correlated") {
                assertTrue(record.evidence.frames.isEmpty())
                assertTrue(record.evidence.attribution in setOf("unavailable", "skipped"))
                continue
            }
            val functions = record.evidence.frames
                .map { it.functionName }
                .filter { it.contains("HotSpotAcceptanceFixture") }
            val a = record.event.filename.endsWith("-A")
            val expected = if (a) listOf("sharedLeaf", "pathA", "call", "main") else listOf("sharedLeaf", "bridge", "pathB", "call", "main")
            assertEquals(expected, functions.map { it.substringBefore('(').substringAfterLast('.') })
            useful++
        }
        assertTrue(useful > 0 && evidence.terminalAccountingComplete && evidence.summary.physicalDrainComplete)
        assertFalse(evidence.collectionLossComplete)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `matched public wrapper and standalone windows retain actual measurements`(
        endpoint: ContainerEndpoint,
        @TempDir directory: Path,
    ) {
        val measurements = mutableListOf<kotlinx.serialization.json.JsonObject>()
        repeat(3) { repeat ->
            for (mode in if (repeat % 2 == 0) listOf("public", "standalone") else listOf("standalone", "public")) {
                val location = Files.createDirectory(directory.resolve("$repeat-$mode"))
                HotSpotAcceptanceHarness(endpoint, location).use { fixture ->
                    fixture.start()
                    if (mode == "public") {
                        profiler().use { profiler ->
                            profiler.attach(Pid(fixture.pid), fixture.options()).use { session ->
                                fixture.prepare()
                                fixture.measure()
                                val evidence = requireNotNull(session.finish().structuredEvidence)
                                verifyCorpus(evidence, fixture.run, fixture.pid)
                                report(fixture, "matched-public-$repeat", evidence)
                            }
                        }
                    } else {
                        standalone(fixture)
                    }
                    fixture.assertCollectorsGone()
                    val target = Json.parseToJsonElement(Files.readString(location.resolve("target.json"))).jsonObject
                    measurements.add(
                        kotlinx.serialization.json.buildJsonObject {
                        put("mode", kotlinx.serialization.json.JsonPrimitive(mode))
                        put("repeat", kotlinx.serialization.json.JsonPrimitive(repeat))
                        put("target", target)
                    },
                    )
                }
            }
        }
        val destination = Path.of("build/reports/hotspot-acceptance/matched-${java.util.UUID.randomUUID()}.json")
        Files.createDirectories(destination.parent)
        Files.writeString(
            destination,
            kotlinx.serialization.json
            .JsonArray(measurements)
            .toString(),
        )
        println("HotSpot matched measurement artifacts=$destination; independent 400-operation 1ms-paced target windows, no overhead threshold")
    }

    private fun standalone(fixture: HotSpotAcceptanceHarness) {
        val manifest = Json.parseToJsonElement(Files.readString(fixture.assets.resolve("manifest.json"))).jsonObject
        val image = "ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@${manifest.getValue("gadget_digest").jsonPrimitive.content}"
        val argv = fixture.options().collectorArgvPrefix +
            listOf(
                fixture.assets.resolve("mw-hotspot-collector").toString(),
                "--capture",
                "--protocol-version=2",
            "--pid",
                fixture.pid.toString(),
                "--image",
                image,
                "--store",
                fixture.assets.resolve("oci").toString(),
                "--hotspot-identity=safepoint-epoch",
                "--drain-full",
                "--duration",
                "60s",
            )
        val process = io.mazewall.platform.process.RealProcessLauncher
            .startProcess(argv, false)
        val output = fixture.directory.resolve("standalone.jsonl")
        val error = fixture.directory.resolve("standalone.stderr")
        val stdout = Thread.ofPlatform().start { Files.newOutputStream(output).use { process.inputStream.transferTo(it) } }
        val stderr = Thread.ofPlatform().start { Files.newOutputStream(error).use { process.errorStream.transferTo(it) } }
        try {
            HotSpotAcceptanceHarness.await { Files.exists(output) && Files.readString(output).contains("\"collector_ready\"") }
            fixture.prepare()
            fixture.measure()
            fixture.stopStandalone() // Signal the actual fixture-owned collector, not its Docker client proxy.
            check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) { Files.readString(error) }
            stdout.join(5000)
            stderr.join(5000)
            check(!stdout.isAlive && !stderr.isAlive)
            val accumulator = io.mazewall.profiler.internal.hotspot
                .HotSpotEvidenceAssembler()
                .begin()
            Files.newInputStream(output).use { input ->
                io.mazewall.profiler.internal.hotspot
                    .HotSpotWireReader(
                    io.mazewall.profiler.internal.hotspot
                    .HotSpotReplayLimits(),
                        2uL,
                ).read(input) { row ->
                    when (row.kind) {
                        "session_started" -> accumulator.accept(row.copy(kind = "collector_attached"))
                        "collector_ready" -> Unit
                        else -> accumulator.accept(row)
                    }
                }
            }
            val evidence = StructuredProfileEvidence(accumulator.finish())
            verifyCorpus(evidence, fixture.run, fixture.pid)
            report(fixture, "matched-standalone", evidence)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS))
            }
            stdout.join(5000)
            stderr.join(5000)
            check(!stdout.isAlive && !stderr.isAlive)
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        }
    }

    private fun report(
        fixture: HotSpotAcceptanceHarness,
        mode: String,
        evidence: StructuredProfileEvidence,
    ) {
        val destination = Path.of("build/reports/hotspot-acceptance/${fixture.run}-$mode")
        Files.createDirectories(destination)
        Files.writeString(destination.resolve("evidence.json"), evidence.toJson())
        Files.copy(fixture.directory.resolve("target.json"), destination.resolve("target.json"))
        Files.copy(fixture.assets.resolve("manifest.json"), destination.resolve("assets-manifest.json"))
        val target = Json.parseToJsonElement(Files.readString(fixture.directory.resolve("target.json"))).jsonObject
        val elapsed = target.getValue("elapsed_ns").jsonPrimitive.content
        println(
            "HotSpot accepted $mode run=${fixture.run} received=${evidence.summary.received} " +
                "resolved=${evidence.summary.resolved} unavailable=${evidence.summary.unavailable} target_elapsed_ns=$elapsed artifacts=$destination",
        )
    }

    private fun profiler(): MazewallProfiler = MazewallProfiler.open(ProfileOptions(strategy = ProfileStrategy.HOTSPOT_AGENTLESS, processWide = true))
}
