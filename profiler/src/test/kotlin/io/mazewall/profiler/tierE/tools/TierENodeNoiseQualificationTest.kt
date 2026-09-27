package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TierENodeNoiseQualificationTest {
    @Test
    fun `control and noisy arms alternate and source mismatch never becomes a completed pair`() {
        val output = Files.createTempDirectory("tier-e-noise-order-")
        val calls = mutableListOf<String>()
        val original = TierENodeNoiseQualification.Identity("revision", "source", "image", "agent", "0,1")
        try {
            TierENodeNoiseQualification.schedule(2, output, control = { path ->
                calls += path.fileName.toString()
                original
            }, noisy = { path ->
                calls += path.fileName.toString()
                original
            })
            assertEquals(listOf("control-1-artifacts", "noisy-1-artifacts", "noisy-2-artifacts", "control-2-artifacts"), calls)
            assertEquals(2, Files.readAllLines(output.resolve("results.jsonl")).size)
            assertEquals(
                "dictionary_full_stream_collector_continuous",
                Json
                    .parseToJsonElement(Files.readString(output.resolve("summary.json")))
                    .jsonObject
                    .getValue("noise_overlap_mode")
                    .jsonPrimitive.content,
            )
        } finally {
            output.toFile().deleteRecursively()
        }
        val mismatched = Files.createTempDirectory("tier-e-noise-foreign-")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                TierENodeNoiseQualification.schedule(1, mismatched, control = { original }, noisy = {
                    original.copy(sourceFingerprint = "foreign")
                })
            }
            assertTrue(Files.readAllLines(mismatched.resolve("results.jsonl")).isEmpty())
        } finally {
            mismatched.toFile().deleteRecursively()
        }
    }

    @Test
    fun `real archived control is eligible while five-missing-ID target remains rejected`() {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("docs/internals/evidence/tier-e-clean-node-noise-20260927/raw-diagnostics.tar.gz")) }
        val archive = repository.resolve("docs/internals/evidence/tier-e-clean-node-noise-20260927/raw-diagnostics.tar.gz")
        val output = Files.createTempDirectory("tier-e-noise-replay-")
        try {
            val extracted = ProcessBuilder("tar", "-xzf", archive.toString(), "-C", output.toString()).inheritIO().start()
            assertEquals(0, extracted.waitFor())
            val control = TierENodeNoiseQualification.verifyTarget(output.resolve("unaudited_no_noise_control/target"))
            assertEquals("0,1", control.cpuSet)
            assertThrows(IllegalArgumentException::class.java) {
                TierENodeNoiseQualification.verifyTarget(output.resolve("default_noisy_incomplete/target"))
            }
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `alternating diagnostic fails closed without erasing earlier completed pair`() {
        val output = Files.createTempDirectory("tier-e-noise-qualification-")
        val calls = mutableListOf<String>()
        val identity = TierENodeNoiseQualification.Identity("revision", "source", "image", "agent", "0,1")
        try {
            assertThrows(IllegalStateException::class.java) {
                TierENodeNoiseQualification.schedule(2, output, control = { target ->
                    calls += target.fileName.toString()
                    identity
                }, noisy = { target ->
                    calls += target.fileName.toString()
                    if (target.fileName.toString().startsWith("noisy-2-")) error("second noisy target incomplete")
                    identity
                })
            }
            assertEquals(listOf("control-1-artifacts", "noisy-1-artifacts", "noisy-2-artifacts"), calls)
            assertEquals(1, Files.readAllLines(output.resolve("results.jsonl")).size)
            assertEquals(
                "false",
                Json
                    .parseToJsonElement(Files.readString(output.resolve("terminal.json")))
                    .jsonObject
                .getValue("success")
                    .jsonPrimitive.content,
            )
        } finally {
            output.toFile().deleteRecursively()
        }
    }
}
