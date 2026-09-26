package io.mazewall.profiler.testing.tierE

import io.mazewall.profiler.tierE.tools.TierERunConfiguration
import io.mazewall.profiler.tierE.tools.controller.TierESplitFixtureController
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TierESplitFixtureControllerTest {
    @Test
    fun `real PID reuse requires v2 hybrid registered workflow before any container is created`() {
        val root = Files.createTempDirectory("tier-e-real-reuse-preflight-")
        val output = Files.createTempDirectory("tier-e-real-reuse-output-")
        try {
            Files.createDirectory(root.resolve("profiler-native"))
            val profile = root.resolve("profile.json")
            Files.writeString(
                profile,
                """{"schema_version":1,"container":{"engine":"docker","uri":"unix:///var/run/docker.sock","image":"fixture"},"fixture":{"coverage":true,"operations":72,"emission_mode":"full-stream","recovery_mode":"disabled"},"measurement":{"forks":1,"workload_cpu":0,"collector_cpu":1}}""",
            )
            val config = TierERunConfiguration
                .load(
                profile,
                environment = emptyMap(),
            ).copy(scenario = TierERunConfiguration.load(profile, environment = emptyMap()).scenario.copy(identityCase = "real-reuse"))
            val failure = assertThrows(IllegalArgumentException::class.java) {
                TierESplitFixtureController.run(
                    config,
                    ContainerEndpoint(ContainerEngine.DOCKER, "unix:///var/run/docker.sock"),
                    root,
                    output,
                )
            }
            assertTrue(failure.message!!.contains("v2 hybrid"))
            assertTrue(Files.list(output).use { it.findAny().isEmpty })
        } finally {
            root.toFile().deleteRecursively()
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `externally owned fixture rejects mismatched and symlinked admission without writing artifacts`() {
        val repository = Files.createTempDirectory("tier-e-owned-preflight-")
        val artifacts = Files.createTempDirectory("tier-e-owned-artifacts-")
        val scratch = Files.createTempDirectory("tier-e-owned-test-run-")
        val runId = "test-run"
        val lock = artifacts.resolve("$runId.creation.lock")
        val state = artifacts.resolve("$runId.creation.lock.state")
        val configFile = repository.resolve("config.json")
        try {
            Files.createDirectory(repository.resolve("profiler-native"))
            Files.writeString(
                configFile,
                """{"schema_version":1,"container":{"engine":"docker","uri":"unix:///var/run/docker.sock","image":"fixture"},"fixture":{"coverage":true,"operations":72,"emission_mode":"full-stream","recovery_mode":"disabled"},"measurement":{"forks":1,"workload_cpu":0,"collector_cpu":1}}""",
            )
            val config = TierERunConfiguration.load(configFile, environment = emptyMap())
            val endpoint = ContainerEndpoint(ContainerEngine.DOCKER, "unix:///var/run/docker.sock")
            Files.writeString(state, "closed\n")

            fun attempt(
                shared: Path,
                admission: Path = lock,
            ): IllegalArgumentException =
                assertThrows(IllegalArgumentException::class.java) {
                    TierESplitFixtureController.run(
                        config,
                        endpoint,
                        repository,
                        artifacts,
                        ownedRunId = runId,
                        ownedShared = shared,
                        creationLock = admission,
                        creationState = state,
                    )
                }

            assertTrue(attempt(scratch).message!!.contains("open"))
            Files.writeString(state, "open\n")
            val partial = assertThrows(IllegalArgumentException::class.java) {
                TierESplitFixtureController.run(
                    config,
                    endpoint,
                    repository,
                    artifacts,
                    ownedRunId = runId,
                    ownedShared = scratch,
                    creationState = state,
                )
            }
            assertTrue(partial.message!!.contains("requires"))
            val invalidRunId = assertThrows(IllegalArgumentException::class.java) {
                TierESplitFixtureController.run(
                    config,
                    endpoint,
                    repository,
                    artifacts,
                    ownedRunId = "../escape",
                    ownedShared = scratch,
                    creationLock = lock,
                    creationState = state,
                )
            }
            assertTrue(invalidRunId.message!!.contains("run ID"))
            assertTrue(attempt(scratch, artifacts.resolve("wrong.lock")).message!!.contains("lock"))
            val actualState = artifacts.resolve("$runId.creation.lock.actual")
            Files.move(state, actualState)
            Files.createSymbolicLink(state, actualState.fileName)
            assertTrue(attempt(scratch).message!!.contains("open"))
            Files.delete(state)
            Files.move(actualState, state)
            val linkedScratch = scratch.parent.resolve("tier-e-owned-$runId-linked")
            Files.createSymbolicLink(linkedScratch, scratch)
            assertTrue(attempt(linkedScratch).message!!.contains("shared"))
            Files.delete(linkedScratch)
            Files.createSymbolicLink(lock, artifacts.resolve("outside.lock"))
            assertTrue(attempt(scratch).message!!.contains("lock"))
            Files.delete(lock)
            Files.writeString(artifacts.resolve("resolved-config.json"), "operator-data")
            assertTrue(attempt(scratch).message!!.contains("output"))
            assertTrue(Files.readString(artifacts.resolve("resolved-config.json")) == "operator-data")
            assertTrue(!Files.exists(artifacts.resolve("$runId.shared-path")))
        } finally {
            repository.toFile().deleteRecursively()
            artifacts.toFile().deleteRecursively()
            scratch.toFile().deleteRecursively()
        }
    }

    @Test
    fun `fixture rejects remote endpoint before preparing binaries or containers`() {
        val repository = Files.createTempDirectory("tier-e-fixture-preflight-")
        val artifacts = Files.createTempDirectory("tier-e-fixture-artifacts-")
        try {
            val config = TierERunConfiguration.load(
                repository.resolve("config.json").also {
                Files.writeString(
                    it,
                    """{"schema_version":1,"container":{"engine":"docker","uri":"ssh://guest","image":"fixture"},"fixture":{"coverage":true,"operations":72,"emission_mode":"full-stream","recovery_mode":"disabled"},"measurement":{"forks":1,"workload_cpu":0,"collector_cpu":1}}""",
                )
            },
                environment = emptyMap(),
            )
            val error = assertThrows(IllegalArgumentException::class.java) {
                TierESplitFixtureController.run(config, ContainerEndpoint(ContainerEngine.DOCKER, "ssh://guest"), repository, artifacts)
            }
            assertTrue(error.message!!.contains("local"))
            assertTrue(Files.list(artifacts).use { it.count() } == 0L)
        } finally {
            repository.toFile().deleteRecursively()
            artifacts.toFile().deleteRecursively()
        }
    }
}
