package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.security.MessageDigest

class TierESplitCoverageVerifierTest {
    private fun faultFixture(): java.nio.file.Path {
        val dir = Files.createTempDirectory("tier-e-fault-coverage-")
        val oracle = dir.resolve("oracle.json")
        val terminal = dir.resolve("terminal.json")
        val manifest = dir.resolve("manifest.json")
        val paths = JsonArray(
            (0..71).map { path ->
            buildJsonObject {
            put("path", path)
            put("frequency", 1)
            put("syscall_number", 257)
        }
        },
        )
        Files.writeString(
            oracle,
            buildJsonObject {
            put("seed", 23063)
            put("declared_operations", 72)
            put("paths", paths)
        }.toString(),
        )
        val dimensions = mapOf(
            "capture_domain_validity" to true,
            "transport_completeness" to false,
            "attribution_coverage" to false,
            "dictionary_integrity" to true,
            "lifecycle_validity" to true,
        )
        Files.writeString(
            terminal,
            buildJsonObject {
            put("registration_id", "split-fixture")
            put("session_epoch", 19802)
            put("evidence_integrity", "INCOMPLETE")
            for ((field, valid) in dimensions) put(field, valid)
            put("policy_eligibility", false)
        }.toString(),
        )

        fun sha(file: java.nio.file.Path) =
            MessageDigest
                .getInstance("SHA-256")
            .digest(Files.readAllBytes(file))
                .joinToString("") { "%02x".format(it) }
        Files.writeString(
            manifest,
            buildJsonObject {
            put("registration_id", "split-fixture")
            put("session_epoch", 19802)
            put("evidence_integrity", "INCOMPLETE")
            for ((field, valid) in dimensions) put(field, valid)
            put("policy_eligibility", false)
            put("expected_oracle_sha256", sha(oracle))
            put("terminal_report_sha256", sha(terminal))
            put(
                "edges",
                JsonArray(
                    (0..71).map { path ->
                buildJsonObject {
                put("workload_path", path)
                put("syscall_number", 257)
            }
            },
                ),
            )
        }.toString(),
        )
        return dir
    }

