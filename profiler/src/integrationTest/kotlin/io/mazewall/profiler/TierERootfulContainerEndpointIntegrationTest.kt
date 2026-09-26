package io.mazewall.profiler

import io.mazewall.profiler.testing.tierE.EnabledIfTierERootfulAvailable
import io.mazewall.profiler.tierE.tools.TierERunConfiguration
import io.mazewall.profiler.tierE.tools.controller.TierEContainerRun
import io.mazewall.profiler.tierE.tools.controller.TierEFixtureLifecycle
import io.mazewall.profiler.tierE.tools.controller.TierENativeFixtureBuild
import io.mazewall.profiler.tierE.tools.controller.TierESplitFixtureController
import io.mazewall.testing.awaitCondition
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import io.mazewall.testing.containers.runContainerCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Short, remote-safe rootful engine smoke; no host mounts or host PID assumptions. */
@EnabledIfTierERootfulAvailable
@Tag("rootful-container")
class TierERootfulContainerEndpointIntegrationTest {
    private val image: String
        get() = System.getenv("TIER_E_DOCKER_IMAGE")?.takeIf { it.isNotBlank() } ?: "mazewall-tier-e-rootful:latest"

    private data class PreparedFixture(
        val root: Path,
        val native: Path,
        val image: String,
        val host: String,
        val configuration: TierERunConfiguration,
    )

