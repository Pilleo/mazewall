package io.mazewall.profiler

import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.EnabledIfRootfulContainerAvailable
import io.mazewall.testing.containers.runContainerCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/** Short, remote-safe rootful engine smoke; no host mounts or host PID assumptions. */
@EnabledIfRootfulContainerAvailable
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
}
