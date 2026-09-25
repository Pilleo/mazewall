package io.mazewall.profiler

import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import io.mazewall.testing.containers.EnabledIfRootfulContainerAvailable
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
            endpoint.uri?.startsWith("unix://") == true,
            "split-topology fixture requires a local container host for PID and shared-volume identity",
        )
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("scripts/run_tier_e_split_privilege_fixture.sh")) }
        val native = Path.of(System.getenv("TIER_E_SPLIT_NATIVE_BUILD_DIR") ?: "/tmp/jseccomp-profiler-native-build")
        if (!Files.isRegularFile(native.resolve("CMakeCache.txt"))) {
            val configured = runContainerCommand(
                listOf("cmake", "-S", root.resolve("profiler-native").toString(), "-B", native.toString(), "-DCMAKE_BUILD_TYPE=Release"),
                Duration.ofSeconds(120),
            )
            assertEquals(0, configured.exitCode, configured.output)
        }
        val built = runContainerCommand(listOf("cmake", "--build", native.toString(), "-j2"), Duration.ofSeconds(120))
        assertEquals(0, built.exitCode, built.output)
        val selectedImage = System.getenv("TIER_E_DOCKER_IMAGE")?.takeIf { it.isNotBlank() } ?: "mazewall-tier-e-rootful:latest"
        val imageAvailable = runContainerCommand(endpoint.command("image", "inspect", selectedImage))
        if (imageAvailable.exitCode != 0) {
            val imageBuild = runContainerCommand(
                endpoint.command("build", "-t", selectedImage, "-f", root.resolve("infra/dev/Containerfile").toString(), root.toString()),
                Duration.ofMinutes(5),
            )
            assertEquals(0, imageBuild.exitCode, imageBuild.output)
        }
        val host = endpoint.uri ?: if (endpoint.engine == ContainerEngine.DOCKER) "unix:///var/run/docker.sock" else ""
        val result = runContainerCommand(
            listOf("bash", root.resolve("scripts/run_tier_e_split_privilege_fixture.sh").toString()),
            Duration.ofSeconds(120),
            mapOf(
                "MAZEWALL_DOCKER_HOST" to host,
                "TIER_E_CONTAINER_ENGINE" to endpoint.engine.name.lowercase(),
                "TIER_E_SPLIT_COVERAGE" to "1",
                "TIER_E_SPLIT_COVERAGE_OPERATIONS" to "72",
                "TIER_E_SPLIT_REUSE_BUILD" to "1",
                "TIER_E_SPLIT_ARTIFACT_DIR" to destination.toString(),
                "TIER_E_DOCKER_IMAGE" to selectedImage,
                "TIER_E_SPLIT_NATIVE_BUILD_DIR" to native.toString(),
            ) +
                if (endpoint.engine == ContainerEngine.PODMAN && endpoint.uri != null) {
                    mapOf("CONTAINER_HOST" to endpoint.uri)
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
