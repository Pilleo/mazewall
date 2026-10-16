package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** One arm's observed artifacts, never extrapolated from other forks. */
public object TierELongMatchedRowBuilder {
    private val json = Json

    public fun build(
        meta: JsonObject,
        directory: Path,
    ): JsonObject {
        val errors = mutableListOf<String>()

        fun artifact(name: String): JsonObject? {
            val path = directory.resolve(name)
            if (!Files.exists(path)) return null
            return try {
                json.parseToJsonElement(Files.readString(path)) as? JsonObject ?: error("expected JSON object")
            } catch (failure: Exception) {
                errors += "$name: ${failure.message}"
                null
            }
        }

        val arm = meta["arm"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        val baseline = arm == "baseline"
        val emission = if (baseline) null else arm.removePrefix("dictionary-").substringBeforeLast('-', "")
        val variant = if (baseline) "baseline" else arm.substringAfterLast('-')
        val oracle = artifact("coverage-oracle.json")
        val target = artifact("target-cpu.json")
        val collector = artifact("collector-process-metrics.json")
        val coverage = artifact("coverage-verdict.json")
        val terminal = artifact("terminal-report.json")
        val summary = artifact("summary.json")
        val baselineValid = baseline && summary?.get("mode") == JsonPrimitive("baseline")
        val policy = terminal?.get("policy_eligibility") ?: coverage?.get("policy_eligibility")
            ?: summary?.get("policy_eligibility") ?: JsonPrimitive(false)
        return buildJsonObject {
            put("fork", meta["fork"] ?: JsonNull)
            put("arm", meta["arm"] ?: JsonNull)
            put("emission", emission?.let(::JsonPrimitive) ?: JsonNull)
            put("variant", variant)
            put("seed", oracle?.get("seed") ?: meta["seed"] ?: JsonNull)
            put("declared_operations", oracle?.get("declared_operations") ?: meta["declaredOperations"] ?: JsonNull)
            put("source_digest", meta["sourceDigest"] ?: JsonNull)
            put("image_id", meta["imageId"] ?: JsonNull)
            put("native_so_sha256", meta["nativeSoSha256"] ?: JsonNull)
            put("daemon_jars_sha256", meta["daemonJarsSha256"] ?: JsonNull)
            put("status", meta["status"] ?: JsonNull)
            put("timed_out", meta["timedOut"] ?: JsonPrimitive(false))
            put("policy_eligibility", policy)
            put("coverage_verdict", if (baselineValid) JsonPrimitive("NOT_APPLICABLE") else coverage?.get("coverage_verdict") ?: JsonNull)
            put("covered_paths", if (baseline) JsonNull else coverage?.get("recovered_edges") ?: JsonNull)
            put("terminal_integrity", terminal?.get("evidence_integrity") ?: JsonNull)
            put("target_cpu_ns", target?.get("target_cpu_ns") ?: JsonNull)
            put("target_sample_boundary", target?.get("sample_boundary") ?: JsonNull)
            put("collector_cpu_ns", collector?.get("cpu_ns") ?: JsonNull)
            put("collector_sample_boundary", collector?.get("sample_boundary") ?: JsonNull)
            put("artifact_errors", JsonArray(errors.map(::JsonPrimitive)))
        }
    }
}
