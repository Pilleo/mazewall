package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.security.MessageDigest

class TierESplitCoverageVerifierTest {
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
