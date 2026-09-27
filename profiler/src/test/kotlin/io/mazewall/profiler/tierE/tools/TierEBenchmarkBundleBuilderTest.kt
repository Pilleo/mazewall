package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

class TierEBenchmarkBundleBuilderTest {
    @Test
    fun `verified Gradle-native package skips CMake but still stages and checks runtime bundle`() {
        val root = Files.createTempDirectory("tier-e-bundle-native-package-")
        try {
            val source = Files.createDirectory(root.resolve("profiler-native"))
            Files.writeString(source.resolve("CMakeLists.txt"), "native fixture\n")
            val packageRoot = Files.createDirectory(root.resolve("native-package"))
            val library = packageRoot.resolve("libmazewall_stack_agent.so")
            Files.writeString(library, "verified native binary")
            val tests = Files.createDirectory(packageRoot.resolve("test-binaries"))
            val executable = tests.resolve("dummy_test")
            Files.writeString(executable, "test fixture")
            executable.toFile().setExecutable(true)
            val classes = Files.createDirectory(packageRoot.resolve("agent-test-classes"))
            Files.writeString(classes.resolve("AgentWorkload.class"), "class")
            Files.writeString(classes.resolve("redefinition-agent.jar"), "jar")
            val identity = TierENativeArtifactPackage.Expected(
                "sha256:" + "a".repeat(64),
                "Release",
                true,
                "compiler",
                "jdk",
                "cmake",
                "amd64",
            )
            Files.writeString(
                packageRoot.resolve("manifest.json"),
                buildJsonObject {
                put("schema_version", 1)
                put("source_digest", TierENativeArtifactPackage.sourceDigest(source))
                put("image_id", identity.imageId)
                put("build_type", identity.buildType)
                put("usdt", identity.usdt)
                put("compiler", identity.compiler)
                put("java", identity.java)
                put("cmake", identity.cmake)
                put("architecture", identity.architecture)
                put("policy_eligibility", false)
                put(
                    "files",
                    buildJsonObject {
                    for (file in listOf(library, executable, classes.resolve("AgentWorkload.class"), classes.resolve("redefinition-agent.jar"))) {
                        put(
                            packageRoot.relativize(file).toString(),
                            MessageDigest
                                .getInstance("SHA-256")
                            .digest(Files.readAllBytes(file))
                                .joinToString("") { "%02x".format(it) },
                        )
                    }
                },
                )
            }.toString(),
            )
            val calls = mutableListOf<String>()
            val fingerprint = "b".repeat(64)
            val result = TierEBenchmarkBundleBuilder.buildPrepared(
                root,
                root.resolve("bundle"),
                root.resolve("cache"),
                true,
                false,
                "Release",
                packageRoot,
                identity,
                fingerprint,
            ) { command, _, _ ->
                calls += command.first()
                require(command.first() != "cmake") { "Cached native package may not trigger CMake" }
                if (command.first() == "env") {
                    val runtime = root.resolve("profiler/build/install/tier-e-daemon/lib")
                    Files.createDirectories(runtime)
                    Files.writeString(runtime.resolve("daemon.jar"), "runtime")
                }
            }
            assertEquals("built", result)
            assertEquals(listOf("env"), calls)
            assertEquals("verified native binary", Files.readString(root.resolve("bundle/libmazewall_stack_agent.so")))
            assertTrue(TierEBenchmarkBundleManifest.verify(root.resolve("bundle"), fingerprint))
            Files.writeString(library, "mutated")
            assertThrows(IllegalArgumentException::class.java) {
                TierEBenchmarkBundleBuilder.buildPrepared(
                    root,
                    root.resolve("second-bundle"),
                    root.resolve("cache"),
                    true,
                    false,
                    "Release",
                    packageRoot,
                    identity,
                    fingerprint,
                ) { _, _, _ -> error("Tampered native package must not build") }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native and runtime builds stage an independently verified bundle`() {
        val root = Files.createTempDirectory("tier-e-bundle-staging-")
        try {
            Files.createDirectory(root.resolve("profiler-native"))
            val bundle = root.resolve("artifacts/bundle")
            val cache = root.resolve("cache")
            val fingerprint = "c".repeat(64)
            val executed = mutableListOf<String>()
            val gradleCommands = mutableListOf<List<String>>()
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
                        gradleCommands += command
                        val runtime = root.resolve("profiler/build/install/tier-e-daemon/lib")
                        Files.createDirectories(runtime)
                        Files.writeString(runtime.resolve("daemon.jar"), "jar")
                    }
                }
            }
            assertEquals("built", status)
            assertEquals(listOf("cmake", "cmake", "env"), executed)
            assertTrue(gradleCommands.single().contains("--project-cache-dir"))
            assertTrue(gradleCommands.single().contains(cache.resolve("gradle-project-cache").toString()))
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