    @Test
    fun `exact ring-drop recovery passes coverage while transport and terminal remain incomplete`() {
        val dir = faultFixture()
        try {
            val result = TierESplitCoverageVerifier.verify(dir.resolve("oracle.json"), dir.resolve("manifest.json"), dir.resolve("terminal.json"))
            assertEquals("PASS", result.getValue("coverage_verdict").jsonPrimitive.content)
            assertEquals(
                72,
                result
                .getValue("recovered_edges")
                .jsonPrimitive.content
                .toInt(),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unknown edge without syscall cannot pass as a recovered path`() {
        val dir = faultFixture()
        try {
            val file = dir.resolve("manifest.json")
            val fields = (Json.parseToJsonElement(Files.readString(file)) as JsonObject).toMutableMap()
            fields["edges"] = JsonArray((fields.getValue("edges") as JsonArray) + buildJsonObject { put("workload_path", 999) })
            Files.writeString(file, JsonObject(fields).toString())
            val result = TierESplitCoverageVerifier.verify(dir.resolve("oracle.json"), file, dir.resolve("terminal.json"))
            assertEquals("FAILED", result.getValue("coverage_verdict").jsonPrimitive.content)
            assertEquals(1, (result.getValue("wrong_edges") as JsonArray).size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["unknown-id", "missing-syscall", "wrong-syscall", "duplicate", "null-edge", "string-id"])
    fun `malformed recovered edge cannot be silently admitted`(scenario: String) {
        val dir = faultFixture()
        try {
            val file = dir.resolve("manifest.json")
            val fields = (Json.parseToJsonElement(Files.readString(file)) as JsonObject).toMutableMap()
            val edges = (fields.getValue("edges") as JsonArray).toMutableList()
            val bad = when (scenario) {
                "unknown-id" -> buildJsonObject {
                    put("workload_path", 999)
                    put("syscall_number", 257)
                }
                "missing-syscall" -> buildJsonObject { put("workload_path", 0) }
                "wrong-syscall" -> buildJsonObject {
                    put("workload_path", 0)
                    put("syscall_number", 999)
                }
                "duplicate" -> edges.first()
                "null-edge" -> kotlinx.serialization.json.JsonNull
                "string-id" -> buildJsonObject {
                    put("workload_path", "0")
                    put("syscall_number", 257)
                }
                else -> error(scenario)
            }
            edges += bad
            fields["edges"] = JsonArray(edges)
            Files.writeString(file, JsonObject(fields).toString())
            val verdict = TierESplitCoverageVerifier.verify(dir.resolve("oracle.json"), file, dir.resolve("terminal.json"))
            assertEquals("FAILED", verdict.getValue("coverage_verdict").jsonPrimitive.content, scenario)
            assertEquals(1, (verdict.getValue("wrong_edges") as JsonArray).size, scenario)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing recovered edge remains incomplete even if ring transport is already incomplete`() {
        val dir = faultFixture()
        try {
            val file = dir.resolve("manifest.json")
            val fields = (Json.parseToJsonElement(Files.readString(file)) as JsonObject).toMutableMap()
            fields["edges"] = JsonArray((fields.getValue("edges") as JsonArray).dropLast(1))
            Files.writeString(file, JsonObject(fields).toString())
            val result = TierESplitCoverageVerifier.verify(dir.resolve("oracle.json"), file, dir.resolve("terminal.json"))
            assertEquals("INCOMPLETE", result.getValue("coverage_verdict").jsonPrimitive.content)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `manifest terminal dimension mismatch is unverifiable`() {
        val dir = faultFixture()
        try {
            val file = dir.resolve("manifest.json")
            val fields = (Json.parseToJsonElement(Files.readString(file)) as JsonObject).toMutableMap()
            fields["dictionary_integrity"] = JsonPrimitive(false)
            Files.writeString(file, JsonObject(fields).toString())
            val result = TierESplitCoverageVerifier.verify(dir.resolve("oracle.json"), file, dir.resolve("terminal.json"))
            assertEquals("UNVERIFIED", result.getValue("coverage_verdict").jsonPrimitive.content)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `complete exact decoded edges yield PASS and a changed oracle checksum is UNVERIFIED`() {
        val dir = Files.createTempDirectory("tier-e-split-coverage-")
        try {
            val oracle = dir.resolve("oracle.json")
            val terminal = dir.resolve("terminal.json")
            val manifest = dir.resolve("manifest.json")
            val paths = JsonArray(
                (0..71).map { path ->
                buildJsonObject {
                put("path", path)
                put("frequency", 1)
                put("syscall_number", 257)
            }
            },
            )
            val edges = JsonArray(
                (0..71).map { path ->
                buildJsonObject {
                put("workload_path", path)
                put("syscall_number", 257)
            }
            },
            )
            Files.writeString(
                oracle,
                buildJsonObject {
                put("schema_version", 1)
                put("seed", 23063)
                put("declared_operations", 72)
                put("paths", paths)
            }.toString(),
            )
            Files.writeString(
                terminal,
                buildJsonObject {
                put("registration_id", "split-fixture")
                put("session_epoch", 19802)
                put("evidence_integrity", "COMPLETE")
                for (field in listOf("capture_domain_validity", "transport_completeness", "attribution_coverage", "dictionary_integrity", "lifecycle_validity")) put(field, true)
                put("policy_eligibility", false)
            }.toString(),
            )

            fun sha(file: java.nio.file.Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
            Files.writeString(
                manifest,
                buildJsonObject {
                put("registration_id", "split-fixture")
                put("session_epoch", 19802)
                put("evidence_integrity", "COMPLETE")
                put("expected_oracle_sha256", sha(oracle))
                put("terminal_report_sha256", sha(terminal))
                for (field in listOf("capture_domain_validity", "transport_completeness", "attribution_coverage", "dictionary_integrity", "lifecycle_validity")) put(field, true)
                put("policy_eligibility", false)
                put("edges", edges)
            }.toString(),
            )
            assertEquals(
                "PASS",
                TierESplitCoverageVerifier
                .verify(oracle, manifest, terminal)
                .getValue("coverage_verdict")
                .jsonPrimitive.content,
            )
            Files.writeString(oracle, Files.readString(oracle).replace("\"seed\":23063", "\"seed\":23064"))
            assertEquals(
                "UNVERIFIED",
                TierESplitCoverageVerifier
                .verify(oracle, manifest, terminal)
                .getValue("coverage_verdict")
                .jsonPrimitive.content,
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
