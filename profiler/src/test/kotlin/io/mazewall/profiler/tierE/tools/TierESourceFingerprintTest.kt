package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

class TierESourceFingerprintTest {
    @Test
    fun `tracked source drift is detectable without touching the operator repository`() {
        val repo = Files.createTempDirectory("tier-e-source-state-")
        try {
            fun git(vararg args: String) {
                val process = ProcessBuilder(listOf("git", "-C", repo.toString()) + args.toList())
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.waitFor(), output)
            }
            git("init", "-q")
            git("config", "user.email", "tier-e-test@example.invalid")
            git("config", "user.name", "Tier E Fixture")
            Files.writeString(repo.resolve("build.gradle.kts"), "// frozen source\n")
            git("add", "build.gradle.kts")
            git("commit", "-q", "-m", "source baseline")

            val frozen = TierESourceFingerprint.read(repo)
            assertEquals(
                0,
                frozen
                .getValue("input_dirty_count")
                .jsonPrimitive.content
                .toInt(),
            )
            val before = frozen.getValue("source_digest").jsonPrimitive.content
            assertEquals(64, before.length)
            val fileDigest = MessageDigest
                .getInstance("SHA-256")
                .digest("// frozen source\n".toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val expectedAggregate = MessageDigest
                .getInstance("SHA-256")
                .digest("build.gradle.kts\u0000regular\u0000$fileDigest\n".toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(expectedAggregate, before)
            assertEquals(
                "regular",
                frozen
                    .getValue("files")
                    .jsonObject
                    .getValue("build.gradle.kts")
                .jsonObject
                    .getValue("kind")
                    .jsonPrimitive.content,
            )

            Files.writeString(repo.resolve("build.gradle.kts"), "// changed source\n")
            val changed = TierESourceFingerprint.read(repo, frozen)
            assertTrue(changed.getValue("unchanged").jsonPrimitive.content == "false")
            assertFalse(changed.getValue("source_digest").jsonPrimitive.content == before)
            assertEquals(
                1,
                changed
                .getValue("input_dirty_count")
                .jsonPrimitive.content
                .toInt(),
            )
        } finally {
            repo.toFile().deleteRecursively()
        }
    }
}
