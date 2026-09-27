package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierERootfulPhaseReuseTest {
    @Test
    fun `rejects foreign phase source before publishing any replay rows`() {
        val source = Files.createTempDirectory("tier-e-foreign-phase-")
        val output = Files.createTempDirectory("tier-e-phase-reuse-output-")
        try {
            Files.writeString(source.resolve("terminal.json"), """{"schema_version":1,"success":true,"reason":"COMPLETE","policy_eligibility":false}""")
            Files.writeString(source.resolve("native-identity.json"), identity(sourceDigest = "another-source"))
            for (mode in listOf("full", "unique")) {
                Files.writeString(source.resolve("phase-$mode-verdict.txt"), "{}")
                for (sample in 1..3) Files.writeString(source.resolve("$mode-$sample.log"), "incomplete")
            }
            val error = assertThrows(IllegalArgumentException::class.java) {
                TierERootfulPhaseReuse.accept(source, output, output.resolve("baseline.env"), expected())
            }
            assertTrue(error.message.orEmpty().contains("differ"))
            assertTrue(Files.list(output).use { it.findAny().isEmpty })
        } finally {
            source.toFile().deleteRecursively()
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects forged complete phase terminal without all six native samples`() {
        val source = Files.createTempDirectory("tier-e-empty-phase-")
        val output = Files.createTempDirectory("tier-e-phase-reuse-output-")
        try {
            Files.writeString(source.resolve("terminal.json"), """{"schema_version":1,"success":true,"reason":"COMPLETE","policy_eligibility":false}""")
            Files.writeString(source.resolve("native-identity.json"), identity(sourceDigest = "source"))
            val error = assertThrows(IllegalArgumentException::class.java) {
                TierERootfulPhaseReuse.accept(source, output, output.resolve("baseline.env"), expected())
            }
            assertTrue(error.message.orEmpty().contains("lacks complete"))
            assertTrue(Files.list(output).use { it.findAny().isEmpty })
        } finally {
            source.toFile().deleteRecursively()
            output.toFile().deleteRecursively()
        }
    }

    private fun identity(sourceDigest: String): String =
        """{"schema_version":1,"source_digest":"$sourceDigest","source_revision":"revision","image_id":"image","agent_sha256":"agent","workload_class_sha256":"class","compiler":"compiler","java":"jdk","cpu_set":"0,1","run_id":"phase-run","policy_eligibility":false}"""

    private fun expected(): TierERootfulPhaseReuse.Expected = TierERootfulPhaseReuse.Expected("source", "revision", "image", "agent", "class", "compiler", "jdk", "0,1")
}
