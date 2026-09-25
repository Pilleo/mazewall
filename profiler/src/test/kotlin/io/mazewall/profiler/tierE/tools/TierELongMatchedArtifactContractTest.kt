package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TierELongMatchedArtifactContractTest {
    private fun paths(): JsonArray =
        JsonArray(
        (0..71).map { index ->
        buildJsonObject {
            put("path", index)
            put(
                "category",
                if (index < 24) {
                "common"
            } else if (index < 48) {
                "infrequent"
            } else {
                "singleton"
            },
            )
            put(
                "frequency",
                if (index < 24) {
                3000
            } else if (index < 48) {
                124
            } else {
                1
            },
            )
            put("method", "path${index.toString().padStart(2, '0')}")
            put("syscall_number", 257)
        }
    },
    )

    @Test
    fun `exact 72 path inventory remains valid in any order`() {
        val entries = paths()
        assertTrue(TierELongMatchedDirectoryVerifier.validOracle(entries))
        assertTrue(TierELongMatchedDirectoryVerifier.validOracle(JsonArray(entries.reversed())))
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "duplicate", "missing-id", "wrong-method", "wrong-category", "wrong-syscall", "zero-frequency", "boolean-frequency", "singleton-frequency"])
    fun `corrupt 72 path inventory fails closed`(case: String) {
        val values = paths().toMutableList()

        fun changed(
            index: Int,
            field: String,
            replacement: kotlinx.serialization.json.JsonElement,
        ) {
            val obj = (values[index] as JsonObject).toMutableMap()
            obj[field] = replacement
            values[index] = JsonObject(obj)
        }
        when (case) {
            "null" -> values[0] = JsonNull
            "duplicate" -> values[1] = values[0]
            "missing-id" -> changed(71, "path", JsonPrimitive(72))
            "wrong-method" -> changed(0, "method", JsonPrimitive("path71"))
            "wrong-category" -> changed(0, "category", JsonPrimitive("singleton"))
            "wrong-syscall" -> changed(0, "syscall_number", JsonPrimitive(999))
            "zero-frequency" -> changed(0, "frequency", JsonPrimitive(0))
            "boolean-frequency" -> changed(0, "frequency", JsonPrimitive(true))
            "singleton-frequency" -> changed(48, "frequency", JsonPrimitive(2))
        }
        assertFalse(TierELongMatchedDirectoryVerifier.validOracle(JsonArray(values)), case)
    }

    private fun terminal(): JsonObject =
        buildJsonObject {
        put("registration_id", "split-fixture")
        put("session_epoch", 19802)
        put("target_pid", 3514)
        put("process_incarnation", 133817)
        put("evidence_integrity", "COMPLETE")
        put("terminal_reason", "COMPLETE")
        put("policy_eligibility", false)
        for (field in listOf("capture_domain_validity", "transport_completeness", "attribution_coverage", "dictionary_integrity", "lifecycle_validity")) put(field, true)
        put("failed_dimensions", JsonArray(emptyList()))
    }

    @ParameterizedTest
    @ValueSource(strings = ["capture_domain_validity", "transport_completeness", "attribution_coverage", "dictionary_integrity", "lifecycle_validity", "failed_dimensions"])
    fun `terminal contradictory to COMPLETE is rejected`(field: String) {
        assertTrue(TierELongMatchedDirectoryVerifier.validTerminal(terminal()))
        val corrupted = terminal().toMutableMap()
        corrupted[field] = if (field == "failed_dimensions") JsonArray(listOf(JsonPrimitive("dictionary_integrity"))) else JsonPrimitive(false)
        assertFalse(TierELongMatchedDirectoryVerifier.validTerminal(JsonObject(corrupted)), field)
        corrupted.remove(field)
        assertFalse(TierELongMatchedDirectoryVerifier.validTerminal(JsonObject(corrupted)), "missing $field")
    }
}
