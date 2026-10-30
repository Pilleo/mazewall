package io.mazewall.verification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class BenchmarkWorkflowTest {
    @TempDir private lateinit var root: Path

    @Test fun `benchmark freeze pins actual installed and published bytes and rejects later changes`() {
        val fixture = fixture(false)
        val frozen = runStep("Freeze Benchmark Inputs", fixture)
        assertEquals(0, frozen.exitCode, frozen.stdout + frozen.stderr)
        val custody = Json.parseToJsonElement(Files.readString(root.resolve("evidence/custody.json"))).jsonObject
        assertEquals(BootFixture.hash(root.resolve("bundle.zip")), custody.getValue("workerBundleSha256").jsonPrimitive.content)
        assertEquals(0, runStep("Verify Frozen Benchmark Inputs", fixture).exitCode)
        Files.writeString(fixture.resolve("published.jar"), "changed publication bytes")
        val changed = runStep("Verify Frozen Benchmark Inputs", fixture)
        assertTrue(changed.exitCode != 0, "A substituted publication must not qualify")
        assertTrue("FAILED" in changed.stdout + changed.stderr)
    }

    @Test fun `benchmark freeze refuses a mismatched embedded worker before writing custody`() {
        val frozen = runStep("Freeze Benchmark Inputs", fixture(true))
        assertTrue(frozen.exitCode != 0, frozen.stdout + frozen.stderr)
        assertFalse(Files.exists(root.resolve("evidence/custody.json")))
    }

    private fun fixture(corrupt: Boolean): Path {
        val repository = Files.createDirectory(root.resolve("repository"))
        val lib = Files.createDirectory(root.resolve("lib"))
        Files.createDirectory(root.resolve("evidence"))
        Files.createDirectory(root.resolve("git-logs"))
        Files.createDirectory(root.resolve("step-logs"))
        val bundle = Files.writeString(root.resolve("bundle.zip"), "actual embedded worker bytes")
        val digest = if (corrupt) "0".repeat(64) else BootFixture.hash(bundle)
        JarOutputStream(Files.newOutputStream(lib.resolve("koog-contained-tool-$VERSION.jar"))).use { jar ->
            for ((name, bytes) in mapOf(
                    "META-INF/mazewall/portal/worker-artifacts/bounded-text-worker" to "format=2\nresource=worker.zip\nsha256=$digest\n".toByteArray(),
                    "worker.zip" to Files.readAllBytes(bundle),
                )) {
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        Files.writeString(repository.resolve("published.jar"), "actual staged publication bytes")
        val lock = root.resolve("benchmarks/integration-boundaries/locks/$VERSION.lockfile")
        Files.createDirectories(lock.parent)
        Files.writeString(lock, "actual coordinate lock")
        var sequence = 0

        fun git(vararg args: String) {
            val result = ConsumerFixture.command(listOf("git") + args, root, root.resolve("git-logs"), "git-${sequence++}")
            assertEquals(0, result.exitCode, result.stderr)
        }
        git("init", "--quiet")
        git("add", lock.toString())
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "fixture")
        return repository
    }

    private fun runStep(
        name: String,
        repository: Path,
    ): ConsumerCommandResult {
        val path = Path.of(".github/workflows/benchmarks.yml")
        assertTrue(Files.isRegularFile(path), "Manual global benchmark workflow is absent")
        val step = Files.readString(path).substringAfter("- name: $name\n", "").substringBefore("\n            - name:")
        val script = step.substringAfter("run: |\n", "").trimIndent()
        require(script.isNotBlank()) { "Missing executable workflow step: $name" }
        val exports = "export BOUNDARY_EVIDENCE=\"\$1\" BOUNDARY_REPOSITORY=\"\$2\" BOUNDARY_LIB=\"\$3\" BOUNDARY_VERSION=\"\$4\"\n"
        return ConsumerFixture.command(
            listOf("bash", "-euo", "pipefail", "-c", exports + script, "benchmark-fixture", root.resolve("evidence").toString(), repository.toString(), root.resolve("lib").toString(), VERSION),
            root,
            Files.createTempDirectory(root.resolve("step-logs"), "step-"),
            name.replace(' ', '-'),
        )
    }

    private companion object {
        const val VERSION = "0.1.0-benchmark.fixture"
    }
}
