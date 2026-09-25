package io.mazewall.profiler

import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.EnabledIfRootfulContainerAvailable
import io.mazewall.testing.containers.runContainerCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** Short, remote-safe rootful engine smoke; no host mounts or host PID assumptions. */
@EnabledIfRootfulContainerAvailable
@Tag("rootful-container")
class TierERootfulContainerEndpointIntegrationTest {
    @Test
    fun `rootful endpoint executes the Tier E image`(endpoint: ContainerEndpoint) {
        val image = System.getenv("TIER_E_DOCKER_IMAGE") ?: "mazewall-tier-e-rootful:latest"
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
        val image = System.getenv("TIER_E_DOCKER_IMAGE") ?: "mazewall-tier-e-rootful:latest"
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
        val image = System.getenv("TIER_E_DOCKER_IMAGE") ?: "mazewall-tier-e-rootful:latest"
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
}
