package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

public fun main(args: Array<String>) {
    if (args.size == 3 && args[0] == "row") {
        val meta = Json.parseToJsonElement(args[1]).jsonObject
        println(Json.encodeToString(JsonElement.serializer(), TierELongMatchedRowBuilder.build(meta, Path.of(args[2]))))
        return
    }
    if (args.isNotEmpty() &&
        args[0] == "fingerprint" &&
        (args.size == 2 || (args.size == 4 && args[2] == "--verify"))
    ) {
        val previous = if (args.size == 4) Json.parseToJsonElement(Files.readString(Path.of(args[3]))).jsonObject else null
        val fingerprint = TierESourceFingerprint.read(Path.of(args[1]), previous)
        println(Json.encodeToString(JsonElement.serializer(), fingerprint))
        if (previous != null && fingerprint.getValue("unchanged").jsonPrimitive.content != "true") exitProcess(1)
        return
    }
    if (args.size == 3 && args[0] == "summarize") {
        val decision = TierELongMatchedEstimator.summarize(Path.of(args[1]))
        Files.writeString(
            Path.of(args[2]),
            Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }.encodeToString(JsonElement.serializer(), decision) +
            "\n",
        )
        println(
            buildJsonObject {
            put("metrics_status", decision.getValue("metrics_status"))
            put("incomplete", decision.getValue("incomplete"))
        },
        )
        if (decision.getValue("metrics_status").toString() != "\"COMPLETE\"") exitProcess(1)
        return
    }
    val result = when {
        args.size == 2 && args[0] == "verify-dir" -> TierELongMatchedDirectoryVerifier.verify(Path.of(args[1]))
        args.size == 6 && args[0] == "verify-jsonl" -> TierELongMatchedAcceptance.verifyJsonl(
            Path.of(args[1]),
            Path.of(args[2]),
            Path.of(args[3]),
            Path.of(args[4]),
            Path.of(args[5]),
        )
        else -> {
            System.err.println(
                "usage: row <meta-json> <artifact-dir> | verify-dir <decision-dir> | verify-jsonl <rows> <manifest> <before> <after> <terminal> | summarize <rows> <decision> | fingerprint <repo> [--verify <before>]",
            )
            exitProcess(2)
        }
    }
    println(
        buildJsonObject {
        put("schema_version", 1)
        put("verdict", result.verdict)
        put("verification_mode", if (args[0] == "verify-dir") "full-directory" else "jsonl-only")
        put("rows", result.rows)
        put("baseline_rows", result.baselines)
        put("profiled_rows", result.profiled)
        put("errors", JsonArray(result.errors.map(::JsonPrimitive)))
        put("policy_eligibility", false)
    },
    )
    if (result.verdict != "PASS") exitProcess(1)
}
