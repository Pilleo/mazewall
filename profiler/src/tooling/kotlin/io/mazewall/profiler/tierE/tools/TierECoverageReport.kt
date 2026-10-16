package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Active coverage report and integrity receipts; historical Node replay remains available. */
public object TierECoverageReport {
    public fun write(directory: Path): JsonObject {
        val rows = TierECoverageSummary.parse(Files.readString(directory.resolve("results.jsonl")))
        val coverage = rows.filter { it["kind"]?.toString() == "\"coverage\"" }
        val comparisons = coverage.map { row ->
            val variant = row.getValue("variant").jsonPrimitive.content
            val fork = row.getValue("fork").jsonPrimitive.content
            require(variant.matches(Regex("[a-z_]+")) && fork.matches(Regex("[1-9][0-9]*"))) { "Invalid coverage manifest label" }
            val actualFile = directory.resolve("coverage-manifests/$variant-fork-$fork.txt.stacks.json")
            val referenceFile = directory.resolve("coverage-manifests/dictionary_full_stream-fork-$fork.txt.stacks.json")
            if (!Files.exists(actualFile) || !Files.exists(referenceFile)) {
                buildJsonObject {
                    put("variant", row.getValue("variant"))
                    put("fork", row.getValue("fork"))
                    put("status", "UNVERIFIED")
                }
            } else {
                val actual = TierECoverageStackIdentity.normalize(Json.parseToJsonElement(Files.readString(actualFile)).jsonObject)
                val expected = TierECoverageStackIdentity.normalize(Json.parseToJsonElement(Files.readString(referenceFile)).jsonObject)
                val shared = expected.keys.filter { it in actual }
                val mismatches = shared.filter { expected[it] != actual[it] }
                buildJsonObject {
                    put("variant", row.getValue("variant"))
                    put("fork", row.getValue("fork"))
                    put("shared_paths", shared.size)
                    put("mismatches", JsonArray(mismatches.map(::JsonPrimitive)))
                    put(
                        "status",
                        if (mismatches.isNotEmpty()) {
                        "FAILED"
                    } else if (shared.size == 72) {
                        "PASS"
                    } else {
                        "INCOMPLETE"
                    },
                    )
                    put("qualification", "same_immutable_bundle_and_JDK_no_redefinition_workload")
                }
            }
        }
        val summary = buildJsonObject {
            for ((key, value) in TierECoverageSummary.summarize(rows)) put(key, value)
            put("full_stack_comparison", JsonArray(comparisons))
        }
        Files.writeString(directory.resolve("coverage-summary.json"), "$summary\n")
        val digest = MessageDigest.getInstance("SHA-256")
        val checksums = listOf("results.jsonl", "manifest.json", "coverage-summary.json").mapNotNull { filename ->
            val file = directory.resolve(filename)
            if (!Files.exists(file)) return@mapNotNull null
            val hex = digest.digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
            "$hex  $filename"
        }
        Files.writeString(directory.resolve("coverage-checksums.sha256"), checksums.joinToString("\n", postfix = "\n"))
        return summary
    }
}
