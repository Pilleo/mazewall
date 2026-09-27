package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierETrustworthySettingsTest {
    @Test
    fun `explicit profile selection rejects legacy measurement knobs but accepts machine endpoint`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            TierEExperimentProfile.requireNoLegacyExperimentEnvironment(mapOf("MAZEWALL_TIER_E_FORKS" to "1"))
        }
        assertTrue(failure.message.orEmpty().contains("MAZEWALL_TIER_E_FORKS"))
        assertDoesNotThrow {
            TierEExperimentProfile.requireNoLegacyExperimentEnvironment(
                mapOf("MAZEWALL_DOCKER_HOST" to "unix:///var/run/docker.sock"),
            )
        }
    }

    @Test
    fun `versioned trustworthy profile rejects unknown keys and ignores legacy experiment overrides`() {
        val profile = Files.createTempFile("tier-e-experiment-", ".json")
        try {
            Files.writeString(
                profile,
                """{"schema_version":1,"kind":"trustworthy","settings":{"run_level":"smoke","benchmark_scope":"full"},"node_noise":{"runs":1,"duration_seconds":1,"cpu":2}}""",
            )
            val selected = TierEExperimentProfile.load(profile)
            assertEquals(1, selected.trustworthy.forks)
            assertEquals(1, selected.nodeNoise.runs)
            Files.writeString(profile, Files.readString(profile).replace("\"cpu\":2", "\"cpu\":2,\"typo\":true"))
            assertThrows(IllegalArgumentException::class.java) { TierEExperimentProfile.load(profile) }
            Files.writeString(
                profile,
                """
                {"schema_version":1,"kind":"trustworthy",
                 "settings":{"iterations":10000,"iter\u0061tions":1},
                 "node_noise":{"runs":1,"duration_seconds":1,"cpu":2}}
            """.trimIndent(),
            )
            assertThrows(IllegalArgumentException::class.java) { TierEExperimentProfile.load(profile) }
            Files.writeString(profile, "[".repeat(70) + "0" + "]".repeat(70))
            val depthFailure = assertThrows(IllegalArgumentException::class.java) { TierEExperimentProfile.load(profile) }
            assertTrue(depthFailure.message.orEmpty().contains("nesting exceeds bound"))
        } finally {
            Files.deleteIfExists(profile)
        }
    }

    @Test
    fun `duplicate experiment keys cannot override a declared measured denominator`() {
        val profile = Files.createTempFile("tier-e-duplicate-profile-", ".json")
        try {
            Files.writeString(
                profile,
                """
                {"schema_version":1,"kind":"trustworthy",
                 "settings":{"run_level":"smoke","iterations":10000,"iterations":1},
                 "node_noise":{"runs":1,"duration_seconds":1,"cpu":2}}
            """.trimIndent(),
            )
            assertThrows(IllegalArgumentException::class.java) { TierEExperimentProfile.load(profile) }
        } finally {
            Files.deleteIfExists(profile)
        }
    }

    @Test
    fun `profile JSON numbers booleans and image names retain their declared types`() {
        val repository = generateSequence(
            java.nio.file.Path
            .of("")
            .toAbsolutePath(),
        ) { it.parent }
            .first { Files.isRegularFile(it.resolve("config/testing/tier-e/trustworthy.json")) }
        val document = Files.readString(repository.resolve("config/testing/tier-e/trustworthy.json"))
        val profile = Files.createTempFile("tier-e-profile-types-", ".json")
        try {
            for ((before, after) in listOf(
                "\"forks\": 1" to "\"forks\": \"1\"",
                "\"calibration\": false" to "\"calibration\": \"false\"",
                "\"image\": \"mazewall-tier-e-rootful:perf-20260919-external-profiler\"" to "\"image\": 42",
                "\"workload_cpu\": 0" to "\"workload_cpu\": \"0\"",
                "\"cpu\": 2" to "\"cpu\": \"2\"",
            )) {
                require(before in document)
                Files.writeString(profile, document.replace(before, after))
                assertThrows(IllegalArgumentException::class.java) { TierEExperimentProfile.load(profile) }
            }
        } finally {
            Files.deleteIfExists(profile)
        }
    }

    @Test
    fun `capacity profile owns image CPUs and exact stage schedule without ambient experiment variables`() {
        val profile = Files.createTempFile("tier-e-capacity-profile-", ".json")
        try {
            val document = """
                {
                  "schema_version": 2,
                  "kind": "capacity",
                  "settings": {"run_level":"custom","benchmark_scope":"tier-e-sustained-collector",
                    "sustained_rate_per_second":1000,"iterations":1000,"warmups":0,"calibration":false},
                  "node_noise": {"runs":1,"duration_seconds":1,"cpu":2},
                  "container": {"image":"mazewall-tier-e-rootful:latest","workload_cpu":0,"collector_cpu":1},
                  "capacity": {"pilot_rates":"1000 2000","stage_seconds":1,"run_node_noise_probe":false}
                }
            """.trimIndent()
            Files.writeString(profile, document)
            val selected = TierEExperimentProfile.load(profile)
            assertEquals("mazewall-tier-e-rootful:latest", selected.image)
            assertEquals("0,1", selected.cpuSet)
            assertEquals(listOf(1000, 2000), selected.capacity!!.pilotRates)
            assertEquals(
                "1000 2000",
                selected
                .resolvedJson()
                .getValue("capacity")
                .jsonObject
                .getValue("pilot_rates")
                .jsonPrimitive.content,
            )
            val stage = selected.capacity!!.plan(1000, 1, 1, "pilot")
            assertEquals(1, stage.batches)
            val settings = TierEProfileExperimentRunner.stageSettings(selected.trustworthy, selected.capacity!!, stage, false)
            assertEquals(1000, settings.sustainedRate)
            assertEquals(1000, settings.iterations)
            assertEquals(1, settings.batches)
            assertEquals(0, settings.warmups)
            assertEquals("tier-e-sustained-collector", settings.scope)
            assertTrue(settings.benchmarkJavaOptions.contains("benchmark-max-retained-records="))
            Files.writeString(profile, document.replace("\"collector_cpu\":1", "\"collector_cpu\":0"))
            assertThrows(IllegalArgumentException::class.java) { TierEExperimentProfile.load(profile) }
            Files.writeString(profile, document.replace("\"stage_seconds\":1", "\"stage_seconds\":1,\"unknown\":true"))
            assertThrows(IllegalArgumentException::class.java) { TierEExperimentProfile.load(profile) }
        } finally {
            Files.deleteIfExists(profile)
        }
    }

    @Test
    fun `capacity schedule rejects numeric and boolean strings and non-string pilot list`() {
        val repository = generateSequence(
            java.nio.file.Path
            .of("")
            .toAbsolutePath(),
        ) { it.parent }
            .first { Files.isRegularFile(it.resolve("config/testing/tier-e/capacity.json")) }
        val document = Files.readString(repository.resolve("config/testing/tier-e/capacity.json"))
        val profile = Files.createTempFile("tier-e-capacity-types-", ".json")
        try {
            for ((before, after) in listOf(
                "\"pilot_rates\": \"1000\"" to "\"pilot_rates\": 1000",
                "\"stage_seconds\": 1" to "\"stage_seconds\": \"1\"",
                "\"run_node_noise_probe\": false" to "\"run_node_noise_probe\": \"false\"",
            )) {
                require(before in document)
                Files.writeString(profile, document.replace(before, after))
                assertThrows(IllegalArgumentException::class.java) { TierEExperimentProfile.load(profile) }
            }
        } finally {
            Files.deleteIfExists(profile)
        }
    }

    @Test
    fun `sustained audit request remains an explicit benchmark-only guest option`() {
        val settings = TierETrustworthySettings.load(
            mapOf(
            "MAZEWALL_TIER_E_BENCHMARK_SCOPE" to "tier-e-sustained-collector",
            "MAZEWALL_TIER_E_SUSTAINED_RATE_PER_SECOND" to "1000",
            "MAZEWALL_TIER_E_BENCHMARK_RAW_SYS_ENTER_AUDIT" to "true",
        ),
        )
        assertTrue(settings.rawSysEnterAudit)
        assertTrue(
            "-Dmazewall.tier-e.benchmark-raw-sys-enter-audit=true" in
            TierETrustworthyGuestCommand.java(settings, 0, 2),
        )
        val exact = TierETrustworthySettings.load(
            mapOf(
            "MAZEWALL_TIER_E_BENCHMARK_SCOPE" to "tier-e-sustained-collector",
            "MAZEWALL_TIER_E_SUSTAINED_RATE_PER_SECOND" to "1000",
            "MAZEWALL_TIER_E_BENCHMARK_EXACT_ADMISSION_AUDIT" to "true",
        ),
        )
        assertTrue(exact.exactAdmissionAudit)
        assertTrue("-Dmazewall.tier-e.benchmark-exact-admission-audit=true" in TierETrustworthyGuestCommand.java(exact, 0, 2))
        assertThrows(IllegalArgumentException::class.java) {
            TierETrustworthySettings.load(mapOf("MAZEWALL_TIER_E_BENCHMARK_RAW_SYS_ENTER_AUDIT" to "maybe"))
        }
    }

    @Test
    fun `smoke level is bounded and decision level cannot use fewer than fifteen forks`() {
        val smoke = TierETrustworthySettings.load(mapOf("MAZEWALL_TIER_E_RUN_LEVEL" to "smoke"))
        assertEquals(1, smoke.forks)
        assertEquals(3, smoke.batches)
        assertEquals("full", smoke.scope)
        assertThrows(IllegalArgumentException::class.java) {
            TierETrustworthySettings.load(mapOf("MAZEWALL_TIER_E_RUN_LEVEL" to "decision", "MAZEWALL_TIER_E_FORKS" to "1"))
        }
    }

    @Test
    fun `malformed flags and conflicting profilers fail before any container creation`() {
        for (environment in listOf(
            mapOf("MAZEWALL_TIER_E_PERF_RECORD" to "yes"),
            mapOf("MAZEWALL_TIER_E_PERF_RECORD" to "true", "MAZEWALL_TIER_E_ALLOCATION_RECORD" to "true"),
            mapOf("MAZEWALL_TIER_E_BENCHMARK_SCOPE" to "unexpected"),
            mapOf("MAZEWALL_TIER_E_USER_NOTIF_CHILD_TIMEOUT_SECONDS" to "480"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { TierETrustworthySettings.load(environment) }
        }
    }
}
