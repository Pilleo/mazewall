package io.mazewall.profiler.hotspot

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.profiler.api.HotSpotObservationTarget
import io.mazewall.profiler.api.HotSpotOptions
import io.mazewall.profiler.api.HotSpotProfilingException
import io.mazewall.profiler.api.HotSpotProfilingSession
import io.mazewall.profiler.api.IncompleteProfileException
import io.mazewall.profiler.api.MazewallProfiler
import io.mazewall.profiler.api.StructuredProfileEvidence
import io.mazewall.testing.awaitCondition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Opt-in direct-helper acceptance. No Docker, sudo, JVM agent, or kernel-policy installation. */
@EnabledIfLinuxAndSupported
@EnabledIfEnvironmentVariable(named = "MW_HOTSPOT_OBSERVED_ACCEPTANCE", matches = "true")
class HotSpotObservedIntegrationTest {
    @Test
    fun `public observations attribute ordinary JVM routes without a container runner`(
        @TempDir(cleanup = CleanupMode.NEVER) directory: Path,
    ) {
        val java = Path.of(requireNotNull(System.getenv("MW_HOTSPOT_OBSERVED_JAVA")))
        val assets = Path.of(requireNotNull(System.getenv("MW_HOTSPOT_ASSET_TEST_DIR")))
        require(java.isAbsolute && Files.isExecutable(java) && assets.isAbsolute)
        val source = directory.resolve("ObservedWorkload.java")
        requireNotNull(javaClass.getResourceAsStream("/hotspot/ObservedWorkload.java")).use { Files.copy(it, source) }
        val target = ProcessBuilder(java.toString(), source.toString(), directory.toString(), "400", "4", "20")
            .redirectOutput(directory.resolve("target.stdout").toFile())
            .redirectError(directory.resolve("target.stderr").toFile())
            .start()
        var session: HotSpotProfilingSession? = null
        var targetIdentity: HotSpotObservationTarget? = null
        var releaseAllowed = true
        var pendingOwnership = "not_acquired"
        var originalFailureOwnership = "no_failure_before_cleanup"
        var failure: Throwable? = null
        try {
            awaitCondition(Duration.ofSeconds(30), "ordinary JVM workload readiness") {
                check(target.isAlive) { "Workload exited before ready; diagnostics retained at $directory" }
                Files.exists(directory.resolve("ready"))
            }
            val identity = observedTargetIdentity(Pid(Math.toIntExact(target.pid())))
            targetIdentity = identity
            Files.writeString(
                directory.resolve("target-identity.json"),
                buildJsonObject {
                put("pid", identity.pid.value)
                put("process_start_ticks", identity.processStartTicks.toString())
                put("cgroup_id", identity.cgroupId.toString())
                put("mount_namespace", identity.mountNamespace.toString())
            }.toString(),
                CREATE_NEW,
            )
            retainObservedMaps(Path.of("/proc/${identity.pid.value}/maps"), directory.resolve("target-maps-before"), identity) {
                observedTargetIdentity(identity.pid)
            }
            releaseAllowed = false
            pendingOwnership = "unknown"
            val collector = MazewallProfiler.observeHotSpot(identity, HotSpotOptions(assets))
            session = collector
            Files.createFile(directory.resolve("measure"))
            awaitCondition(Duration.ofSeconds(30), "ordinary JVM marked-work completion") {
                check(target.isAlive) { "Workload exited during collection; diagnostics retained at $directory" }
                Files.exists(directory.resolve("done"))
            }
            assertEquals("400\n", Files.readString(directory.resolve("done")))
            val result = collector.finish()
            val evidence = requireNotNull(result.structuredEvidence)
            Files.writeString(directory.resolve("evidence.json"), evidence.toJson(), CREATE_NEW)
            verifyObservedCorpus(evidence, directory, identity.pid, identity.processStartTicks)
            assertFalse(result.coverage.complete || evidence.policyEligibility || evidence.collectionLossComplete || collector.pendingOwnership)
            assertFailsWith<IncompleteProfileException> { result.toDedicatedWorkerPolicies() }
        } catch (caught: Throwable) {
            failure = caught
            originalFailureOwnership = if (caught is HotSpotProfilingException) caught.pendingOwnership.toString() else "unknown"
            if (session == null && caught is HotSpotProfilingException) {
                pendingOwnership = caught.pendingOwnership.toString()
                releaseAllowed = !caught.pendingOwnership
            }
        } finally {
            val interrupted = Thread.interrupted() || failure is InterruptedException

            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (caught: Throwable) {
                    failure = CleanupFailures.merge(failure, caught)
                }
            }
            targetIdentity?.let { identity ->
                attempt {
                    check(target.isAlive) { "Target exited before final maps read" }
                    retainObservedMaps(Path.of("/proc/${identity.pid.value}/maps"), directory.resolve("target-maps-after"), identity) {
                        observedTargetIdentity(identity.pid)
                    }
                }
            }
            val collector = session
            if (collector != null) {
                failure = closeObservedSession(directory, collector, failure)
                releaseAllowed = !collector.pendingOwnership
                pendingOwnership = collector.pendingOwnership.toString()
            } else {
                attempt { Files.writeString(directory.resolve("pending-ownership-after-close.txt"), pendingOwnership, CREATE_NEW) }
            }
            if (releaseAllowed) {
                attempt { Files.writeString(directory.resolve("release"), "release\n", CREATE_NEW) }
                attempt {
                    check(target.waitFor(5, TimeUnit.SECONDS)) { "Target did not retire; retained PID=${target.pid()} at $directory" }
                    check(target.exitValue() == 0) { "Target exited ${target.exitValue()}; diagnostics retained at $directory" }
                }
                attempt { target.outputStream.close() }
                attempt { target.inputStream.close() }
                attempt { target.errorStream.close() }
            } else {
                val held = IllegalStateException("Collector ownership pending or unknown; target PID=${target.pid()} and all evidence retained at $directory")
                failure = CleanupFailures.merge(failure, held)
            }
            attempt {
                Files.writeString(
                    directory.resolve("target-retirement.txt"),
                    "pid=${target.pid()}\nrelease_allowed=$releaseAllowed\nalive=${target.isAlive}\n" +
                        "exit_code=${if (target.isAlive) "unknown" else target.exitValue()}\n",
                    CREATE_NEW,
                )
            }
            attempt {
                Files.writeString(
                    directory.resolve("attempt-outcome.txt"),
                    "outcome=${if (failure == null) "accepted" else "rejected"}\n" +
                        "original_failure_pending_ownership=$originalFailureOwnership\n" +
                        "pending_ownership_after_close=$pendingOwnership\n",
                    CREATE_NEW,
                )
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        failure?.let { throw retainObservedFailure(directory, it) }
    }
}

private fun verifyObservedCorpus(
    evidence: StructuredProfileEvidence,
    directory: Path,
    pid: Pid,
    birth: ULong,
) {
    val oracle = (0..3).flatMap { worker ->
        Files.readAllLines(directory.resolve("oracle-$worker.tsv")).map { line ->
            val fields = line.split('\t')
            require(fields.size == 3)
            fields[0] to (fields[1] to fields[2].toInt())
        }
    }
    assertEquals(400, oracle.size)
    val expected = oracle.toMap()
    assertEquals(400, expected.size)
    val records = evidence.records.filter { it.event.filename.substringAfterLast('/') in expected }
    assertEquals(400, records.size)
    assertEquals(400, records.map { it.event.filename.substringAfterLast('/') }.toSet().size)
    var useful = 0
    for (record in records) {
        val (route, tid) = expected.getValue(record.event.filename.substringAfterLast('/'))
        assertEquals(pid, record.event.pid)
        assertEquals(birth, record.event.processStartTicks)
        assertEquals(tid, record.event.tid.value)
        assertFalse(record.event.pathTruncated)
        val frames = record.evidence.frames
        val opposite = if (route == "routeA") "routeB" else "routeA"
        assertFalse(frames.any { it.functionName.contains("ObservedWorkload.$opposite(") })
        val leaf = frames.indexOfFirst { it.functionName.contains("ObservedWorkload.leaf(") }
        val caller = frames.indexOfFirst { it.functionName.contains("ObservedWorkload.$route(") }
        if (record.evidence.attribution == "event_correlated" && leaf >= 0 && caller >= 0 && frames.none { it.errorFrame }) {
            assertTrue(leaf < caller)
            useful++
        }
    }
    assertTrue(useful >= 360, "Only $useful of 400 marked operations have useful correct routes")
    assertTrue(evidence.finalized && evidence.terminalAccountingComplete && evidence.summary.physicalDrainComplete)
    assertEquals(0uL, evidence.pendingOccurrences)
    val markedNames = (0..3)
        .flatMap { worker ->
            Files.readAllLines(directory.resolve("oracle-$worker.tsv")).map { it.substringBefore('\t') }
        }.toSet()
    val compiledEvents = observedCompiledMarkedEvents(evidence, markedNames)
    Files.writeString(directory.resolve("compiled-marked-event-ids.txt"), compiledEvents.sorted().joinToString("\n"), CREATE_NEW)
    val compiledRecords = records.filter { it.eventId in compiledEvents }
    Files.writeString(
        directory.resolve("compiled-marked-routes.tsv"),
        "route\tmarked\tcompiled_events\n" +
            listOf("routeA", "routeB").joinToString("\n") { route ->
                "$route\t${oracle.count { it.second.first == route }}\t" +
                    compiledRecords.count { expected.getValue(it.event.filename.substringAfterLast('/')).first == route }
            },
        CREATE_NEW,
    )
    val sampledFrames = compiledRecords.flatMap { record ->
        record.evidence.frames.mapIndexed { index, frame ->
            buildJsonObject {
                put("event_id", record.eventId)
                put("filename", record.event.filename)
                put("route", expected.getValue(record.event.filename.substringAfterLast('/')).first)
                put("frame_index", index)
                put("function_name", frame.functionName)
                put("error_frame", frame.errorFrame)
                put("scope_lookup", frame.scopeLookup)
                put("scope_walk", frame.scopeWalk)
                put("bci_state", frame.bciState)
                put("quality_diagnostic", frame.qualityDiagnostic)
            }.toString()
        }
    }
    Files.writeString(directory.resolve("compiled-marked-frames.jsonl"), sampledFrames.joinToString("\n"), CREATE_NEW)
    assertTrue(compiledEvents.isNotEmpty(), "No marked application frame has observed nmethod PC provenance")
}
