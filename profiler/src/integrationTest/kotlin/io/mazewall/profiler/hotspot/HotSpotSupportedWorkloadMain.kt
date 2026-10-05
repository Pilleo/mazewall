package io.mazewall.profiler.hotspot

import io.mazewall.profiler.api.HotSpotOptions
import io.mazewall.profiler.api.HotSpotProfilingException
import io.mazewall.profiler.api.MazewallProfiler
import io.mazewall.profiler.api.ProfileOptions
import io.mazewall.profiler.api.ProfileStrategy
import io.mazewall.profiler.hotspot.performance.HotSpotWorkloadMeasurementSettings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path

/** Runs only in the explicitly selected pinned interpreter/Serial fixture JVM. */
object HotSpotSupportedWorkloadMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val assets = Path.of(args[0])
        val output = Path.of(args[1])
        val socket = args[2]
        val name = args[3]
        val settings = HotSpotWorkloadMeasurementSettings(
            args.getOrNull(4)?.toInt() ?: 20,
            args.getOrNull(5)?.toInt() ?: 40,
            args.getOrNull(6)?.toLong() ?: 100L,
        )
        val prefix = listOf(
            "docker",
            "--host",
            socket,
            "run",
            "--pull=never",
            "--rm",
            "-i",
            "--name",
            name,
            "--privileged",
            "--pid=host",
            "--mount",
            "type=bind,src=$assets,dst=$assets,readonly",
            "--mount",
            "type=bind,src=/sys,dst=/sys,readonly",
            HotSpotAcceptanceHarness.GO,
        )
        val paths = (0 until settings.operations).map { index -> output.resolve("current-$index-${if (index % 2 == 0) "A" else "B"}").also { Files.write(it, byteArrayOf(1)) } }
        val warm = output.resolve("warm").also { Files.write(it, byteArrayOf(1)) }
        val uid = Files
            .readString(Path.of("/proc/self/status"))
            .lineSequence()
            .first { it.startsWith("Uid:") }
            .split(Regex("\\s+"))[1]
            .toInt()
        check(uid != 0) { "Current-JVM target must remain ordinary user" }
        val options = ProfileOptions(strategy = ProfileStrategy.HOTSPOT_AGENTLESS, processWide = true)
        val admissionStart = System.nanoTime()
        var ready = 0L
        var warmupEnd = 0L
        var markedEnd = 0L
        var ended = 0L
        var outcome = "rejected"
        var received = 0uL
        var retained = 0
        var pending = 0uL
        var pendingOwnership = false
        Files.writeString(output.resolve("current-progress.txt"), "admitting\n")
        try {
            MazewallProfiler.open(options, HotSpotOptions(assets, prefix)).use { profiler ->
            val result = profiler.profile {
                ready = System.nanoTime()
                repeat(settings.warmups) { index ->
                    call(index, warm)
                    Thread.sleep(settings.paceMillis)
                }
                warmupEnd = System.nanoTime()
                paths.forEachIndexed { index, path ->
                    call(index, path)
                    Thread.sleep(settings.paceMillis)
                }
                markedEnd = System.nanoTime()
                "workload-value"
            }
            ended = System.nanoTime()
            check(result.value == "workload-value" && !result.coverage.complete)
            val evidence = requireNotNull(result.structuredEvidence)
            received = evidence.summary.received
            retained = evidence.records.size
            pending = evidence.pendingOccurrences
            check(evidence.finalized && evidence.summary.physicalDrainComplete)
            Files.writeString(output.resolve("current-evidence.json"), evidence.toJson())
            check(evidence.records.count { Regex("/current-[0-9]+-[AB]$").containsMatchIn(it.event.filename) } == settings.operations)
            check(evidence.records.any { record -> record.evidence.frames.any { it.functionName.contains("HotSpotSupportedWorkloadMain") } })
            Files.writeString(output.resolve("current-result.txt"), "${result.value}\nuid=$uid\nrecords=${evidence.records.size}\n")
            outcome = "accepted"
        }
        } catch (failure: HotSpotProfilingException) {
            ended = System.nanoTime()
            failure.partialResult?.structuredEvidence?.let {
                received = it.summary.received
                retained = it.records.size
                pending = it.pendingOccurrences
            }
            if (failure.pendingOwnership) {
                try {
                    failure.retryCleanup()
                } catch (cleanup: Exception) {
                    failure.addSuppressed(cleanup)
                }
            }
            pendingOwnership = failure.pendingOwnership
            failure.partialResult?.structuredEvidence?.let { Files.writeString(output.resolve("current-failed-evidence.json"), it.toJson()) }
            Files.writeString(output.resolve("current-failure.txt"), "pending=${failure.pendingOwnership}\n${failure.diagnostics}\n")
            throw failure
        } finally {
            val measurement = buildJsonObject {
                put("schema_version", 1)
                put("outcome", outcome)
                put("operations", settings.operations)
                put("warmups", settings.warmups)
                put("pace_millis", settings.paceMillis)
                put("admission_nanos", if (ready == 0L) null else ready - admissionStart)
                put("warmup_nanos", if (warmupEnd == 0L) null else warmupEnd - ready)
                put("marked_nanos", if (markedEnd == 0L) null else markedEnd - warmupEnd)
                put("finish_nanos", if (markedEnd == 0L || ended == 0L) null else ended - markedEnd)
                put("received", received.toLong())
                put("retained", retained)
                put("pending", pending.toLong())
                put("pending_ownership", pendingOwnership)
                put("collection_loss_complete", false)
            }
            Files.writeString(output.resolve("current-measurement.json"), measurement.toString() + "\n")
        }
    }

    private fun call(
        index: Int,
        path: Path,
    ) {
        if (index % 2 == 0) pathA(path) else pathB(path)
    }

    private fun pathA(path: Path) = leaf(path)

    private fun pathB(path: Path) = bridge(path)

    private fun bridge(path: Path) = leaf(path)

    private fun leaf(path: Path) {
        FileInputStream(path.toFile()).use { check(it.read() == 1) }
    }
}
