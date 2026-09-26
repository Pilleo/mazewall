package io.mazewall.profiler.tierE.tools

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Native CTest benchmark variants, scheduled in Kotlin with retained raw samples. */
public object TierENativeScenarioRunner {
    private val flavours = listOf(
        "agent_matched_benchmark_none",
        "agent_matched_benchmark_dictionary_full",
        "agent_matched_benchmark_dictionary_unique",
        "agent_matched_benchmark_inline_full",
        "agent_matched_benchmark_inline_unique",
    )
    private val release = listOf(
        "agent_phase_benchmark_full_disabled",
        "agent_phase_benchmark_full",
        "agent_phase_benchmark_full_live",
        "agent_phase_benchmark_unique_disabled",
        "agent_phase_benchmark_unique",
        "agent_phase_benchmark_unique_live",
    )
    private val releaseMetrics = listOf(
        "get_stack_trace_ns",
        "raw_key_ns",
        "logical_metadata_ns",
        "serialization_ns",
        "publication_bytes",
        "publication_latency_ns",
        "lock_wait_ns",
        "peak_retained_bytes",
        "publication_count",
        "coalesced_publications",
        "failed_publications",
    )

    public fun compareTransports(
        build: Path,
        output: Path,
    ) {
        val cases = listOf(
            Triple("agent_phase_benchmark_full", "dictionary", "full-stream"),
            Triple("agent_phase_benchmark_unique", "dictionary", "unique-stack-syscall"),
            Triple("agent_phase_benchmark_inline_full", "inline-per-invocation", "full-stream"),
            Triple("agent_phase_benchmark_inline_unique", "inline-per-invocation", "unique-stack-syscall"),
        )
        Files.createDirectories(requireNotNull(output.toAbsolutePath().parent))
        Files.writeString(output, "")
        for ((name, transport, mode) in cases) {
            val log = output.resolveSibling("${output.fileName}.$name")
            Files.writeString(log, "")
            repeat(3) { runCtest(build, name, log) }
            val samples = Files.readString(log)
            Files.writeString(output, samples, java.nio.file.StandardOpenOption.APPEND)
            for (metric in listOf("get_stack_trace_ns", "raw_key_ns", "logical_metadata_ns", "serialization_ns", "publication_bytes")) {
                val median = TierENativeScenarioSummary.medianThree(samples, metric)
                Files.writeString(
                    output,
                    "tier-e-stack-transport: transport=$transport mode=$mode metric=$metric median=$median\n",
                    java.nio.file.StandardOpenOption.APPEND,
                )
            }
        }
    }

    public fun checkPhaseGate(
        build: Path,
        baseline: Path,
        output: Path,
    ) {
        Files.createDirectories(requireNotNull(output.toAbsolutePath().parent))
        Files.writeString(output, "")
        for (mode in listOf("full", "unique")) {
            val log = output.resolveSibling("${output.fileName}.$mode")
            Files.writeString(log, "")
            repeat(3) { runCtest(build, "agent_phase_benchmark_$mode", log) }
            val raw = Files.readString(log)
            Files.writeString(output, raw, java.nio.file.StandardOpenOption.APPEND)
            val base = TierEBenchmarkPhaseGate.baselines(baseline, mode)
            val metrics = TierEBenchmarkPhaseGate.check(mode, raw, base)
            for ((phase, median) in metrics) {
                Files.writeString(
                    output,
                    "tier-e-benchmark-samples: mode=$mode phase=$phase median=$median " +
                    "limit=${Math.multiplyExact(base.getValue(phase), 125) / 100}\n",
                        java.nio.file.StandardOpenOption.APPEND,
                )
            }
            Files.writeString(
                output,
                "tier-e-benchmark-gate: mode=$mode median_total_ns=${metrics.values.sum()} limit=125% status=PASS\n",
                java.nio.file.StandardOpenOption.APPEND,
            )
        }
    }

    private fun runCtest(
        build: Path,
        scenario: String,
        output: Path,
    ) {
        val process = ProcessBuilder("ctest", "--test-dir", build.toString(), "-V", "-R", "^$scenario$")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(output.toFile()))
            .start()
        if (!process.waitFor(3, TimeUnit.MINUTES)) {
            process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            error("Tier E native scenario timed out: $scenario")
        }
        check(process.exitValue() == 0) { "Tier E native scenario failed: $scenario" }
    }

    public fun plan(kind: String): List<String> =
        when (kind) {
        "matched" -> (0 until 5).flatMap { fork -> flavours.indices.map { offset -> flavours[(fork + offset) % flavours.size] } }
        "release" -> release.flatMap { scenario -> List(13) { scenario } }
        else -> throw IllegalArgumentException("Unknown Tier E native scenario plan")
    }

    public fun run(
        kind: String,
        repository: Path,
        build: Path,
        output: Path,
    ) {
        val schedule = plan(kind)
        Files.createDirectories(build)
        Files.createDirectories(requireNotNull(output.toAbsolutePath().parent))

        fun execute(
            args: List<String>,
            append: Boolean = false,
            dest: Path = output,
        ) {
            val process = ProcessBuilder(args)
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .apply {
                if (append) redirectOutput(ProcessBuilder.Redirect.appendTo(dest.toFile())) else redirectOutput(dest.toFile())
            }.start()
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
                error("Tier E native benchmark command timed out: ${args.first()}")
            }
            check(process.exitValue() == 0) { "Tier E native benchmark failed: ${args.first()} (${process.exitValue()})" }
        }
        execute(listOf("cmake", "-S", repository.resolve("profiler-native").toString(), "-B", build.toString(), "-DCMAKE_BUILD_TYPE=Release"))
        execute(listOf("cmake", "--build", build.toString(), "-j2"), append = true)
        Files.writeString(output, "tier-e-$kind-benchmark: mode=native-agent-only build_type=Release\n")
        for (scenario in schedule) {
            execute(listOf("ctest", "--test-dir", build.toString(), "-V", "-R", "^$scenario$"), append = true)
        }
        val raw = Files.readString(output)
        if (kind == "matched") {
            for (scenario in flavours) {
                val result = TierENativeScenarioSummary.matched(raw, scenario)
                Files.writeString(
                    output,
                    "tier-e-matched-summary: scenario=$scenario p50_ns=${result.p50} p95_ns=${result.p95} " +
                    "p99_ns=${result.p99} samples=${result.samples}\n",
                        java.nio.file.StandardOpenOption.APPEND,
                )
            }
        } else {
            for (scenario in release) {
                val scoped = Regex("(?s)Start [0-9]+: $scenario(?:\\s|$).*?Test #[0-9]+:")
                    .findAll(raw)
                    .joinToString("\n") { it.value }
                for (metric in releaseMetrics) {
                    val result = TierENativeScenarioSummary.release(scoped, metric)
                    Files.writeString(
                        output,
                        "tier-e-release-summary: scenario=$scenario metric=$metric p50=${result.p50} " +
                        "p95=${result.p95} p99=${result.p99}\n",
                            java.nio.file.StandardOpenOption.APPEND,
                    )
                }
            }
        }
    }
}
