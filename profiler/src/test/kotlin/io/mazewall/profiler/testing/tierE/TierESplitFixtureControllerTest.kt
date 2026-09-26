package io.mazewall.profiler.testing.tierE

import io.mazewall.profiler.tierE.tools.TierERunConfiguration
import io.mazewall.profiler.tierE.tools.controller.TierESplitFixtureController
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierESplitFixtureControllerTest {
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
