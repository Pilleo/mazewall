package io.mazewall.profiler.tierE.tools

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Runs inside the disposable privileged benchmark container using an immutable /bundle mount. */
public fun main(): Unit = trustworthyGuestMain()

public fun trustworthyGuestMain() {
    val settings = TierETrustworthySettings.load()
    val workloadCpu = requireNotNull(System.getenv("WORKLOAD_CPU")?.toIntOrNull())
    val collectorCpu = requireNotNull(System.getenv("COLLECTOR_CPU")?.toIntOrNull())
    val artifact = Path.of("/artifacts")
    Files.createDirectories(artifact)

    fun execute(
        command: List<String>,
        timeout: Duration,
        output: Path? = null,
    ) {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
            if (output != null) redirectOutput(output.toFile()) else inheritIO()
        }.start()
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            error("Tier E guest command timed out: ${command.first()}")
        }
        check(process.exitValue() == 0) { "Tier E guest command failed: ${command.first()} (${process.exitValue()})" }
    }
    if (ProcessBuilder("mountpoint", "-q", "/sys/kernel/tracing").start().waitFor() != 0) {
        execute(listOf("mount", "-t", "tracefs", "tracefs", "/sys/kernel/tracing"), Duration.ofSeconds(20))
    }
    if (settings.canonicalCache == "disabled") {
        val preflight = artifact.resolve("cache-disabled-preflight.log")
        execute(
            listOf(
                "java",
                "-Dmazewall.tier-e.canonical-cache=disabled",
                "-Dmazewall.tier-e.ring-capacity-bytes=8388608",
            "--enable-native-access=ALL-UNNAMED",
                "-cp",
                "/bundle/runtime/*",
                "io.mazewall.profiler.tierE.stress.TierEKernelSmoke",
            "/bundle/libmazewall_stack_agent.so",
                "auto",
                "/bundle/runtime/*",
                "/tmp/tier-e-cache-preflight.mzsd",
            "platform",
                "10",
                workloadCpu.toString(),
                "full-stream",
                "dictionary",
                "common-file-workload",
                "benchmark",
                "0",
                "1",
            ),
            Duration.ofMinutes(2),
            preflight,
        )
        val log = Files.readString(preflight)
        check(
            Regex("cache_hits=0(?: |$)", RegexOption.MULTILINE).containsMatchIn(log) &&
            Regex("cache_misses=[1-9][0-9]*(?: |$)", RegexOption.MULTILINE).containsMatchIn(log),
        ) { "Canonical cache was not disabled" }
    }
    val java = TierETrustworthyGuestCommand.java(settings, workloadCpu, collectorCpu)
    val allocationProbe = if (settings.allocationRecord) {
        val runId = requireNotNull(System.getenv("TIER_E_ALLOCATION_PROBE_RUN_ID")) { "Missing allocation probe owner" }
        TierEAllocationProbe(TierEAllocationProbe.nameFor(runId))
    } else {
        null
    }
    var primary: Throwable? = null
    try {
    val profiler = when {
        settings.perfRecord -> listOf(
            "perf",
            "record",
            "--output",
            "/artifacts/perf.data",
            "--clockid",
            "mono",
            "--event",
            "cycles:u",
            "--call-graph",
            settings.perfCallGraph,
            "--",
        ) +
            java
        settings.allocationRecord -> {
            val probe = requireNotNull(allocationProbe)
            probe.open()
            Files.writeString(artifact.resolve("allocation-probe-name.txt"), "${probe.event}\n")
            listOf("perf", "record", "--output", "/artifacts/allocation.data", "--event", probe.event, "--") + java
        }
        else -> java
    }
        val options = settings.benchmarkJavaOptions.trim() +
            if (settings.perfRecord && settings.perfJitMap) {
            " -XX:+UnlockDiagnosticVMOptions -XX:+DumpPerfMapAtExit"
        } else {
            ""
        }
        val process = ProcessBuilder(profiler)
            .redirectErrorStream(true)
            .redirectOutput(artifact.resolve("benchmark.log").toFile())
            .apply {
                environment()["JAVA_TOOL_OPTIONS"] = listOf(
                    options,
                "-Dmazewall.tier-e.sys-enter-attachment-backend=${settings.attachmentBackend}",
                "-Dmazewall.tier-e.kernel-coverage-mode=${settings.kernelCoverageMode}",
                "-Dmazewall.tier-e.sys-enter-attachment-shape=${settings.attachmentShape}",
                ).joinToString(" ")
            }.start()
        if (!process.waitFor((settings.childTimeoutSeconds.toLong() * settings.forks + 60).coerceAtMost(7200), TimeUnit.SECONDS)) {
            process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            error("Tier E guest benchmark timed out")
        }
        check(process.exitValue() == 0) { "Tier E benchmark exited ${process.exitValue()}" }
        if (settings.perfRecord && settings.perfJitMap) {
            val maps = Files.createDirectories(artifact.resolve("perf-maps"))
            Files.list(Path.of("/tmp")).use { paths ->
                paths
                    .filter { it.fileName.toString().matches(Regex("perf-[0-9]+\\.map")) }
                    .forEach { Files.copy(it, maps.resolve(it.fileName)) }
            }
        }
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        if (allocationProbe != null) {
            try {
                allocationProbe.close()
            } catch (cleanup: Throwable) {
                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
}
