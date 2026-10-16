package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierETrustedArtifactExportTest {
    @Test
    fun `only identical pre-export readiness markers may be published twice`() {
        val parent = Files.createTempDirectory("tier-e-export-collision-")
        try {
            val staging = Files.createDirectory(parent.resolve("staging"))
            val output = Files.createDirectory(parent.resolve("output"))
            Files.writeString(staging.resolve("attachment-ready"), "session-1")
            Files.writeString(output.resolve("attachment-ready"), "session-1")
            TierETrustedArtifactExport.publish(staging, output)
            Files.writeString(staging.resolve("attachment-ready"), "session-2")
            assertThrows(IllegalArgumentException::class.java) { TierETrustedArtifactExport.publish(staging, output) }
            assertTrue(Files.readString(output.resolve("attachment-ready")) == "session-1")
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a symlink in container exports cannot be published into caller artifacts`() {
        val parent = Files.createTempDirectory("tier-e-export-test-")
        try {
            val staging = Files.createDirectory(parent.resolve("staging"))
            val output = Files.createDirectory(parent.resolve("output"))
            Files.writeString(staging.resolve("results.jsonl"), "evidence")
            Files.createSymbolicLink(staging.resolve("unsafe"), parent.resolve("outside"))
            assertThrows(IllegalArgumentException::class.java) { TierETrustedArtifactExport.publish(staging, output) }
            assertTrue(!Files.exists(output.resolve("results.jsonl")))
            Files.delete(staging.resolve("unsafe"))
            TierETrustedArtifactExport.publish(staging, output)
            assertTrue(Files.readString(output.resolve("results.jsonl")) == "evidence")
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}
