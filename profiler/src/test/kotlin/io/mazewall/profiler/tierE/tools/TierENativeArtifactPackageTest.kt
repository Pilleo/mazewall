package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class TierENativeArtifactPackageTest {
    @Test
    fun `package is rejected when binary or immutable image identity changes`() {
        val root = Files.createTempDirectory("tier-e-verified-native-")
        try {
            val native = Files.createDirectories(root.resolve("native"))
            val src = Files.createDirectories(native.resolve("src"))
            Files.writeString(native.resolve("CMakeLists.txt"), "cmake contract\n")
            Files.writeString(src.resolve("agent.cpp"), "agent source\n")
            val bundle = Files.createDirectories(root.resolve("package"))
            val library = bundle.resolve("libmazewall_stack_agent.so")
            Files.writeString(library, "exact native library")
            val fixture = Files.createDirectories(bundle.resolve("test-binaries")).resolve("dummy_test")
            Files.writeString(fixture, "executable fixture")
            fixture.toFile().setExecutable(true)
            val classes = Files.createDirectories(bundle.resolve("agent-test-classes"))
            Files.writeString(classes.resolve("AgentWorkload.class"), "workload class")
            Files.writeString(classes.resolve("redefinition-agent.jar"), "agent jar")
            val digest = MessageDigest.getInstance("SHA-256")
            for (path in listOf(native.resolve("CMakeLists.txt"), src.resolve("agent.cpp"))) {
                val name = native.relativize(path).toString()
                digest.update(name.toByteArray())
                digest.update(0)
                digest.update(Files.readAllBytes(path))
                digest.update(0)
            }
            val sourceHash = hex(digest.digest())
            val outputs = listOf(library, fixture, classes.resolve("AgentWorkload.class"), classes.resolve("redefinition-agent.jar"))
            Files.writeString(
                bundle.resolve("manifest.json"),
                buildJsonObject {
                put("schema_version", 1)
                put("source_digest", sourceHash)
                put("image_id", "sha256:" + "a".repeat(64))
                put("build_type", "Debug")
                put("usdt", false)
                put("compiler", "compiler-a")
                put("java", "jdk-25")
                put("cmake", "cmake-a")
                put("architecture", "amd64")
                put("policy_eligibility", false)
                put(
                    "files",
                    buildJsonObject {
                    for (file in outputs) put(bundle.relativize(file).toString(), hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))))
                },
                )
            }.toString(),
            )
            val expected = TierENativeArtifactPackage.Expected("sha256:" + "a".repeat(64), "Debug", false, "compiler-a", "jdk-25", "cmake-a", "amd64")
            assertEquals(
                hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(library))),
                TierENativeArtifactPackage.verify(bundle, native, expected).agentSha256,
            )
            assertThrows(IllegalArgumentException::class.java) {
                TierENativeArtifactPackage.verify(bundle, native, expected.copy(imageId = "sha256:" + "b".repeat(64)))
            }
            Files.writeString(library, "mutated library")
            assertThrows(IllegalArgumentException::class.java) { TierENativeArtifactPackage.verify(bundle, native, expected) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
