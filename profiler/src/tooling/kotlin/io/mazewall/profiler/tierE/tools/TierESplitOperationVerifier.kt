package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Independent exact directory-fd oracle, separate from terminal event completeness. */
public object TierESplitOperationVerifier {
    public fun verify(
        oracleFile: Path,
        observationsFile: Path,
        terminalFile: Path,
    ): JsonObject {
        fun read(file: Path): JsonObject =
            Json.parseToJsonElement(Files.readString(file)) as? JsonObject
            ?: throw IllegalArgumentException("Expected JSON object: $file")
        val oracle = read(oracleFile)
        val observations = read(observationsFile)
        val terminal = read(terminalFile)

        fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

        fun JsonObject.identity(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun numbers(
            value: JsonArray?,
            field: String,
        ): List<Long> {
            require(value != null) { "Missing $field" }
            return value.map { item ->
                (item as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                    ?: throw IllegalArgumentException("Malformed $field")
            }
        }
        val declared = numbers(oracle["directory_fds"] as? JsonArray, "oracle directory_fds")
        val seen = numbers(observations["exact_directory_fds"] as? JsonArray, "observed exact_directory_fds").groupingBy { it }.eachCount()
        val expected = oracle.number("expected_operations")
        val unresolved = observations.number("unresolved_observations")
        val integrity = terminal.identity("evidence_integrity")
        require(expected != null && expected >= 0 && unresolved != null && unresolved >= 0 && integrity in setOf("COMPLETE", "INCOMPLETE")) {
            "Missing operation counts or terminal evidence"
        }
        val membership = declared.toSet()
        val missing = membership.filter { it !in seen }
        val duplicate = membership.filter { (seen[it] ?: 0) > 1 }
        val background = seen.keys.filter { it !in membership }
        val registration = observations.identity("registration_id")
        val epoch = observations.number("session_epoch")
        val valid = expected == declared.size.toLong() &&
            membership.size == declared.size &&
            !registration.isNullOrBlank() &&
            registration == terminal.identity("registration_id") &&
            epoch != null &&
            epoch > 0 &&
            epoch == terminal.number("session_epoch") &&
            observations["policy_eligibility"]?.toString() == "false" &&
            terminal["policy_eligibility"]?.toString() == "false"
        val representative = when (observations["representative_only"]?.toString()) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Missing representative_only")
        }
        val coverage = if (!valid) {
            "UNVERIFIED"
        } else if (missing.isNotEmpty()) {
            "INCOMPLETE"
        } else {
            "PASS"
        }
        val history = when {
            !valid -> "UNVERIFIED"
            representative -> "NOT_APPLICABLE"
            missing.isNotEmpty() || duplicate.isNotEmpty() || background.isNotEmpty() || unresolved > 0 -> "INCOMPLETE"
            else -> "PASS"
        }

        fun counted(ids: List<Long>) =
            JsonArray(
            ids.map { fd ->
            buildJsonObject {
                put("fd", fd)
                put("count", seen.getValue(fd))
            }
        },
        )
        return buildJsonObject {
            put("schema_version", 1)
            put("expected_operations", expected)
            put("recovered_operation_ids", membership.size - missing.size)
            put("missing_operation_ids", JsonArray(missing.map(::JsonPrimitive)))
            put("duplicate_declared_ids", counted(duplicate))
            put("background_ids", counted(background))
            put("unresolved_observations", unresolved)
            put("operation_coverage_verdict", coverage)
            put("event_history_verdict", history)
            put("terminal_evidence_integrity", integrity)
            put("policy_eligibility", false)
        }
    }
}