    private fun prepareFixture(endpoint: ContainerEndpoint): PreparedFixture {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("scripts/run_tier_e_split_privilege_fixture.sh")) }
        val configuration = TierERunConfiguration.load(
            root.resolve("config/testing/tier-e/smoke.json"),
            environment = mapOf("TIER_E_DOCKER_IMAGE" to (System.getenv("TIER_E_DOCKER_IMAGE") ?: "")),
            overrides = mapOf(
                "container.engine" to endpoint.engine.name.lowercase(),
                "container.uri" to requireNotNull(endpoint.resolvedUri) { "Tier E fixture requires resolved local endpoint" },
            ),
        )
        val selectedImage = configuration.container.image
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
            compiler = compiler,
            flags = "usdt=${System.getenv("MAZEWALL_ENABLE_TIER_E_USDT") == "true"};compiler-version=${version.output.lineSequence().firstOrNull()}",
        )
        return PreparedFixture(root, native, selectedImage, requireNotNull(configuration.container.uri), configuration)
    }

    private fun withOwnedFixture(
        endpoint: ContainerEndpoint,
        prepared: PreparedFixture,
        runId: String,
        destination: Path,
        environmentExtra: Map<String, String> = emptyMap(),
        extraCleanup: () -> Unit = {},
        exercise: (Process) -> Unit,
    ) {
        val creationLock = destination.resolve("$runId.creation.lock")
        val creationState = destination.resolve("$runId.creation.lock.state")
        Files.writeString(creationState, "open\n")
        val shared = Files.createTempDirectory("tier-e-owned-$runId-")
        val owner = TierEContainerRun(endpoint, runId, destination, ownedShared = shared)
        TierEFixtureLifecycle(owner, creationLock, extraCleanup = extraCleanup).run(start = {
            ProcessBuilder("bash", prepared.root.resolve("scripts/run_tier_e_split_privilege_fixture.sh").toString())
                .directory(prepared.root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(destination.resolve("$runId.controller.log").toFile())
                .apply {
                    environment().putAll(
                        mapOf(
                        "MAZEWALL_DOCKER_HOST" to prepared.host,
                        "TIER_E_CONTAINER_ENGINE" to endpoint.engine.name.lowercase(),
                        "TIER_E_SPLIT_RUN_ID" to runId,
                        "TIER_E_SPLIT_CREATE_LOCK" to creationLock.toString(),
                        "TIER_E_SPLIT_CREATE_STATE" to creationState.toString(),
                        "TIER_E_SPLIT_SHARED_DIR" to shared.toString(),
                        "TIER_E_SPLIT_COVERAGE" to if (prepared.configuration.fixture.coverage) "1" else "0",
                        "TIER_E_SPLIT_COVERAGE_OPERATIONS" to prepared.configuration.fixture.operations
                            .toString(),
                        "TIER_E_SPLIT_EMISSION_MODE" to prepared.configuration.fixture.emissionMode,
                        "TIER_E_SPLIT_RECOVERY_MODE" to prepared.configuration.fixture.recoveryMode,
                        "TIER_E_SPLIT_REUSE_BUILD" to "1",
                        "TIER_E_SPLIT_ARTIFACT_DIR" to destination.toString(),
                        "TIER_E_DOCKER_IMAGE" to prepared.image,
                        "TIER_E_SPLIT_NATIVE_BUILD_DIR" to prepared.native.toString(),
                    ),
                    )
                    if (endpoint.engine == ContainerEngine.PODMAN && endpoint.resolvedUri != null) {
                        environment()["CONTAINER_HOST"] = endpoint.resolvedUri
                    }
                    environment().putAll(environmentExtra)
                }.also {
                    Files.writeString(destination.resolve("$runId.resolved-config.json"), prepared.configuration.resolvedJson() + "\n")
                }.start()
        }, exercise = exercise)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `Kotlin owned two-container fixture recovers exact 72 declared paths`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        assumeTrue(endpoint.resolvedUri?.startsWith("unix://") == true)
        val prepared = prepareFixture(endpoint)
        TierESplitFixtureController.run(prepared.configuration, endpoint, prepared.root, destination, prepared.native)
        assertTrue(Files.readString(destination.resolve("coverage-verdict.json")).contains("\"recovered_edges\":72"))
        assertTrue(Files.readString(destination.resolve("terminal-report.json")).contains("\"evidence_integrity\":\"COMPLETE\""))
        assertTrue(Files.readString(destination.resolve("verdict.env")).contains("policy_eligibility=false"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `Kotlin owned coverage baseline runs without agent or collector`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        assumeTrue(endpoint.resolvedUri?.startsWith("unix://") == true)
        val prepared = prepareFixture(endpoint)
        val baseline = prepared.configuration.copy(scenario = prepared.configuration.scenario.copy(baseline = true))
        TierESplitFixtureController.run(baseline, endpoint, prepared.root, destination, prepared.native)
        assertTrue(Files.readString(destination.resolve("verdict.env")).contains("coverage_verdict=NOT_APPLICABLE"))
        assertTrue(Files.readString(destination.resolve("summary.json")).contains("\"mode\":\"baseline\""))
        assertTrue(!Files.exists(destination.resolve("terminal-report.json")))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `Kotlin owned short benchmark reconciles exact operations`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        assumeTrue(endpoint.resolvedUri?.startsWith("unix://") == true)
        val prepared = prepareFixture(endpoint)
        val benchmark = prepared.configuration.copy(
            fixture = prepared.configuration.fixture.copy(coverage = false),
            scenario = prepared.configuration.scenario.copy(benchmark = true),
        )
        TierESplitFixtureController.run(benchmark, endpoint, prepared.root, destination, prepared.native)
        assertTrue(Files.readString(destination.resolve("operation-verdict.json")).contains("\"operation_coverage_verdict\":\"PASS\""))
        assertTrue(Files.readString(destination.resolve("terminal-report.json")).contains("\"evidence_integrity\":\"COMPLETE\""))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `Kotlin owned owner-only control socket rejects target and unrelated users`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        assumeTrue(endpoint.resolvedUri?.startsWith("unix://") == true)
        val prepared = prepareFixture(endpoint)
        val restricted = prepared.configuration.copy(scenario = prepared.configuration.scenario.copy(socketAccess = "owner-only"))
        TierESplitFixtureController.run(restricted, endpoint, prepared.root, destination, prepared.native)
        assertTrue(Files.readString(destination.resolve("verdict.env")).contains("socket_access=OWNER_ONLY"))
        assertTrue(
            Files.readString(destination.resolve("target-user-denied.log")).contains("Permission denied") ||
            Files.readString(destination.resolve("target-user-denied.log")).contains("AccessDeniedException"),
        )
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `Kotlin owned stale identity is rejected before workload attribution`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        assumeTrue(endpoint.resolvedUri?.startsWith("unix://") == true)
        val prepared = prepareFixture(endpoint)
        val stale = prepared.configuration.copy(scenario = prepared.configuration.scenario.copy(identityCase = "stale"))
        TierESplitFixtureController.run(stale, endpoint, prepared.root, destination, prepared.native)
        val verdict = Files.readString(destination.resolve("verdict.env"))
        assertTrue(verdict.contains("terminal_reason=STALE_PROCESS_IDENTITY"))
        assertTrue(verdict.contains("policy_eligibility=false"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `Kotlin owned simulated PID reuse closes the session incomplete`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        assumeTrue(endpoint.resolvedUri?.startsWith("unix://") == true)
        val prepared = prepareFixture(endpoint)
        val reused = prepared.configuration.copy(
            fixture = prepared.configuration.fixture.copy(coverage = false),
            scenario = prepared.configuration.scenario.copy(identityCase = "simulated-reuse"),
        )
        TierESplitFixtureController.run(reused, endpoint, prepared.root, destination, prepared.native)
        val verdict = Files.readString(destination.resolve("verdict.env"))
        assertTrue(verdict.contains("terminal_reason=PROCESS_IDENTITY_REUSED"))
        assertTrue(verdict.contains("policy_eligibility=false"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `Kotlin owned fault fixture retains exact coverage and incomplete ring transport`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        assumeTrue(endpoint.resolvedUri?.startsWith("unix://") == true)
        val prepared = prepareFixture(endpoint)
        val fault = TierERunConfiguration.load(
            prepared.root.resolve("config/testing/tier-e/fault-recovery.json"),
            environment = emptyMap(),
                overrides = mapOf(
                    "container.engine" to endpoint.engine.name.lowercase(),
                "container.uri" to requireNotNull(endpoint.resolvedUri),
                    "container.image" to prepared.image,
                ),
        )
        TierESplitFixtureController.run(fault, endpoint, prepared.root, destination, prepared.native)
        assertTrue(Files.readString(destination.resolve("coverage-verdict.json")).contains("\"recovered_edges\":72"))
        assertTrue(Files.readString(destination.resolve("terminal-report.json")).contains("\"evidence_integrity\":\"INCOMPLETE\""))
        assertTrue(Files.readString(destination.resolve("verdict.env")).contains("ring_transport=INCOMPLETE"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `Kotlin owned unclosed artifact is rejected without policy eligibility`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        assumeTrue(endpoint.resolvedUri?.startsWith("unix://") == true)
        val prepared = prepareFixture(endpoint)
        val negative = prepared.configuration.copy(
            fixture = prepared.configuration.fixture.copy(coverage = false),
            scenario = prepared.configuration.scenario.copy(negativeArtifact = "unclosed"),
        )
        TierESplitFixtureController.run(negative, endpoint, prepared.root, destination, prepared.native)
        assertTrue(Files.readString(destination.resolve("terminal-report.json")).contains("\"evidence_integrity\":\"INCOMPLETE\""))
        assertTrue(Files.readString(destination.resolve("verdict.env")).contains("negative_artifact_case=unclosed"))
        assertTrue(Files.readString(destination.resolve("verdict.env")).contains("policy_eligibility=false"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `Kotlin owned delayed closed artifact is admitted after target exit`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        assumeTrue(endpoint.resolvedUri?.startsWith("unix://") == true)
        val prepared = prepareFixture(endpoint)
        val delayed = prepared.configuration.copy(scenario = prepared.configuration.scenario.copy(delayedArtifact = true))
        TierESplitFixtureController.run(delayed, endpoint, prepared.root, destination, prepared.native)
        assertTrue(Files.readString(destination.resolve("terminal-report.json")).contains("\"evidence_integrity\":\"COMPLETE\""))
        assertTrue(Files.readString(destination.resolve("verdict.env")).contains("policy_eligibility=false"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `Kotlin owned allowed incomplete flag uses explicit coverage verdict`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        assumeTrue(endpoint.resolvedUri?.startsWith("unix://") == true)
        val prepared = prepareFixture(endpoint)
        val allowed = prepared.configuration.copy(scenario = prepared.configuration.scenario.copy(allowIncomplete = true))
        TierESplitFixtureController.run(allowed, endpoint, prepared.root, destination, prepared.native)
        val verdict = Files.readString(destination.resolve("verdict.env"))
        assertTrue(verdict.contains("coverage_verdict=PASS"))
        assertTrue(verdict.contains("policy_eligibility=false"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "malformed", "symlink", "session-mismatch"])
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `Kotlin owned negative artifact keeps terminal incomplete`(
        scenario: String,
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        assumeTrue(endpoint.resolvedUri?.startsWith("unix://") == true)
        val prepared = prepareFixture(endpoint)
        val negative = prepared.configuration.copy(
            fixture = prepared.configuration.fixture.copy(coverage = false),
            scenario = prepared.configuration.scenario.copy(negativeArtifact = scenario),
        )
        TierESplitFixtureController.run(negative, endpoint, prepared.root, destination, prepared.native)
        assertTrue(Files.readString(destination.resolve("verdict.env")).contains("negative_artifact_case=$scenario"))
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
    fun `failed real log export retains stopped container and its recoverable logs`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        val runId = "tier-e-log-failed-${UUID.randomUUID()}"
        val name = "$runId-target"
        val blockedDestination = destination.resolve("blocked")
        Files.writeString(blockedDestination, "not a directory")
        try {
            val launched = runContainerCommand(
                endpoint.command(
                    "run",
                    "-d",
                    "--name",
                    name,
                "--entrypoint",
                    "/bin/sh",
                    image,
                    "-c",
                    "printf recovery-marker; sleep 90",
                ),
            )
            assertEquals(0, launched.exitCode, launched.output)
            val error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                TierEContainerRun(endpoint, runId, blockedDestination).close()
            }
            assertTrue(error.message!!.contains("retained for recovery: $name"), error.message)
            assertEquals("false", runContainerCommand(endpoint.command("inspect", "--format", "{{.State.Running}}", name)).output.trim())
            assertTrue(runContainerCommand(endpoint.command("logs", name)).output.contains("recovery-marker"))
        } finally {
            runContainerCommand(endpoint.command("rm", "-f", name))
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
        withOwnedFixture(endpoint, prepared, runId, destination) { controller ->
            assertTrue(controller.waitFor(120, TimeUnit.SECONDS), "split fixture controller timed out")
            assertEquals(0, controller.exitValue(), Files.readString(destination.resolve("$runId.controller.log")).takeLast(4096))
            assertEquals(
                0,
                runContainerCommand(endpoint.command("inspect", "$runId-target")).exitCode,
                "Kotlin owner must receive the source container before shell cleanup",
            )
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
        withOwnedFixture(endpoint, prepared, runId, destination, extraCleanup = {
            val removed = runContainerCommand(endpoint.command("rm", "-f", sentinel))
            check(removed.exitCode == 0 || removed.output.contains("no such container", ignoreCase = true)) {
                "Sentinel cleanup failed: ${removed.output}"
            }
        }) { controller ->
            val started = runContainerCommand(
                endpoint.command("run", "-d", "--rm", "--name", sentinel, "--entrypoint", "/bin/sh", prepared.image, "-c", "sleep 90"),
            )
            assertEquals(0, started.exitCode, started.output)
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
            assertEquals(0, runContainerCommand(endpoint.command("inspect", sentinel)).exitCode)
        }
        assertTrue(runContainerCommand(endpoint.command("inspect", "$runId-target")).exitCode != 0)
        assertTrue(runContainerCommand(endpoint.command("inspect", "$runId-collector")).exitCode != 0)
        assertTrue(runContainerCommand(endpoint.command("inspect", sentinel)).exitCode != 0)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `startup cancellation settles an in flight real Docker creation before cleanup`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        assumeTrue(endpoint.engine == ContainerEngine.DOCKER && endpoint.resolvedUri?.startsWith("unix://") == true)
        val prepared = prepareFixture(endpoint)
        val runId = "tier-e-late-${UUID.randomUUID()}"
        val gate = destination.resolve("gate.fifo")
        val entered = destination.resolve("creation-entered")
        val created = destination.resolve("creation-completed")
        assertEquals(0, runContainerCommand(listOf("mkfifo", gate.toString())).exitCode)
        val bin = Files.createDirectories(destination.resolve("bin"))
        val docker = bin.resolve("docker")
        val realDocker = runContainerCommand(listOf("sh", "-c", "command -v docker")).output.trim()
        assertTrue(Files.isExecutable(Path.of(realDocker)), "Docker client executable unavailable: $realDocker")
        Files.writeString(
            docker,
            """#!/usr/bin/env bash
if [[ " ${'$'}* " == *" run -d --name $runId-target "* ]]; then
    touch "${'$'}TIER_E_TEST_CREATION_ENTERED"
    read -r release < "${'$'}TIER_E_TEST_CREATION_GATE"
    "$realDocker" "${'$'}@"
    status=${'$'}?
    if [[ "${'$'}status" == 0 ]]; then touch "${'$'}TIER_E_TEST_CREATION_COMPLETED"; fi
    exit "${'$'}status"
fi
 exec "$realDocker" "${'$'}@"
""",
        )
        assertTrue(docker.toFile().setExecutable(true))
        var releaser: Thread? = null
        val releaseFailure = AtomicReference<Throwable?>()
        try {
            val failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                withOwnedFixture(
                    endpoint,
                    prepared,
                    runId,
                    destination,
                    environmentExtra = mapOf(
                    "PATH" to "$bin:${System.getenv("PATH")}",
                    "TIER_E_TEST_CREATION_ENTERED" to entered.toString(),
                    "TIER_E_TEST_CREATION_GATE" to gate.toString(),
                    "TIER_E_TEST_CREATION_COMPLETED" to created.toString(),
                ),
                ) { controller ->
                    awaitCondition(Duration.ofSeconds(30), "Docker creation entered under lock") { Files.exists(entered) }
                    releaser = Thread {
                        try {
                            awaitCondition(Duration.ofSeconds(5), "controller stopped before releasing creation") { !controller.isAlive }
                            Files.writeString(gate, "release\n")
                        } catch (error: Throwable) {
                            releaseFailure.set(error)
                        }
                    }.also { it.start() }
                    error("cancel during owned container creation")
                }
            }
            assertTrue(failure.message!!.contains("cancel during owned container creation"))
            assertTrue(failure.suppressed.isEmpty(), "teardown errors: ${failure.suppressed.contentToString()}")
            releaser?.join(5_000)
            assertTrue(releaser?.isAlive == false)
            assertTrue(releaseFailure.get() == null, "creation release failed: ${releaseFailure.get()}")
            assertTrue(Files.exists(created), "Docker must complete the delayed creation before name reconciliation")
            assertTrue(runContainerCommand(endpoint.command("inspect", "$runId-target")).exitCode != 0)
            assertTrue(runContainerCommand(endpoint.command("inspect", "$runId-collector")).exitCode != 0)
        } finally {
            releaser?.interrupt()
            runContainerCommand(endpoint.command("rm", "-f", "$runId-target", "$runId-collector"))
        }
    }

    @Test
    fun `interrupted fixture teardown still reaps unrelated sentinel`(
        endpoint: ContainerEndpoint,
        @TempDir destination: Path,
    ) {
        assumeTrue(endpoint.resolvedUri?.startsWith("unix://") == true)
        val prepared = prepareFixture(endpoint)
        val runId = "tier-e-interrupt-${UUID.randomUUID()}"
        val sentinel = "tier-e-unrelated-${UUID.randomUUID()}"
        try {
            val failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                withOwnedFixture(endpoint, prepared, runId, destination, extraCleanup = {
                    val result = runContainerCommand(endpoint.command("rm", "-f", sentinel))
                    check(result.exitCode == 0) { result.output }
                }) {
                    assertEquals(
                        0,
                        runContainerCommand(
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
                        ).exitCode,
                    )
                    Thread.currentThread().interrupt()
                    error("controller interrupted")
                }
            }
            assertTrue(failure.message!!.contains("controller interrupted"))
            assertTrue(failure.suppressed.isEmpty(), "teardown errors: ${failure.suppressed.contentToString()}")
            assertTrue(Thread.currentThread().isInterrupted)
            Thread.interrupted()
            assertTrue(runContainerCommand(endpoint.command("inspect", sentinel)).exitCode != 0)
        } finally {
            Thread.interrupted()
            runContainerCommand(endpoint.command("rm", "-f", sentinel))
        }
    }
}
