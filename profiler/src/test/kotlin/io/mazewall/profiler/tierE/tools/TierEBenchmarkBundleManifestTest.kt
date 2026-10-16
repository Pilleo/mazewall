package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierEBenchmarkBundleManifestTest {
    @Test
    fun `bundle detects artifact mutation missing runtime and stale input fingerprint`() {
        val bundle = Files.createTempDirectory("tier-e-bundle-manifest-")
        try {
            val runtime = Files.createDirectory(bundle.resolve("runtime"))
            val jar = runtime.resolve("tier-e.jar")
            Files.writeString(jar, "jar-a")
            Files.writeString(bundle.resolve("libmazewall_stack_agent.so"), "native-a")
            val fingerprint = "a".repeat(64)
            TierEBenchmarkBundleManifest.write(bundle, fingerprint)
            assertTrue(TierEBenchmarkBundleManifest.verify(bundle, fingerprint))
            val alias = runtime.resolve("extra.jar")
            Files.createSymbolicLink(alias, jar.fileName)
            assertThrows(IllegalArgumentException::class.java) { TierEBenchmarkBundleManifest.verify(bundle, fingerprint) }
            Files.delete(alias)
            assertThrows(IllegalArgumentException::class.java) { TierEBenchmarkBundleManifest.verify(bundle, "b".repeat(64)) }
            Files.writeString(jar, "jar-b")
            assertThrows(IllegalArgumentException::class.java) { TierEBenchmarkBundleManifest.verify(bundle, fingerprint) }
            Files.writeString(jar, "jar-a")
            Files.delete(jar)
            assertThrows(IllegalArgumentException::class.java) { TierEBenchmarkBundleManifest.verify(bundle, fingerprint) }
        } finally {
            bundle.toFile().deleteRecursively()
        }
    }
}
