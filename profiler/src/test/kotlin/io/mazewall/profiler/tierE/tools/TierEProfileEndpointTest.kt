package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierEProfileEndpointTest {
    @Test
    fun `local Docker endpoint selection rejects conflicting aliases before resources`() {
        val docker = "unix:///var/run/docker.sock"
        val podman = "unix:///run/user/1000/podman/podman.sock"
        assertEquals(docker, TierEProfileEndpoint.select(emptyMap(), null))
        assertEquals(docker, TierEProfileEndpoint.select(mapOf("DOCKER_HOST" to podman), null))
        assertThrows(IllegalArgumentException::class.java) {
            TierEProfileEndpoint.select(mapOf("MAZEWALL_DOCKER_HOST" to docker, "DOCKER_HOST" to podman), null)
        }
        assertEquals(
            docker,
            TierEProfileEndpoint.select(
            mapOf("MAZEWALL_DOCKER_HOST" to podman, "DOCKER_HOST" to podman),
                docker,
        ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TierEProfileEndpoint.select(emptyMap(), "ssh://remote")
        }
    }
}
