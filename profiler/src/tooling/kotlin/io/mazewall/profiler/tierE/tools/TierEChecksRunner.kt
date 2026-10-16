package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerEndpointDiscovery
import io.mazewall.testing.containers.ContainerEngine
import io.mazewall.testing.containers.EndpointConfig
import io.mazewall.testing.containers.EndpointSelection
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Small explicit Tier E check levels; the paced long matrix is never a check dependency. */
public object TierEChecksRunner {
    public fun plan(level: String): List<String> =
        when (level) {
        "focused" -> listOf("focused-gradle")
        "native" -> listOf("native-configure", "native-build", "native-ctest")
        "kernel-smoke" -> listOf("kernel-smoke")
        "delivery" -> listOf("profiler-test", "native-configure", "native-build", "native-ctest", "backlog-check")
        else -> throw IllegalArgumentException("Unknown Tier E check level: $level")
    }

    public fun run(
        level: String,
        repository: Path,
        output: Path,
    ) {
        val stages = plan(level)
        Files.createDirectories(output)
        require(Files.list(output).use { it.findAny().isEmpty }) { "Tier E checks require fresh artifacts" }
        TierEMatrixTerminal.run(output) {
            fun event(
                name: String,
                state: String,
                log: Path? = null,
            ) {
                Files.writeString(
                    output.resolve("events.log"),
                    "tier-e-check: stage=$name state=$state timestamp=${Instant.now()}" +
                        (log?.let { " log=$it" } ?: "") +
                        "\n",
                            java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                )
            }
            val native = repository.resolve("build/tier-e-native-check")
            for (name in stages) {
                val log = output.resolve("$name.log")
                event(name, "started")
                if (name == "kernel-smoke") {
                    val selected = ContainerEndpointDiscovery().discover(
                        EndpointConfig(
                            ContainerEngine.DOCKER,
                        System.getenv("MAZEWALL_DOCKER_HOST") ?: "unix:///var/run/docker.sock",
                        ),
                    )
                    val endpoint = (selected as? EndpointSelection.Ready)?.endpoint
                        ?: error("Tier E kernel smoke requires rootful Docker: $selected")
                    val settings = TierETrustworthySettings.load(
                        System.getenv() +
                        mapOf(
                        "MAZEWALL_TIER_E_BENCHMARK_SCOPE" to "tier-e-capture-profile",
                        "MAZEWALL_TIER_E_FORKS" to "1",
                            "MAZEWALL_TIER_E_WARMUPS" to "0",
                        "MAZEWALL_TIER_E_BATCHES" to "1",
                            "MAZEWALL_TIER_E_ITERATIONS" to "64",
                        ),
                    )
                    TierETrustworthyRunner.run(
                        settings,
                        endpoint,
                        repository,
                        output.resolve("kernel-smoke"),
                        System.getenv("MAZEWALL_TIER_E_DOCKER_IMAGE") ?: "mazewall-tier-e-rootful:perf-20260919-external-profiler",
                        System.getenv("MAZEWALL_TIER_E_BENCHMARK_CPUS"),
                    )
                    Files.writeString(log, "rootful kernel smoke completed\n")
                } else {
                    val command = when (name) {
                        "focused-gradle" -> listOf(
                            repository.resolve("gradlew").toString(),
                            ":profiler:test",
                            "--tests",
                            "io.mazewall.profiler.tierE.stress.TierEMatchedBenchmarkJsonTest",
                            "--tests",
                            "io.mazewall.profiler.tierE.stress.TierECommonFileWorkloadTest",
                            "--tests",
                            "io.mazewall.profiler.internal.attribution.TierEProfilerSessionLifecycleTest",
                        )
                        "native-configure" -> listOf(
                            "cmake",
                            "-S",
                            repository.resolve("profiler-native").toString(),
                            "-B",
                            native.toString(),
                            "-DCMAKE_BUILD_TYPE=Release",
                        )
                        "native-build" -> listOf("cmake", "--build", native.toString(), "-j2")
                        "native-ctest" -> listOf("ctest", "--test-dir", native.toString(), "--output-on-failure")
                        "profiler-test" -> listOf(repository.resolve("gradlew").toString(), ":profiler:test")
                        else -> listOf(repository.resolve("scripts/adkw").toString(), "check-backlog")
                    }
                    val process = ProcessBuilder(command)
                        .directory(repository.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start()
                    if (!process.waitFor(Duration.ofMinutes(20).toMillis(), TimeUnit.MILLISECONDS)) {
                        process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
                        process.destroyForcibly()
                        error("Tier E check stage timed out: $name")
                    }
                    check(process.exitValue() == 0) { "Tier E check stage failed: $name (see $log)" }
                }
                event(name, "completed", log)
            }
            event("runner", "passed")
        }
    }
}
