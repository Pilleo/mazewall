package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Exact coverage/operation oracle; separate from long-control acceptance. */
public object TierESplitCoverageVerifier {
    private val json = Json
    private val dimensions = listOf(
        "capture_domain_validity",
        "transport_completeness",
        "attribution_coverage",
        "dictionary_integrity",
        "lifecycle_validity",
    )

    public fun verify(
        oracleFile: Path,
        coverageFile: Path,
        terminalFile: Path,
    ): JsonObject {
        val admission = mutableListOf<String>()

        fun read(file: Path): Pair<JsonObject?, ByteArray?> =
            try {
            val bytes = Files.readAllBytes(file)
            (json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject) to bytes
        } catch (failure: Exception) {
            admission += "${file.fileName}: ${failure.message}"
            null to null
        }
        val (oracle, oracleBytes) = read(oracleFile)
        val (coverage, _) = read(coverageFile)
        val (terminal, terminalBytes) = read(terminalFile)
        if (oracle == null || coverage == null || terminal == null || oracleBytes == null || terminalBytes == null) {
            return verdict("UNVERIFIED", admission, emptyList(), emptyList(), emptyMap())
        }
        if (coverage.text("expected_oracle_sha256") != oracleBytes.sha256()) admission += "oracle_checksum"
        if (coverage.text("terminal_report_sha256") != terminalBytes.sha256()) admission += "terminal_checksum"
        if (coverage.text("registration_id") != terminal.text("registration_id") ||
            coverage.number("session_epoch") != terminal.number("session_epoch")
        ) {
            admission += "session_identity"
        }
        if (coverage.text("evidence_integrity") != terminal.text("evidence_integrity") ||
            terminal.text("evidence_integrity") !in setOf("COMPLETE", "INCOMPLETE")
        ) {
            admission += "evidence_integrity"
        }
        for (dimension in dimensions) {
            val observed = coverage.boolean(dimension)
            if (observed == null || observed != terminal.boolean(dimension)) {
                admission += dimension
            }
        }
        if (coverage.boolean("policy_eligibility") != false || terminal.boolean("policy_eligibility") != false) {
            admission += "policy_eligibility"
        }
        val paths = oracle["paths"] as? JsonArray
        val expected = linkedMapOf<Long, Long>()
        var operations = 0L
        if (paths == null || paths.isEmpty()) {
            admission += "oracle_paths_invalid"
        } else {
            for (entry in paths) {
            val path = entry as? JsonObject
            val id = path?.number("path")
            val frequency = path?.number("frequency")
            val syscall = path?.number("syscall_number")
            if (id == null || frequency == null || frequency <= 0L || syscall == null) {
                admission += "oracle_path_invalid"
                continue
            }
            if (id in expected) admission += "oracle_duplicate_path"
            expected[id] = syscall
            operations += frequency
        }
        }
        if (oracle.number("declared_operations") != operations) admission += "oracle_frequency"
        val wrong = mutableListOf<JsonElement>()
        val recovered = linkedMapOf<Long, JsonElement>()
        val edges = coverage["edges"] as? JsonArray
        if (edges == null) {
            admission += "coverage_edges_invalid"
        } else {
            for (edge in edges) {
            val record = edge as? JsonObject
            val id = record?.number("workload_path")
                val syscall = record?.number("syscall_number")
                if (id == null || syscall == null || id !in expected || syscall != expected[id] || id in recovered) {
                wrong += edge
            } else {
                recovered[id] = edge
            }
        }
        }
        val missing = expected.keys.filter { it !in recovered }
        val recall = mapOf(
            "common" to (0L..23L),
            "infrequent" to (24L..47L),
            "singleton" to (48L..71L),
        ).mapValues { (_, range) ->
            val count = expected.keys.count { it in range }
            val found = recovered.keys.count { it in range }
            Triple(count, found, if (count == 0) 0.0 else found.toDouble() / count)
        }
        val status = when {
            admission.isNotEmpty() -> "UNVERIFIED"
            wrong.isNotEmpty() -> "FAILED"
            missing.isNotEmpty() -> "INCOMPLETE"
            else -> "PASS"
        }
        return verdict(status, admission, missing, wrong, recall)
    }

    private fun verdict(
        status: String,
        admission: List<String>,
        missing: List<Long>,
        wrong: List<JsonElement>,
        recall: Map<String, Triple<Int, Int, Double>>,
    ): JsonObject =
        buildJsonObject {
        put("coverage_verdict", status)
        put("admission_errors", JsonArray(admission.map(::JsonPrimitive)))
        put("expected_edges", recall.values.sumOf { it.first })
        put("recovered_edges", recall.values.sumOf { it.second })
        put("missing_paths", JsonArray(missing.map(::JsonPrimitive)))
        put("wrong_edges", JsonArray(wrong))
        put(
            "recall",
            buildJsonObject {
            for ((category, values) in recall) {
                put(
                    category,
                    buildJsonObject {
                put("expected", values.first)
                put("recovered", values.second)
                put("recall", values.third)
            },
                )
            }
        },
        )
        put("policy_eligibility", false)
    }

    private fun ByteArray.sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
        .digest(this)
            .joinToString("") { "%02x".format(it) }

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(name: String): Long? = (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private fun JsonObject.boolean(name: String): Boolean? =
        when (get(name)?.toString()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
