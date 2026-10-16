package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class TierELongMatchedAcceptanceTest {
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("docs/internals/evidence/tier-e-long-matched-controls/fork-results.jsonl")) }
    private val retained = root.resolve("docs/internals/evidence/tier-e-long-matched-controls")

    @Test
    fun `retained fifteen fork control is complete with ninety profiled arms`() {
        val result = TierELongMatchedAcceptance.verifyJsonl(
            retained.resolve("fork-results.jsonl"),
            retained.resolve("run-manifest.json"),
            retained.resolve("source-state-before.json"),
            retained.resolve("source-state-after.json"),
            retained.resolve("terminal.json"),
        )
        assertEquals("PASS", result.verdict)
        assertEquals(105, result.rows)
        assertEquals(15, result.baselines)
        assertEquals(90, result.profiled)
    }

    @Test
    fun `a whole changed fork never validates against a frozen manifest`() {
        val lines = Files.readAllLines(retained.resolve("fork-results.jsonl"))
        val sample = Files.createTempFile("tier-e-cross-fork-", ".jsonl")
        try {
            Files.write(
                sample,
                lines.mapIndexed { index, line ->
                if (index < 7) line.replace(Regex("\"source_digest\":\"[^\"]+\""), "\"source_digest\":\"other-source\"") else line
            },
            )
            val result = TierELongMatchedAcceptance.verifyJsonl(
                sample,
                retained.resolve("run-manifest.json"),
                retained.resolve("source-state-before.json"),
                retained.resolve("source-state-after.json"),
                retained.resolve("terminal.json"),
            )
            assertEquals("INCOMPLETE", result.verdict)
            assertTrue(result.errors.any { it.contains("source_digest") }, result.errors.toString())
        } finally {
            Files.deleteIfExists(sample)
        }
    }

    @Test
    fun `complete rows without raw arm artifacts are not full-directory evidence`() {
        val directory = Files.createTempDirectory("tier-e-empty-artifacts-")
        try {
            for (file in listOf(
                "fork-results.jsonl",
                    "run-manifest.json",
                    "source-state-before.json",
                "source-state-after.json",
                    "terminal.json",
            )) {
                Files.copy(retained.resolve(file), directory.resolve(file))
            }
            val result = TierELongMatchedDirectoryVerifier.verify(directory)
            assertEquals("INCOMPLETE", result.verdict)
            assertTrue(result.errors.any { it.contains("missing_arm_dir") }, result.errors.toString())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["string-fork", "null-arm", "missing-artifact-errors", "huge-cpu", "rootless-policy"])
    fun `invalid row cannot be accepted as a matched control`(mutation: String) {
        val sample = Files.createTempFile("tier-e-invalid-row-", ".jsonl")
        try {
            val original = Files.readAllLines(retained.resolve("fork-results.jsonl"))
            val first = Json.parseToJsonElement(original.first()) as JsonObject
            val fields = first.toMutableMap()
            when (mutation) {
                "string-fork" -> fields["fork"] = JsonPrimitive("1")
                "null-arm" -> fields["arm"] = JsonNull
                "missing-artifact-errors" -> fields.remove("artifact_errors")
                "huge-cpu" -> fields["target_cpu_ns"] = Json.parseToJsonElement("${"9".repeat(401)}")
                "rootless-policy" -> fields["policy_eligibility"] = JsonPrimitive(true)
                else -> error(mutation)
            }
            Files.write(sample, listOf(JsonObject(fields).toString()) + original.drop(1))
            val result = TierELongMatchedAcceptance.verifyJsonl(
                sample,
                retained.resolve("run-manifest.json"),
                retained.resolve("source-state-before.json"),
                retained.resolve("source-state-after.json"),
                retained.resolve("terminal.json"),
            )
            assertEquals("INCOMPLETE", result.verdict, mutation)
            assertTrue(result.errors.isNotEmpty(), mutation)
        } finally {
            Files.deleteIfExists(sample)
        }
    }
}
