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
    fun `relative redirect directory cannot forge native absent CLI errno`() {
        ownedRedirectFixture("directory")
    }

    @Test
    fun `relative redirect permission failure cannot forge native absent CLI errno`() {
        ownedRedirectFixture("permission")
    }

    private fun ownedRedirectFixture(mode: String) {
        val root = java.nio.file.Files
            .createTempDirectory("container-redirect-contract-")
        val log = root.resolve("receipt.log")
        var acquired: Process? = null
        try {
            val builder = ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "-Xmx128m",
                "-cp",
                System.getProperty("java.class.path"),
                RedirectFixture::class.java.name,
                mode,
                root.toString(),
            ).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
            listOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").forEach { builder.environment().remove(it) }
            val child = builder.start().also { acquired = it }
            println("Owned redirect fixture pid=${child.pid()} private cwd=$root mode=$mode")
            assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "owned redirect fixture must complete within bound")
            val receipt = java.nio.file.Files
                .readString(log)
            println(receipt)
            assertEquals(0, child.exitValue(), receipt)
        } finally {
            try {
                acquired?.let { child ->
                    child.destroyForcibly()
                    assertTrue(child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS), "actual redirect fixture child must be reaped")
                    assertTrue(!child.isAlive)
                }
            } finally {
                java.nio.file.Files
                    .deleteIfExists(root.resolve("error=2,fixture"))
                java.nio.file.Files
                    .deleteIfExists(log)
                java.nio.file.Files
                    .deleteIfExists(root)
            }
        }
    }

    internal object RedirectFixture {
        @JvmStatic
        fun main(args: Array<String>) {
            check(
                java.nio.file.Path
                .of("")
                .toAbsolutePath()
                .normalize() == java.nio.file.Path
                    .of(args[1])
                    .toAbsolutePath()
                    .normalize(),
            ) {
                "redirect artifacts must be created only in the acquired private child cwd"
            }
            val output = java.nio.file.Path
                .of("error=2,fixture")
            try {
                when (args[0]) {
                    "directory" ->
                        java.nio.file.Files
                        .createDirectory(output)
                    "permission" -> {
                        java.nio.file.Files
                            .createFile(output)
                        java.nio.file.Files
                            .setPosixFilePermissions(output, emptySet())
                    }
                    else -> error("unknown redirect fixture mode")
                }
                var acquired: Process? = null
                val actual = try {
                    runCatching {
                        ProcessBuilder("/bin/true").redirectOutput(output.toFile()).start().also { acquired = it }
                    }.exceptionOrNull()
                } finally {
                    acquired?.let {
                        it.destroyForcibly()
                        check(it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
                        check(!it.isAlive)
                    }
                }
                check(actual is java.io.IOException && actual.cause is java.io.FileNotFoundException) {
                    "real redirect-open failure must be acquired, not invented native errno: $actual"
                }
                actual.printStackTrace(System.err)
                val seen = mutableListOf<ContainerEngine>()
                val discovery = ContainerEndpointDiscovery(
                    probe = CliEndpointProbe { command, timeout ->
                    seen += if (command.first() == "docker") ContainerEngine.DOCKER else ContainerEngine.PODMAN
                    runContainerCommand(listOf("/bin/true"), timeout, outputFile = output)
                },
                )
                val failure = runCatching { discovery.discover(EndpointConfig()) }.exceptionOrNull()
                check(failure is java.io.IOException && failure.cause is java.io.FileNotFoundException) {
                    "actual redirect-open failure must propagate instead of absent-CLI fallback: $failure; seen=$seen"
                }
                check(seen == listOf(ContainerEngine.DOCKER)) { "redirect failure must not try a fallback engine: $seen" }
                failure.printStackTrace(System.err)
            } finally {
                java.nio.file.Files
                    .deleteIfExists(output)
            }
        }
    }

    @Test
    fun `executable filename cannot forge absent CLI errno on actual EACCES`() {
        val root = java.nio.file.Files
            .createTempDirectory("container-launch-errno-")
        val executable = root.resolve("error=2,fixture")
        try {
            java.nio.file.Files
                .writeString(executable, "#!/bin/sh\nexit 0\n")
            java.nio.file.Files
                .setPosixFilePermissions(
                    executable,
                    java.nio.file.attribute.PosixFilePermissions
                    .fromString("rw-------"),
                )
            val actual = runCatching { ProcessBuilder(executable.toString()).start() }.exceptionOrNull()
            assertTrue(actual is java.io.IOException)
            actual?.printStackTrace(System.err)
            assertTrue(
                actual
                ?.cause
                ?.message
                .orEmpty()
                .matches(Regex("(?:error=13,.*|Exec failed, error: 13 \\(.*)")),
                    "actual nested launch evidence must prove EACCES: $actual",
            )
            val seen = mutableListOf<ContainerEngine>()
            val discovery = ContainerEndpointDiscovery(
                probe = CliEndpointProbe { command, timeout ->
                seen += if (command.first() == "docker") ContainerEngine.DOCKER else ContainerEngine.PODMAN
                runContainerCommand(listOf(executable.toString()), timeout)
            },
            )
            val failure = runCatching { discovery.discover(EndpointConfig()) }.exceptionOrNull()
            assertTrue(failure is java.io.IOException, "actual EACCES must propagate instead of absent-CLI fallback: $failure")
            assertTrue(
                failure
                ?.cause
                ?.message
                .orEmpty()
                .matches(Regex("(?:error=13,.*|Exec failed, error: 13 \\(.*)")),
            )
            assertEquals(listOf(ContainerEngine.DOCKER), seen, "permission failure must never select a fallback engine")
        } finally {
            java.nio.file.Files
                .deleteIfExists(executable)
            java.nio.file.Files
                .deleteIfExists(root)
        }
    }

    @Test
    fun `actual missing executable remains unavailable CLI control`() {
        val root = java.nio.file.Files
            .createTempDirectory("container-missing-cli-")
        try {
            val command = listOf(root.resolve("missing-cli").toString())
            val actual = runCatching { ProcessBuilder(command).start() }.exceptionOrNull()
            assertTrue(actual is java.io.IOException)
            actual?.printStackTrace(System.err)
            assertTrue(
                actual
                ?.cause
                ?.message
                .orEmpty()
                .matches(Regex("(?:error=2,.*|Exec failed, error: 2 \\(.*)")),
            )
            assertEquals(-1, runContainerCommand(command).exitCode)
        } finally {
            java.nio.file.Files
                .deleteIfExists(root)
        }
    }

    @Test
    fun `owned command cancellation propagates evidence without trying another endpoint`() {
        val root = java.nio.file.Files
            .createTempDirectory("container-cancel-")
        val receipt = root.resolve("owned-pids")
        val seen = mutableListOf<ContainerEngine>()
        val failure = java.util.concurrent.atomic
            .AtomicReference<Throwable?>()
        val interrupted = java.util.concurrent.atomic
            .AtomicBoolean()
        val command = listOf("/bin/sh", "-c", "sleep 60 & child=\$!; printf '%s %s' \$\$ \$child > \"\$1\"; wait \$child", "fixture", receipt.toString())
        val discovery = ContainerEndpointDiscovery(
            probe = CliEndpointProbe { _, timeout ->
                seen += ContainerEngine.DOCKER
                runContainerCommand(command, timeout)
            },
        )
        val waiter = Thread {
            val outcome = runCatching { discovery.discover(EndpointConfig()) }
            failure.set(outcome.exceptionOrNull())
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        waiter.start()
        try {
            val deadline = System.nanoTime() +
                java.time.Duration
                .ofSeconds(5)
                .toNanos()
            while (!java.nio.file.Files
                .exists(receipt) &&
                System.nanoTime() < deadline
            ) {
                    Thread.sleep(10)
                }
            assertTrue(
                java.nio.file.Files
                .exists(receipt),
                    "owned process tree must be acquired before cancellation",
            )
        } finally {
            waiter.interrupt()
            waiter.join(8_000)
        }
        assertTrue(!waiter.isAlive, "command owner must complete bounded cleanup")
        assertTrue(failure.get() is InterruptedException, "cancellation must propagate its actual exception, not become endpoint unavailability: ${failure.get()}")
        assertTrue(interrupted.get(), "all cleanup must finish before interrupt restoration")
        assertEquals(listOf(ContainerEngine.DOCKER), seen, "cancellation must not try the next engine")
        java.nio.file.Files.readString(receipt).split(" ").forEach { pid ->
            assertTrue(!ProcessHandle.of(pid.toLong()).map(ProcessHandle::isAlive).orElse(false), "owned process $pid must be physically terminated")
        }
    }

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
    fun `generic explicit rootful Docker selection reaches the selected server`() {
        if (System.getenv("MAZEWALL_DOCKER_HOST") != "unix:///var/run/docker.sock") return
        val config = EndpointConfig.fromEnvironment(
            mapOf(
            "MAZEWALL_CONTAINER_ENGINE" to "docker",
            "MAZEWALL_CONTAINER_URI" to "unix:///var/run/docker.sock",
        ),
        )
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
        val result = discovery.discover(EndpointConfig.fromEnvironment(mapOf("MAZEWALL_CONTAINER_ENGINE" to "invalid")))
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
