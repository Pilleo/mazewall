package io.mazewall.profiler

import io.mazewall.testing.awaitCondition
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import io.mazewall.testing.containers.EnabledIfRootfulContainerAvailable
import io.mazewall.testing.containers.TierEContainerRun
import io.mazewall.testing.containers.TierENativeFixtureBuild
import io.mazewall.testing.containers.runContainerCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Short, remote-safe rootful engine smoke; no host mounts or host PID assumptions. */
@EnabledIfRootfulContainerAvailable
@Tag("rootful-container")
class TierERootfulContainerEndpointIntegrationTest {
    private val image: String
        get() = System.getenv("TIER_E_DOCKER_IMAGE")?.takeIf { it.isNotBlank() } ?: "mazewall-tier-e-rootful:latest"

    private data class PreparedFixture(
        val root: Path,
        val native: Path,
        val image: String,
        val host: String,
    )

    private fun prepareFixture(endpoint: ContainerEndpoint): PreparedFixture {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("scripts/run_tier_e_split_privilege_fixture.sh")) }
        val selectedImage = image
        if (runContainerCommand(endpoint.command("image", "inspect", selectedImage)).exitCode != 0) {
            val imageBuild = runContainerCommand(
                endpoint.command("build", "-t", selectedImage, "-f", root.resolve("infra/dev/Containerfile").toString(), root.toString()),
                Duration.ofMinutes(5),
            )
            assertEquals(0, imageBuild.exitCode, imageBuild.output)
        }
        val localPidNamespace = Files.readSymbolicLink(Path.of("/proc/self/ns/pid")).toString()
        val serverPidNamespace = runContainerCommand(
            endpoint.command(
            "run",
                "--rm",
                "--pid=host",
                "--entrypoint",
                "readlink",
                selectedImage,
                "/proc/self/ns/pid",
        ),
            Duration.ofSeconds(15),
        )
        assertEquals(0, serverPidNamespace.exitCode, serverPidNamespace.output)
        assumeTrue(
            serverPidNamespace.output.trim() == localPidNamespace,
            "selected server does not share the client's host PID namespace",
        )
        val probe = Files.createTempDirectory("tier-e-locality-")
        try {
            val token = UUID.randomUUID().toString()
            Files.writeString(probe.resolve("token"), token)
            val mounted = runContainerCommand(
                endpoint.command(
                "run",
                    "--rm",
                    "--mount",
                    "type=bind,source=$probe,target=/probe,readonly",
                "--entrypoint",
                    "cat",
                    selectedImage,
                    "/probe/token",
            ),
                Duration.ofSeconds(15),
            )
            if (mounted.exitCode != 0 && mounted.output.contains(Regex("(?i)permission denied|operation not permitted"))) {
                assertEquals(0, mounted.exitCode, mounted.output)
            }
            assumeTrue(
                mounted.exitCode == 0 && mounted.output.trim() == token,
                "selected server cannot read the client's shared mount",
            )
        } finally {
            probe.toFile().deleteRecursively()
        }
        val compiler = System.getenv("CXX")?.takeIf { it.isNotBlank() } ?: "c++"
        val version = runContainerCommand(listOf(compiler, "--version"), Duration.ofSeconds(5))
        assertEquals(0, version.exitCode, "native compiler version unavailable: ${version.output}")
        val native = TierENativeFixtureBuild.prepare(
            root,
            System.getenv("TIER_E_SPLIT_NATIVE_BUILD_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of),
            compiler = "$compiler:${version.output.lineSequence().firstOrNull()}",
            flags = "usdt=${System.getenv("MAZEWALL_ENABLE_TIER_E_USDT") == "true"}",
        )
        return PreparedFixture(root, native, selectedImage, endpoint.resolvedUri!!)
    }

    @Test
    fun `rootful endpoint executes the Tier E image`(endpoint: ContainerEndpoint) {
        val container = "tier-e-endpoint-${UUID.randomUUID()}"
        val result = try {
            runContainerCommand(
                endpoint.command("run", "--rm", "--name", container, "--entrypoint", "/bin/sh", image, "-c", "printf tier-e-ready"),
                Duration.ofSeconds(90),
            )
        } finally {
            runContainerCommand(endpoint.command("rm", "-f", container), Duration.ofSeconds(10))
        }
        assertEquals(0, result.exitCode, result.output)
        assertTrue(result.output.contains("tier-e-ready"), result.output)
    }

    @Test
    fun `privileged failure exports caller-owned artifacts`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        val name = "tier-e-failed-${UUID.randomUUID()}"
        val volume = "$name-workspace"
        try {
            assertEquals(0, runContainerCommand(endpoint.command("volume", "create", volume)).exitCode)
            val create = runContainerCommand(
                endpoint.command(
                    "create",
                    "--name",
                    name,
                    "--privileged",
                    "--mount",
                    "type=volume,src=$volume,dst=/workspace",
                    "--entrypoint",
                    "/bin/sh",
                    image,
                    "-c",
                    "mkdir -p /artifacts; printf owned > /artifacts/result.txt; exit 7",
                ),
            )
            assertEquals(0, create.exitCode, create.output)
            assertTrue(runContainerCommand(endpoint.command("start", "-a", name)).exitCode != 0)
            val copied = runContainerCommand(endpoint.command("cp", "$name:/artifacts/.", destination.toString()))
            assertEquals(0, copied.exitCode, copied.output)
            val file = destination.resolve("result.txt")
            assertEquals("owned", Files.readString(file))
            assertEquals(Files.getOwner(destination), Files.getOwner(file))
        } finally {
            runContainerCommand(endpoint.command("rm", "-f", name))
            runContainerCommand(endpoint.command("volume", "rm", volume))
        }
    }

    @Test
    fun `timeout retires only its own privileged container`(endpoint: ContainerEndpoint) {
        val name = "tier-e-timeout-${UUID.randomUUID()}"
        try {
            val created = runContainerCommand(
                endpoint.command("create", "--name", name, "--privileged", "--entrypoint", "/bin/sh", image, "-c", "sleep 90"),
            )
            assertEquals(0, created.exitCode, created.output)
            val result = runContainerCommand(endpoint.command("start", "-a", name), Duration.ofSeconds(1))
            assertEquals(-1, result.exitCode)
            assertTrue(result.output.contains("timed out"), result.output)
        } finally {
            runContainerCommand(endpoint.command("rm", "-f", name), Duration.ofSeconds(10))
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `non-root coverage target and privileged collector recover the exact 72 paths`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        assumeTrue(
            endpoint.resolvedUri?.startsWith("unix://") == true,
            "split-topology fixture requires a local container host for PID and shared-volume identity",
        )
        val prepared = prepareFixture(endpoint)
        val runId = "tier-e-junit-${UUID.randomUUID()}"
        TierEContainerRun(endpoint, runId, destination).use {
            val result = runContainerCommand(
                listOf("bash", prepared.root.resolve("scripts/run_tier_e_split_privilege_fixture.sh").toString()),
                Duration.ofSeconds(120),
                mapOf(
                    "MAZEWALL_DOCKER_HOST" to prepared.host,
                    "TIER_E_CONTAINER_ENGINE" to endpoint.engine.name.lowercase(),
                    "TIER_E_SPLIT_RUN_ID" to runId,
                    "TIER_E_SPLIT_COVERAGE" to "1",
                    "TIER_E_SPLIT_COVERAGE_OPERATIONS" to "72",
                    "TIER_E_SPLIT_REUSE_BUILD" to "1",
                    "TIER_E_SPLIT_ARTIFACT_DIR" to destination.toString(),
                    "TIER_E_DOCKER_IMAGE" to prepared.image,
                    "TIER_E_SPLIT_NATIVE_BUILD_DIR" to prepared.native.toString(),
                ) +
                    if (endpoint.engine == ContainerEngine.PODMAN && endpoint.resolvedUri != null) {
                        mapOf("CONTAINER_HOST" to endpoint.resolvedUri)
                    } else {
                        emptyMap()
                    },
            )
            assertEquals(0, result.exitCode, result.output)
            assertTrue(Files.readString(destination.resolve("coverage-verdict.json")).contains("\"recovered_edges\":72"))
            assertTrue(Files.readString(destination.resolve("terminal-report.json")).contains("\"evidence_integrity\":\"COMPLETE\""))
            assertTrue(Files.readString(destination.resolve("verdict.env")).contains("policy_eligibility=false"))
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `killed fixture controller cannot leave its privileged collector running`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        assumeTrue(
            endpoint.resolvedUri?.startsWith("unix://") == true,
            "split fixture requires a proven local container host",
        )
        val prepared = prepareFixture(endpoint)
        val runId = "tier-e-killed-${UUID.randomUUID()}"
        val sentinel = "tier-e-unrelated-${UUID.randomUUID()}"
        val started = runContainerCommand(
            endpoint.command(
            "run",
                "-d",
                "--rm",
                "--name",
                sentinel,
                "--entrypoint",
                "/bin/sh",
                prepared.image,
                "-c",
                "sleep 90",
        ),
        )
        assertEquals(0, started.exitCode, started.output)
        val controller = ProcessBuilder("bash", prepared.root.resolve("scripts/run_tier_e_split_privilege_fixture.sh").toString())
            .directory(prepared.root.toFile())
            .redirectErrorStream(true)
            .redirectOutput(destination.resolve("killed-controller.log").toFile())
            .apply {
                environment().putAll(
                    mapOf(
                    "MAZEWALL_DOCKER_HOST" to prepared.host,
                    "TIER_E_CONTAINER_ENGINE" to endpoint.engine.name.lowercase(),
                    "TIER_E_SPLIT_RUN_ID" to runId,
                    "TIER_E_SPLIT_COVERAGE" to "1",
                    "TIER_E_SPLIT_COVERAGE_OPERATIONS" to "72",
                    "TIER_E_SPLIT_REUSE_BUILD" to "1",
                    "TIER_E_SPLIT_ARTIFACT_DIR" to destination.toString(),
                    "TIER_E_DOCKER_IMAGE" to prepared.image,
                    "TIER_E_SPLIT_NATIVE_BUILD_DIR" to prepared.native.toString(),
                ),
                )
                if (endpoint.engine == ContainerEngine.PODMAN && endpoint.resolvedUri != null) {
                    environment()["CONTAINER_HOST"] = endpoint.resolvedUri
                }
            }.start()
        try {
            TierEContainerRun(endpoint, runId, destination).use {
                awaitCondition(Duration.ofSeconds(30), "target and collector registered") {
                    runContainerCommand(endpoint.command("inspect", "$runId-target")).exitCode == 0 &&
                        runContainerCommand(endpoint.command("inspect", "$runId-collector")).exitCode == 0
                }
                controller.destroyForcibly()
                assertTrue(controller.waitFor(2, TimeUnit.SECONDS))
                assertEquals(
                    0,
                    runContainerCommand(endpoint.command("inspect", "$runId-collector")).exitCode,
                    "SIGKILL prevented the controller's EXIT trap",
                )
            }
            assertTrue(runContainerCommand(endpoint.command("inspect", "$runId-target")).exitCode != 0)
            assertTrue(runContainerCommand(endpoint.command("inspect", "$runId-collector")).exitCode != 0)
            assertEquals(
                0,
                runContainerCommand(endpoint.command("inspect", sentinel)).exitCode,
                "Kotlin owner must not reap unrelated containers",
            )
        } finally {
            controller.destroyForcibly()
            TierEContainerRun(endpoint, runId, destination).close()
            runContainerCommand(endpoint.command("rm", "-f", sentinel))
        }
    }
}
