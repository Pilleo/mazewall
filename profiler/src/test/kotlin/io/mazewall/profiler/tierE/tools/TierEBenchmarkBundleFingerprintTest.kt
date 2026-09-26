package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierEBenchmarkBundleFingerprintTest {
    @Test
    fun `tracked source changes and native marker setting change the bundle identity`() {
        val repository = Files.createTempDirectory("tier-e-bundle-fingerprint-")
        try {
            val source = Files.createDirectories(repository.resolve("profiler")).resolve("source.kt")
            Files.writeString(source, "v1")
            check(ProcessBuilder("git", "init", "-q", repository.toString()).start().waitFor() == 0)
            check(ProcessBuilder("git", "-C", repository.toString(), "add", "profiler/source.kt").start().waitFor() == 0)
            val first = TierEBenchmarkBundleFingerprint.read(repository, false, "Release")
            assertTrue(first.matches(Regex("[a-f0-9]{64}")))
            Files.writeString(source, "v2")
            assertNotEquals(first, TierEBenchmarkBundleFingerprint.read(repository, false, "Release"))
            Files.delete(source)
            assertNotEquals(first, TierEBenchmarkBundleFingerprint.read(repository, false, "Release"))
            assertNotEquals(
                TierEBenchmarkBundleFingerprint.read(repository, false, "Release"),
                TierEBenchmarkBundleFingerprint.read(repository, true, "Release"),
            )
        } finally {
            repository.toFile().deleteRecursively()
        }
    }
}
