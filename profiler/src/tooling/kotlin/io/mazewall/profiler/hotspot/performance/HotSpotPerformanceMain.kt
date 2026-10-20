package io.mazewall.profiler.hotspot.performance

import io.mazewall.profiler.internal.hotspot.HotSpotAssetTimings
import io.mazewall.profiler.internal.hotspot.HotSpotAssets
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Explicit offline developer entrypoint; normal JIT is a diagnostic control, not live admission. */
internal object HotSpotPerformanceMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Usage: assets|replay <input> <new-report.json>" }
        val input = Path.of(args[1]).toAbsolutePath().normalize()
        val started = System.nanoTime()
        val measurement = when (args[0]) {
            "assets" -> assets(input)
            "replay" -> replay(input)
            else -> error("Unknown performance mode: ${args[0]}")
        }
        val elapsed = System.nanoTime() - started
        val report = buildJsonObject {
            put("schema_version", 1)
            put("mode", args[0])
            put("input", input.toString())
            put("java_runtime_version", System.getProperty("java.runtime.version"))
            put("jvm_arguments", buildJsonArray { ManagementFactory.getRuntimeMXBean().inputArguments.forEach { add(it) } })
            put("total_nanos", elapsed)
            put("collection_loss_complete", false)
            measurement.forEach { (key, value) -> put(key, value) }
        }
        Files.writeString(Path.of(args[2]), report.toString() + "\n", java.nio.file.StandardOpenOption.CREATE_NEW)
    }

    private fun assets(input: Path): JsonObject {
        val timings = HotSpotAssetTimings()
        HotSpotAssets.load(input, timings)
        val manifest = Files.readAllBytes(input.resolve("manifest.json"))
        val files = Json
            .parseToJsonElement(manifest.toString(Charsets.UTF_8))
            .jsonObject
            .getValue("files")
            .jsonObject
        return buildJsonObject {
            put("asset_manifest_sha256", hash(manifest))
            put("input_bytes", files.keys.sumOf { Files.size(input.resolve(it)) })
            put("asset_files", files.size)
            put("phase_nanos", buildJsonObject { timings.snapshot().forEach { (key, value) -> put(key, value) } })
        }
    }

    private fun replay(input: Path): JsonObject {
        val rendered = Files.newInputStream(input).use { measureReplayAndRender(it, HotSpotReplayLimits()) }
        val measured = rendered.replay
        val renderedBytes = rendered.canonicalJson.toByteArray(Charsets.UTF_8)
        return buildJsonObject {
            put("input_bytes", Files.size(input))
            put("projection_schema", 1)
            put("wire_records", measured.wireRecords)
            put("pipeline_nanos", measured.pipelineNanos)
            put("mapping_nanos", measured.mappingNanos)
            put("reader_decode_io_nanos", measured.pipelineNanos - measured.mappingNanos)
            put("finish_nanos", measured.finishNanos)
            put("render_nanos", rendered.renderNanos)
            put("rendered_bytes", renderedBytes.size)
            put("rendered_sha256", hash(renderedBytes))
            put(
                "received",
                measured.evidence.summary.received
                .toLong(),
            )
            put("retained", measured.evidence.records.size)
            put("discarded", measured.evidence.discardedOccurrences.toLong())
            put("pending", measured.evidence.pendingOccurrences.toLong())
            put("native_retirement_certified", false)
        }
    }

    private fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
