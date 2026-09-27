package io.mazewall.profiler.testing.tierE

import io.mazewall.profiler.tierE.tools.TierERunConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierERunConfigurationTest {
    private val smoke = """{
      "schema_version":1,
      "container":{"engine":"docker","uri":null,"image":"mazewall-tier-e-rootful:latest"},
      "fixture":{"coverage":true,"operations":72,"emission_mode":"full-stream","recovery_mode":"disabled"},
      "measurement":{"forks":1,"workload_cpu":0,"collector_cpu":1}
    }"""

    @Test
    fun `split fixture profile rejects quoted denominators and duplicate decoded keys`() {
        val profile = Files.createTempFile("tier-e-strict-split-", ".json")
        try {
            for ((before, after) in listOf(
                "\"image\":\"mazewall-tier-e-rootful:latest\"" to "\"image\":42",
                "\"coverage\":true" to "\"coverage\":\"true\"",
                "\"operations\":72" to "\"operations\":\"72\"",
                "\"forks\":1" to "\"forks\":\"1\"",
                "\"workload_cpu\":0" to "\"workload_cpu\":\"0\"",
                "\"operations\":72" to "\"operations\":72,\"oper\\u0061tions\":24",
            )) {
                Files.writeString(profile, smoke.replace(before, after))
                assertThrows(IllegalArgumentException::class.java) {
                    TierERunConfiguration.load(profile, environment = emptyMap())
                }
            }
        } finally {
            Files.deleteIfExists(profile)
        }
    }

    @Test
    fun `version two models scenario build deadlines and artifact policy before controller execution`() {
        val profile = Files.createTempFile("tier-e-v2-", ".json")
        try {
            val v2 = smoke
                .replace("\"schema_version\":1", "\"schema_version\":2")
                .replace("\"recovery_mode\":\"disabled\"", "\"recovery_mode\":\"recover\"")
                .replace(
                    "\"collector_cpu\":1}",
                    """"collector_cpu":1,"seed_start":23063,"warmups":3,"batches":10,"iterations":100},
                    "scenario":{"marker_version":"v2","marker_backend":"uprobe","stack_transport":"dictionary","socket_access":"group","fault_syscall":257,"negative_artifact":null,"identity_case":null,"live_snapshots":false,"baseline":false,"benchmark":false,"allow_incomplete":false,"delayed_artifact":false},
                    "build":{"native_build_dir":null,"reuse":true},
                    "deadlines":{"arm_seconds":600,"terminal_seconds":90,"stop_seconds":10},
                    "artifacts":{"output_root":null,"fresh":true,"retain_on_failure":true}""",
                )
            Files.writeString(profile, v2)
            val config = TierERunConfiguration.load(profile, environment = emptyMap())
            assertEquals("v2", config.scenario.markerVersion)
            assertEquals(257, config.scenario.faultSyscall)
            assertEquals(600, config.deadlines.armSeconds)
            assertTrue(config.resolvedJson().contains("\"marker_version\":\"v2\""))
            Files.writeString(profile, v2.replace(",\"delayed_artifact\":false", ""))
            assertEquals(false, TierERunConfiguration.load(profile, environment = emptyMap()).scenario.delayedArtifact)
            Files.writeString(profile, v2.replace("\"marker_version\":\"v2\"", "\"marker_version\":\"v1\""))
            assertThrows(IllegalArgumentException::class.java) { TierERunConfiguration.load(profile, environment = emptyMap()) }
        } finally {
            Files.deleteIfExists(profile)
        }
    }

    @Test
    fun `typed profile is resolved once and legacy overrides are explicit`() {
        val profile = Files.createTempFile("tier-e-smoke-", ".json")
        try {
            Files.writeString(profile, smoke)
            val config = TierERunConfiguration.load(
                profile,
                environment = mapOf("MAZEWALL_DOCKER_HOST" to "unix:///var/run/docker.sock"),
                overrides = mapOf("fixture.operations" to "96"),
            )
            assertEquals("docker", config.container.engine)
            assertEquals("unix:///var/run/docker.sock", config.container.uri)
            assertEquals(96, config.fixture.operations)
            assertTrue(config.fixture.coverage)
            assertTrue(config.resolvedJson().contains("\"policy_eligibility\":false"))
        } finally {
            Files.deleteIfExists(profile)
        }
    }

    @Test
    fun `default profile keeps unresolved URI for selected local context`() {
        val profile = Files.createTempFile("tier-e-context-", ".json")
        try {
            Files.writeString(profile, smoke)
            assertEquals(null, TierERunConfiguration.load(profile, environment = emptyMap()).container.uri)
        } finally {
            Files.deleteIfExists(profile)
        }
    }

    @Test
    fun `Podman connection ignores Docker only host alias`() {
        val profile = Files.createTempFile("tier-e-podman-", ".json")
        try {
            Files.writeString(profile, smoke)
            val selected = TierERunConfiguration.load(
                profile,
                environment = mapOf(
                "TIER_E_CONTAINER_ENGINE" to "podman",
                "DOCKER_HOST" to "unix:///run/user/1000/docker.sock",
                "CONTAINER_HOST" to "unix:///run/podman/podman.sock",
            ),
            )
            assertEquals("podman", selected.container.engine)
            assertEquals("unix:///run/podman/podman.sock", selected.container.uri)
        } finally {
            Files.deleteIfExists(profile)
        }
    }

    @Test
    fun `Podman-only connection selects Podman without an explicit engine flag`() {
        val profile = Files.createTempFile("tier-e-podman-default-", ".json")
        try {
            Files.writeString(profile, smoke)
            val selected = TierERunConfiguration.load(
                profile,
                environment = mapOf(
                "CONTAINER_HOST" to "unix:///run/podman/podman.sock",
            ),
            )
            assertEquals("podman", selected.container.engine)
            assertEquals("unix:///run/podman/podman.sock", selected.container.uri)
        } finally {
            Files.deleteIfExists(profile)
        }
    }

    @Test
    fun `conflicting endpoint aliases and unknown fields fail before execution`() {
        val profile = Files.createTempFile("tier-e-invalid-", ".json")
        try {
            Files.writeString(profile, smoke)
            assertThrows(IllegalArgumentException::class.java) {
                TierERunConfiguration.load(
                    profile,
                    environment = mapOf(
                    "MAZEWALL_DOCKER_HOST" to "unix:///var/run/docker.sock",
                    "DOCKER_HOST" to "ssh://elsewhere",
                    ),
                )
            }
            Files.writeString(profile, smoke.replace("\"forks\":1", "\"forks\":1,\"silent_bypass\":true"))
            assertThrows(IllegalArgumentException::class.java) { TierERunConfiguration.load(profile, environment = emptyMap()) }
        } finally {
            Files.deleteIfExists(profile)
        }
    }

    @Test
    fun `invalid combinations and malformed schema fail closed`() {
        val profile = Files.createTempFile("tier-e-invalid-mode-", ".json")
        try {
            Files.writeString(profile, smoke.replace("\"operations\":72", "\"operations\":71"))
            assertThrows(IllegalArgumentException::class.java) { TierERunConfiguration.load(profile, environment = emptyMap()) }
            Files.writeString(profile, smoke.replace("\"schema_version\":1", "\"schema_version\":2"))
            assertThrows(IllegalArgumentException::class.java) { TierERunConfiguration.load(profile, environment = emptyMap()) }
        } finally {
            Files.deleteIfExists(profile)
        }
    }
}
