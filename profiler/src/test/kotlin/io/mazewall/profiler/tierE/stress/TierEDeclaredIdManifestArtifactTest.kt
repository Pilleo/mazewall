package io.mazewall.profiler.tierE.stress

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TierEDeclaredIdManifestArtifactTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `publishes exact target authored IDs atomically before opening the audit window`() {
        val targetManifest = directory.resolve("original.openat-fds")
        val original = "root=/workload\nfd=7746\nfd=13\nfd=7748\n"
        Files.writeString(targetManifest, original)
        val output = directory.resolve("declared.manifest")
        val receipt = TierEDeclaredIdManifestArtifact.publish(targetManifest, output, setOf(13, 7746, 7748))
        assertEquals(original, Files.readString(output))
        assertEquals(3, receipt.rows)
        assertEquals(TierEPreGateEvidenceArtifact.sha256(output), receipt.sha256)
        assertTrue(Files.isRegularFile(output))
    }

    @Test
    fun `duplicate or substituted target FD rejects the manifest before publication`() {
        val targetManifest = directory.resolve("original.openat-fds")
        val output = directory.resolve("declared.manifest")
        Files.writeString(targetManifest, "root=/workload\nfd=13\nfd=13\n")
        assertThrows(IllegalArgumentException::class.java) {
            TierEDeclaredIdManifestArtifact.publish(targetManifest, output, setOf(13))
        }
        assertFalse(Files.exists(output))
        Files.writeString(targetManifest, "root=/workload\nfd=13\nfd=7746\n")
        assertThrows(IllegalArgumentException::class.java) {
            TierEDeclaredIdManifestArtifact.publish(targetManifest, output, setOf(13, 7748))
        }
        assertFalse(Files.exists(output))
    }
}
