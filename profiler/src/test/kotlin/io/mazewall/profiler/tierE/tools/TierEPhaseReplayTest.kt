package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class TierEPhaseReplayTest {
    @Test
    fun `replay preserves six raw inputs and invalidates prior complete terminal on failure`() {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("docs/internals/evidence/tier-e-separated-phase-gate-20260927/raw-receipts.tar.gz")) }
        val source = Files.createTempDirectory("tier-e-phase-input-")
        val output = Files.createTempDirectory("tier-e-phase-output-")
        try {
            val archive = repository.resolve("docs/internals/evidence/tier-e-separated-phase-gate-20260927/raw-receipts.tar.gz")
            val names = listOf("full", "unique").flatMap { mode -> (1..3).map { "$mode-$it.log" } }
            val command = listOf("tar", "-xzf", archive.toString(), "-C", source.toString()) + names.map { "phase/$it" }
            assertEquals(0, ProcessBuilder(command).inheritIO().start().waitFor())
            val input = source.resolve("phase")
            val hashes = names.associateWith { name -> hash(input.resolve(name)) }
            val baseline = repository.resolve("profiler-native/tier-e-benchmark-baseline.env")

            assertTrue(TierERootfulPhaseEvidence.replay(input, baseline, output).isSuccess)
            assertEquals("COMPLETE", reason(output))
            assertEquals(hashes, names.associateWith { name -> hash(input.resolve(name)) })
            assertFalse(Files.exists(input.resolve("phase-full-verdict.txt")))

            Files.writeString(input.resolve("unique-1.log"), "not a CTest sample\n")
            assertTrue(TierERootfulPhaseEvidence.replay(input, baseline, output).isFailure)
            assertEquals("INCOMPLETE", reason(output))
            Files.delete(input.resolve("unique-2.log"))
            assertTrue(TierERootfulPhaseEvidence.replay(input, baseline, output).isFailure)
            assertEquals("INCOMPLETE", reason(output))
        } finally {
            source.toFile().deleteRecursively()
            output.toFile().deleteRecursively()
        }
    }

    private fun reason(output: Path): String =
        Json
            .parseToJsonElement(Files.readString(output.resolve("terminal.json")))
        .jsonObject
            .getValue("reason")
            .jsonPrimitive.content

    private fun hash(path: Path): String =
        MessageDigest
            .getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
}
