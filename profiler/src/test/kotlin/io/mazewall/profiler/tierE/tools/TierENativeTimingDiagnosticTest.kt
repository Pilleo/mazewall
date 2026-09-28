package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.security.MessageDigest

class TierENativeTimingDiagnosticTest {
    @Test
    fun `parser admits the sealed first fork log with twelve binding detail rows`() {
        val log = requireNotNull(javaClass.getResourceAsStream("/tier-e/native-timing-first-fork.txt")).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(log).joinToString("") { "%02x".format(it) }
        assertEquals("a28856c78e837a791aaf83cd6b92ec025e240acf017ef158eb873bba966e3c3c", hash)
        val observation = TierENativeTimingDiagnostic.parse('A', "full", log.decodeToString())
        assertEquals(514L, observation.captures)
        assertEquals(1_600_105L, observation.stackNs)
        assertEquals(36_341_546L, observation.workloadNs)
        assertEquals(null, observation.captureCpuNs)
        assertTrue(!observation.policyEligibility)
    }

    @Test
    fun `telemetry admission rejects unavailable cpu counters and expanded cpu affinity`() {
        val valid = "usage_usec 150\nnr_periods 1\nnr_throttled 0\nthrottled_usec 0"
        val prior = TierENativeTimingDiagnostic.requireTelemetry(valid, "max 100000", "0,2", "some avg10=0.00 avg60=0.00 avg300=0.00 total=1")
        val after = TierENativeTimingDiagnostic.requireTelemetry(valid.replace("150", "210"), "max 100000", "0,2", "some avg10=0.01 avg60=0.00 avg300=0.00 total=2")
        TierENativeTimingDiagnostic.requireProgress(prior, after)
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.requireTelemetry("usage_usec 150", "max 100000", "0,2", "some avg10=0.00")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.requireTelemetry(valid, "max 100000", "0-2", "some avg10=0.00")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.requireTelemetry(valid, "100000 100000", "0,2", "some avg10=0.00")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.requireProgress(after, prior)
        }
    }

    @Test
    fun `admission rejects a substituted diagnostic binary even when original and workload match`() {
        TierENativeTimingDiagnostic.requireBinaryIdentities(
            "ff1e640c079de05ba407eab963cfb8939553e7f49390e60fdb20645faa3f790a",
            "9dafc1495eb42e7c554637b748087fcb7cc42e286d69fa8165776565aa923e76",
            "12ac99613bffb53253c02545063b37f01eb1860657233acf7879f90365d7c0e6",
        )
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.requireBinaryIdentities(
                "ff1e640c079de05ba407eab963cfb8939553e7f49390e60fdb20645faa3f790a",
                "a".repeat(64),
                "12ac99613bffb53253c02545063b37f01eb1860657233acf7879f90365d7c0e6",
            )
        }
    }

    @Test
    fun `toolchain admission pins JDK and native compiler from historical timing receipt`() {
        val jdk = "openjdk version \"25.0.4\" 2026-07-21 LTS\nOpenJDK Runtime Environment Temurin-25.0.4+7 (build 25.0.4+7-LTS)"
        val compiler = "c++ (Ubuntu 15.2.0-16ubuntu1) 15.2.0"
        TierENativeTimingDiagnostic.requireToolchain(jdk, compiler)
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.requireToolchain("openjdk version \"25.0.5\" 2026-08-21 LTS", compiler)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.requireToolchain(jdk, "c++ (Ubuntu 16.0) 16.0")
        }
    }

    @Test
    fun `ownership export must preserve all thirty six fork logs and telemetry before retirement`() {
        val root = Files.createTempDirectory("tier-e-native-export-")
        val host = root.resolve("host")
        val exported = root.resolve("exported").resolve("diagnostic")
        Files.createDirectories(host)
        Files.createDirectories(exported)
        try {
            val shared = listOf("plan.json", "schedule.jsonl", "preflight.sha256", "observations.jsonl", "diagnostic-terminal.json", "original-agent.so")
            val sampleFiles = (1..36).flatMap { index ->
                val name = "sample-${index.toString().padStart(2, '0')}"
                listOf("$name.log", "$name-before.json", "$name-after.json")
            }
            for (name in shared + sampleFiles) {
                Files.writeString(host.resolve(name), "exact-$name")
                Files.writeString(exported.resolve(name), "exact-$name")
            }
            TierENativeTimingDiagnostic.verifyExport(host, exported)
            Files.delete(exported.resolve("sample-36-after.json"))
            assertThrows(IllegalArgumentException::class.java) { TierENativeTimingDiagnostic.verifyExport(host, exported) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `readiness has exactly one nonqualifying fork for each arm and mode`() {
        val readiness = TierENativeTimingDiagnostic.readinessSchedule()
        assertEquals(
            listOf("A/full", "A/unique", "B/full", "B/unique", "C/full", "C/unique"),
            readiness.map { "${it.arm}/${it.mode}" },
        )
        assertEquals((1..6).toList(), readiness.map { it.index })
        assertTrue(readiness.all { it.block == 1 })
    }

    @Test
    fun `readiness export must include all six forks without accepting a five-fork subset`() {
        val root = Files.createTempDirectory("tier-e-readiness-export-")
        val host = root.resolve("host")
        val exported = root.resolve("exported")
        Files.createDirectories(host)
        Files.createDirectories(exported)
        try {
            val shared = listOf("plan.json", "schedule.jsonl", "preflight.sha256", "observations.jsonl", "diagnostic-terminal.json", "original-agent.so")
            val forkFiles = (1..6).flatMap { index ->
                val name = "sample-${index.toString().padStart(2, '0')}"
                listOf("$name.log", "$name-before.json", "$name-after.json")
            }
            for (name in shared + forkFiles) {
                Files.writeString(host.resolve(name), "ready-$name")
                Files.writeString(exported.resolve(name), "ready-$name")
            }
            TierENativeTimingDiagnostic.verifyExport(host, exported, 6)
            Files.delete(exported.resolve("sample-06.log"))
            assertThrows(IllegalArgumentException::class.java) { TierENativeTimingDiagnostic.verifyExport(host, exported, 6) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed or missing readiness receipt cannot admit a new campaign`() {
        val root = Files.createTempDirectory("tier-e-absent-readiness-")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                TierENativeTimingDiagnostic.requireReadiness(root, "exact-source", "exact-digest")
            }
            Files.writeString(root.resolve("diagnostic-terminal.json"), """{"status":"INCOMPLETE","policy_eligibility":false}""")
            assertThrows(IllegalArgumentException::class.java) {
                TierENativeTimingDiagnostic.requireReadiness(root, "exact-source", "exact-digest")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `six hash-bound readiness forks admit only their own clean source`() {
        val root = Files.createTempDirectory("tier-e-complete-readiness-")
        val exported = root.resolve("diagnostic")
        Files.createDirectory(exported)
        try {
            fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

            fun write(
                name: String,
                text: String,
            ) = Files.writeString(root.resolve(name), text)
            val plan = buildJsonObject {
                put("kind", "NON_QUALIFYING_TIMING_READINESS")
                put("source_revision", "exact-revision")
                put("source_digest", "exact-digest")
                put("image_id", "sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56")
                put("kernel", "7.0.0-34-generic")
                put("kernel_config_sha256", "e75c0adab6020e72844d7431cf801a02b94ed32efc251c3b0fb80c021d065180")
                put("cpu_set", "0,2")
                put("samples", 6)
                put("timeout_seconds_per_fork", 180)
                put("campaign_timeout_minutes", 20)
                put("policy_eligibility", false)
                put("original_agent_sha256", "ff1e640c079de05ba407eab963cfb8939553e7f49390e60fdb20645faa3f790a")
                put("diagnostic_agent_sha256", "9dafc1495eb42e7c554637b748087fcb7cc42e286d69fa8165776565aa923e76")
                put("workload_class_sha256", "12ac99613bffb53253c02545063b37f01eb1860657233acf7879f90365d7c0e6")
                put("java", "openjdk version \"25.0.4\" 2026-07-21 LTS")
                put("compiler", "c++ (Ubuntu 15.2.0-16ubuntu1) 15.2.0")
            }
            write("plan.json", "$plan\n")
            write("diagnostic-terminal.json", """{"status":"READINESS_COMPLETE","policy_eligibility":false}""" + "\n")
            write("original-agent.so", "retained-copy")
            val modes = listOf("A/full", "A/unique", "B/full", "B/unique", "C/full", "C/unique")
            val schedule = mutableListOf<String>()
            val observations = mutableListOf<String>()
            for ((index, pair) in modes.withIndex()) {
                val (arm, mode) = pair.split('/')
                val prefix = "sample-${(index + 1).toString().padStart(2, '0')}"
                val diagnostic = if (arm == "C") {
                    "capture-timing-diagnostic: attempted=514 completed=514 invalid=0 elapsed_ns=1234 cpu_ns=800 migrations=0 voluntary_switches=0 involuntary_switches=0 clock_pair_ns=50 clock_pair_valid=1"
                } else {
                    ""
                }
                val log = sampleLog(diagnostic).replace("emission_mode=unique-stack-syscall", "emission_mode=${if (mode == "full") "full-stream" else "unique-stack-syscall"}")
                write("$prefix.log", log)

                fun telemetry(usage: Long): String =
                    buildJsonObject {
                    put("cpu_stat", "usage_usec $usage\nnr_throttled 0")
                    put("cpu_max", "max 100000")
                    put("cpuset_effective", "0,2")
                    put("host_cpu_pressure", "some avg10=0.00 avg60=0.00 avg300=0.00 total=1")
                }.toString()
                val before = telemetry(100L)
                val after = telemetry(200L)
                write("$prefix-before.json", "$before\n")
                write("$prefix-after.json", "$after\n")
                schedule += buildJsonObject {
                    put("index", index + 1)
                    put("block", 1)
                    put("arm", arm)
                    put("mode", mode)
                }.toString()
                observations += buildJsonObject {
                    put("index", index + 1)
                    put("block", 1)
                    put("arm", arm)
                    put("mode", mode)
                    put("stack_ns", 3157189)
                    put("workload_ns", 70000000)
                    put("sample_log_sha256", hash(log.toByteArray()))
                    put("before", before)
                    put("after", after)
                    put("policy_eligibility", false)
                }.toString()
            }
            write("schedule.jsonl", schedule.joinToString("\n", postfix = "\n"))
            write("observations.jsonl", observations.joinToString("\n", postfix = "\n"))
            write("preflight.sha256", "${hash(Files.readAllBytes(root.resolve("plan.json")))}  plan.json\n${hash(Files.readAllBytes(root.resolve("schedule.jsonl")))}  schedule.jsonl\n")
            Files.list(root).use { files -> files.filter { Files.isRegularFile(it) }.forEach { Files.copy(it, exported.resolve(it.fileName)) } }
            TierENativeTimingDiagnostic.requireReadiness(root, "exact-revision", "exact-digest")
            assertThrows(IllegalArgumentException::class.java) {
                TierENativeTimingDiagnostic.requireReadiness(root, "foreign-revision", "exact-digest")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `fixed schedule contains 36 fresh forks and balances orders`() {
        val samples = TierENativeTimingDiagnostic.schedule()
        assertEquals(36, samples.size)
        assertEquals(listOf('A', 'B', 'C'), samples.take(6).map { it.arm }.distinct())
        assertEquals(listOf('B', 'C', 'A'), samples.subList(6, 12).map { it.arm }.distinct())
        assertEquals(listOf("full", "unique"), samples.take(2).map { it.mode })
        assertEquals(listOf("unique", "full"), samples.subList(6, 8).map { it.mode })
        assertEquals((1..36).toList(), samples.map { it.index })
    }

    @Test
    fun `instrumented evidence requires complete clocks and matching workload`() {
        val sample =
            sampleLog(
                "capture-timing-diagnostic: attempted=514 completed=514 invalid=0 elapsed_ns=1234 cpu_ns=800 migrations=0 voluntary_switches=0 involuntary_switches=0 clock_pair_ns=50 clock_pair_valid=1",
            )
        val parsed = TierENativeTimingDiagnostic.parse('C', "unique", sample)
        assertEquals(1234L, parsed.captureElapsedNs)
        assertEquals(800L, parsed.captureCpuNs)
        assertEquals(514L, parsed.captures)
        assertTrue(!parsed.policyEligibility)
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.parse('C', "unique", sample.replace("invalid=0", "invalid=1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.parse('C', "unique", sample.replace("stack-captures=514", "stack-captures=513"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.parse('C', "unique", sample.replace("clock_pair_valid=1", "clock_pair_valid=0"))
        }
    }

    @Test
    fun `controls reject instrumented data and require matching emission mode`() {
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.parse(
                'A',
                "unique",
                sampleLog(
                    "capture-timing-diagnostic: attempted=514 completed=514 invalid=0 elapsed_ns=1234 cpu_ns=800 migrations=0 voluntary_switches=0 involuntary_switches=0 clock_pair_ns=50 clock_pair_valid=1",
                ),
            )
        }
        val control = TierENativeTimingDiagnostic.parse('B', "unique", sampleLog(""))
        assertEquals(null, control.captureCpuNs)
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.parse('B', "full", sampleLog(""))
        }
    }

    @Test
    fun `remote endpoint cannot allocate diagnostic resources or output`() {
        val root = Files.createTempDirectory("tier-e-timing-root-")
        val output = root.resolve("fresh")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                TierENativeTimingDiagnosticRunner.run(root, output, ContainerEndpoint(ContainerEngine.DOCKER, "ssh://remote"))
            }
            assertTrue(Files.notExists(output))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `readiness rejects remote Docker before allocating any runner or output`() {
        val root = Files.createTempDirectory("tier-e-readiness-root-")
        val output = root.resolve("fresh")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                TierENativeTimingDiagnosticRunner.readiness(root, output, ContainerEndpoint(ContainerEngine.DOCKER, "ssh://remote"))
            }
            assertTrue(Files.notExists(output))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun sampleLog(diagnostic: String) =
        """
        agent-workload: workloadNs=70000000
        mazewall-stack-agent: native-binds=248 proxied-binds=4 stack-captures=514 stack-failures=0
        mazewall-stack-agent: capture-profile-binding=unix-open0 admitted=202 excluded=0
        tier-e-benchmark: get_stack_trace_ns=3157189 raw_key_ns=123 canonical_cache_lookup_ns=456 logical_metadata_ns=789 emission_mode=unique-stack-syscall cache_hits=504 cache_misses=10
        $diagnostic
    """.trimIndent()
}
