package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

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
        val output = Files.createTempDirectory("tier-e-noise-replay-")
        try {
            for (fixture in listOf("control", "noisy")) {
                val target = Files.createDirectory(output.resolve(fixture))
                for (name in listOf("terminal.json", "manifest.json", "results.jsonl")) {
                    requireNotNull(javaClass.getResourceAsStream("/tier-e/node-noise/$fixture/$name")).use { Files.copy(it, target.resolve(name)) }
                }
            }
            val control = TierENodeNoiseQualification.verifyTarget(output.resolve("control"))
            assertEquals("0,1", control.cpuSet)
            assertThrows(IllegalArgumentException::class.java) {
                TierENodeNoiseQualification.verifyTarget(output.resolve("noisy"))
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
