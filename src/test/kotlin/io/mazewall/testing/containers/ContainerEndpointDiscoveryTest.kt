package io.mazewall.testing.containers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request
import org.junit.platform.launcher.core.LauncherFactory

class ContainerEndpointDiscoveryTest {
    @Test
    fun `explicit rootless endpoint is skipped without fallback`() {
        val seen = mutableListOf<ContainerEndpoint>()
        val result = ContainerEndpointDiscovery { endpoint ->
            seen += endpoint
            EndpointProbeResult.Rootless
        }.discover(EndpointConfig(engine = ContainerEngine.PODMAN, uri = "unix:///run/podman/podman.sock"))
        assertTrue(result is EndpointSelection.Skipped)
        assertEquals(1, seen.size)
        assertEquals(ContainerEngine.PODMAN, seen.single().engine)
    }

    @Test
    fun `explicit rootful Podman is selected`() {
        val result = ContainerEndpointDiscovery { EndpointProbeResult.Rootful }
            .discover(EndpointConfig(engine = ContainerEngine.PODMAN, uri = "unix:///run/podman/podman.sock"))
        assertEquals(ContainerEngine.PODMAN, (result as EndpointSelection.Ready).endpoint.engine)
    }

    @Test
    fun `no available local endpoint is skipped with reason`() {
        val result = ContainerEndpointDiscovery { EndpointProbeResult.Unavailable("connection refused") }
            .discover(EndpointConfig())
        assertTrue(result is EndpointSelection.Skipped)
        assertTrue((result as EndpointSelection.Skipped).reason.contains("rootful"))
    }

    @Test
    fun `unavailable explicit endpoint never switches engines`() {
        val seen = mutableListOf<ContainerEndpoint>()
        val result = ContainerEndpointDiscovery { endpoint ->
            seen += endpoint
            EndpointProbeResult.Unavailable("unreachable")
        }.discover(EndpointConfig(engine = ContainerEngine.DOCKER, uri = "tcp://example.invalid:2375"))
        assertTrue(result is EndpointSelection.Skipped)
        assertEquals(1, seen.size)
    }

    @Test
    fun `JUnit exposes an informative skip reason when no rootful endpoint exists`() {
        if (ContainerEndpointDiscovery().discover() is EndpointSelection.Ready) return
        var reason: String? = null
        val listener = object : TestExecutionListener {
            override fun executionSkipped(
                testIdentifier: TestIdentifier,
                skipReason: String,
            ) {
                reason = skipReason
            }
        }
        val launcher = LauncherFactory.create()
        launcher.registerTestExecutionListeners(listener)
        launcher.execute(request().selectors(selectClass(ConditionFixture::class.java)).build())
        assertTrue(reason?.contains("No usable rootful container endpoint") == true, reason)
    }

    @Test
    fun `unrecognized Docker security response cannot prove rootfulness`() {
        val probe = CliEndpointProbe { _, _ -> ContainerCommandResult(0, "null") }
        val result = probe.probe(ContainerEndpoint(ContainerEngine.DOCKER, "unix:///var/run/docker.sock"))
        assertTrue(result is EndpointProbeResult.Unavailable)
    }

    @Test
    fun `server rootless status is checked for both engines`() {
        val dockerRootless = CliEndpointProbe { _, _ -> ContainerCommandResult(0, "[\"name=rootless\"]") }
        assertEquals(EndpointProbeResult.Rootless, dockerRootless.probe(ContainerEndpoint(ContainerEngine.DOCKER, null)))
        val podmanRootful = CliEndpointProbe { _, _ -> ContainerCommandResult(0, "false") }
        assertEquals(EndpointProbeResult.Rootful, podmanRootful.probe(ContainerEndpoint(ContainerEngine.PODMAN, null)))
    }

    @Test
    fun `Docker standard rootful security options admit the server`() {
        val options = "[\"name=seccomp,profile=builtin\",\"name=cgroupns\"]"
        val probe = CliEndpointProbe { _, _ -> ContainerCommandResult(0, options) }
        assertEquals(EndpointProbeResult.Rootful, probe.probe(ContainerEndpoint(ContainerEngine.DOCKER, "unix:///var/run/docker.sock")))
    }

    @Test
    fun `explicit rootful Docker selection reaches the same server as the integration gate`() {
        if (System.getenv("MAZEWALL_DOCKER_HOST") != "unix:///var/run/docker.sock") return
        val config = EndpointConfig.fromEnvironment()
        assertEquals(ContainerEngine.DOCKER, config.engine)
        assertEquals("unix:///var/run/docker.sock", config.uri)
        val result = ContainerEndpointDiscovery().discover(config)
        assertTrue(result is EndpointSelection.Ready, result.toString())
    }

    @Test
    fun `default Docker context resolves local URI without discarding selected context`() {
        val discovery = ContainerEndpointDiscovery(
            resolveDefault = { "unix:///var/run/docker.sock" },
            probe = EndpointProbe { EndpointProbeResult.Rootful },
        )
        val endpoint = (discovery.discover(EndpointConfig()) as EndpointSelection.Ready).endpoint
        assertEquals(ContainerEngine.DOCKER, endpoint.engine)
        assertEquals(null, endpoint.uri, "CLI must keep using selected Docker context")
        assertEquals("unix:///var/run/docker.sock", endpoint.resolvedUri)
        assertEquals(listOf("docker", "info"), endpoint.command("info"))
    }

    @Test
    fun `remote default context stays remote even when the client has a local Docker socket`() {
        val discovery = ContainerEndpointDiscovery(
            resolveDefault = { "ssh://operator@node.example" },
            probe = EndpointProbe { EndpointProbeResult.Rootful },
        )
        val endpoint = (discovery.discover(EndpointConfig()) as EndpointSelection.Ready).endpoint
        assertEquals("ssh://operator@node.example", endpoint.resolvedUri)
        assertEquals(null, endpoint.uri)
    }

    @Test
    fun `invalid explicit engine does not silently fall back to Docker`() {
        var probes = 0
        val discovery = ContainerEndpointDiscovery(
            probe = EndpointProbe {
                probes++
                EndpointProbeResult.Rootful
            },
        )
        val result = discovery.discover(EndpointConfig.fromEnvironment(mapOf("TIER_E_CONTAINER_ENGINE" to "invalid")))
        assertTrue(result is EndpointSelection.Skipped)
        assertTrue((result as EndpointSelection.Skipped).reason.contains("invalid"))
        assertEquals(0, probes)
    }

    @Test
    fun `Podman connection environment selects Podman rather than Docker`() {
        val config = EndpointConfig.fromEnvironment(mapOf("CONTAINER_HOST" to "unix:///run/podman/podman.sock"))
        assertEquals(ContainerEngine.PODMAN, config.engine)
        assertEquals("unix:///run/podman/podman.sock", config.uri)
    }

    @EnabledIfRootfulContainerAvailable
    class ConditionFixture {
        @Test
        fun `condition injects the admitted endpoint`(endpoint: ContainerEndpoint) {
            assertTrue(endpoint.engine in setOf(ContainerEngine.DOCKER, ContainerEngine.PODMAN))
            assertTrue(!endpoint.resolvedUri.isNullOrBlank())
        }
    }
}
