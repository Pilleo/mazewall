package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

class TierEBenchmarkBundleBuilderTest {
    @Test
    fun `native and runtime builds stage an independently verified bundle`() {
        val root = Files.createTempDirectory("tier-e-bundle-staging-")
        try {
            Files.createDirectory(root.resolve("profiler-native"))
            val bundle = root.resolve("artifacts/bundle")
            val cache = root.resolve("cache")
            val fingerprint = "c".repeat(64)
            val executed = mutableListOf<String>()
            val clock = AtomicLong(0)
            val status = TierEBenchmarkBundleBuilder.build(
                root,
                bundle,
                cache,
                false,
                false,
                "Release",
                fingerprint,
                clockMillis = { clock.getAndAdd(10) },
            ) { command, _, _ ->
                executed += command.first()
                when {
                    command.first() == "cmake" && "--build" in command -> {
                        Files.writeString(cache.resolve("native-$fingerprint/libmazewall_stack_agent.so"), "native")
                    }
                    command.first() == "env" -> {
                        val runtime = root.resolve("profiler/build/install/tier-e-daemon/lib")
                        Files.createDirectories(runtime)
                        Files.writeString(runtime.resolve("daemon.jar"), "jar")
                    }
                }
            }
            assertEquals("built", status)
            assertEquals(listOf("cmake", "cmake", "env"), executed)
            assertTrue(TierEBenchmarkBundleManifest.verify(bundle, fingerprint))
            val manifest = kotlinx.serialization.json.Json
                .parseToJsonElement(Files.readString(bundle.resolve("manifest.json")))
                .jsonObject
            assertTrue(
                manifest
                .getValue("build_timing_ms")
                .jsonObject
                .getValue("native_build")
                .toString() == "10",
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `verified cache reuses bundle but corrupted bundle is never replaced`() {
        val root = Files.createTempDirectory("tier-e-bundle-builder-")
        try {
            val bundle = root.resolve("bundle")
            val runtime = Files.createDirectories(bundle.resolve("runtime"))
            Files.writeString(bundle.resolve("libmazewall_stack_agent.so"), "native")
            Files.writeString(runtime.resolve("daemon.jar"), "jar")
            val fingerprint = "a".repeat(64)
            TierEBenchmarkBundleManifest.write(bundle, fingerprint)
            val result = TierEBenchmarkBundleBuilder.build(
                root,
                bundle,
                root.resolve("cache"),
                false,
                false,
                "Release",
                fingerprint,
            ) { _, _, _ -> error("Verified bundle should not build") }
            assertEquals("reused", result)
            Files.writeString(runtime.resolve("daemon.jar"), "mutated")
            assertThrows(IllegalArgumentException::class.java) {
                TierEBenchmarkBundleBuilder.build(
                    root,
                    bundle,
                    root.resolve("cache"),
                    false,
                    false,
                    "Release",
                    fingerprint,
                ) { _, _, _ -> error("Corrupted bundle must not build") }
            }
            assertTrue(Files.readString(runtime.resolve("daemon.jar")) == "mutated")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
