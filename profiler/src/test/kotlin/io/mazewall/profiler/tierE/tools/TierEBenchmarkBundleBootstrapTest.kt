package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TierEBenchmarkBundleBootstrapTest {
    @Test
    fun `bootstrap uses owned project cache before Kotlin bundle builder can run`() {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("scripts/build_tier_e_benchmark_bundle.sh")) }
        val workspace = Files.createTempDirectory("tier-e-bootstrap-cache-")
        try {
            val scripts = Files.createDirectory(workspace.resolve("scripts"))
            Files.copy(repository.resolve("scripts/build_tier_e_benchmark_bundle.sh"), scripts.resolve("build_tier_e_benchmark_bundle.sh"))
            val arguments = workspace.resolve("arguments.txt")
            val wrapper = workspace.resolve("gradlew")
            Files.writeString(wrapper, "#!/bin/sh\nprintf '%s\\n' \"\$@\" > '$arguments'\nexit 23\n")
            wrapper.toFile().setExecutable(true)
            val cache = workspace.resolve("owned-cache")
            val process = ProcessBuilder("bash", scripts.resolve("build_tier_e_benchmark_bundle.sh").toString(), "--fingerprint", workspace.toString(), "false")
                .apply { environment()["MAZEWALL_TIER_E_BUILD_CACHE_ROOT"] = cache.toString() }
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(23, process.waitFor())
            val args = Files.readAllLines(arguments)
            assertTrue("--project-cache-dir" in args)
            assertTrue(cache.resolve("gradle-bootstrap-project-cache").toString() in args)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}
