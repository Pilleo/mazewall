package io.mazewall.profiler.tierE.tools

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Non-qualifying native timing comparison: fixed order and strict raw sample admission. */
public object TierENativeTimingDiagnostic {
    private const val ORIGINAL_SHA = "ff1e640c079de05ba407eab963cfb8939553e7f49390e60fdb20645faa3f790a"
    private const val DIAGNOSTIC_SHA = "9dafc1495eb42e7c554637b748087fcb7cc42e286d69fa8165776565aa923e76"
    private const val WORKLOAD_SHA = "12ac99613bffb53253c02545063b37f01eb1860657233acf7879f90365d7c0e6"

    /** Prevent a changed compiler, image or workload bytecode from entering a paired comparison. */
    public fun requireBinaryIdentities(
        original: String,
        diagnostic: String,
        workload: String,
    ) {
        require(original == ORIGINAL_SHA && diagnostic == DIAGNOSTIC_SHA && workload == WORKLOAD_SHA) {
            "Native timing diagnostic binary identity changed"
        }
    }

    public fun requireToolchain(
        java: String,
        compiler: String,
    ) {
        require(java.lineSequence().firstOrNull() == "openjdk version \"25.0.4\" 2026-07-21 LTS") {
            "Native timing JDK differs from retained generic timing receipt"
        }
        require(compiler == "c++ (Ubuntu 15.2.0-16ubuntu1) 15.2.0") {
            "Native timing compiler differs from retained generic timing receipt"
        }
    }

    /** The owner retires its runner only after every published raw sample matches its host original. */
    public fun verifyExport(
        host: Path,
        exported: Path,
        samples: Int = 36,
    ) {
        require(samples in setOf(6, 36)) { "Native timing export requires the fixed readiness or acquisition sample count" }
        require(Files.isDirectory(host, NOFOLLOW_LINKS) && Files.isDirectory(exported, NOFOLLOW_LINKS)) {
            "Native timing diagnostic export directories are missing"
        }
        val shared = listOf("plan.json", "schedule.jsonl", "preflight.sha256", "observations.jsonl", "diagnostic-terminal.json", "original-agent.so")
        val sampleFiles = (1..samples).flatMap { index ->
            val name = "sample-${index.toString().padStart(2, '0')}"
            listOf("$name.log", "$name-before.json", "$name-after.json")
        }
        val expected = (shared + sampleFiles).toSet()
        val published = Files.list(exported).use { entries -> entries.map { it.fileName.toString() }.toList().toSet() }
        require(published == expected) { "Native timing diagnostic export is missing a raw file or contains an unexpected file" }
        for (name in expected) {
            val source = host.resolve(name)
            val result = exported.resolve(name)
            require(Files.isRegularFile(source, NOFOLLOW_LINKS) && Files.isRegularFile(result, NOFOLLOW_LINKS) && Files.mismatch(source, result) == -1L) {
                "Native timing diagnostic export differs from host sample: $name"
            }
        }
    }

    public data class Sample(
        val index: Int,
        val block: Int,
        val arm: Char,
        val mode: String,
    )

    public data class Observation(
        val stackNs: Long,
        val workloadNs: Long,
        val captures: Long,
        val captureElapsedNs: Long?,
        val captureCpuNs: Long?,
        val clockPairNs: Long?,
        val policyEligibility: Boolean = false,
    )

    public data class Telemetry(
        val usageUsec: Long,
        val throttledPeriods: Long,
    )

    /** Required, non-invasive cgroup and pressure observations for each measured JVM. */
    public fun requireTelemetry(
        cpuStat: String,
        cpuMax: String,
        cpus: String,
        pressure: String,
    ): Telemetry {
        fun counter(name: String): Long {
            val rows = cpuStat
                .lineSequence()
                .map { it.split(' ') }
                .filter { it.firstOrNull() == name }
                .toList()
            require(rows.size == 1 && rows.single().size == 2) { "Missing or duplicate cgroup $name" }
            return rows.single()[1].toLongOrNull()?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("Invalid cgroup $name")
        }
        require(cpuMax.matches(Regex("max [1-9][0-9]*"))) { "Native timing container has a CPU quota or no quota provenance" }
        require(cpus == "0,2") { "Native timing container effective CPUs differ from the pinned set" }
        require(Regex("(?m)^some avg10=[0-9]+\\.[0-9]+ ").containsMatchIn(pressure)) { "Host CPU pressure is unavailable" }
        return Telemetry(counter("usage_usec"), counter("nr_throttled"))
    }

    public fun requireProgress(
        before: Telemetry,
        after: Telemetry,
    ) {
        require(after.usageUsec > before.usageUsec && after.throttledPeriods >= before.throttledPeriods) {
            "Native timing container CPU counters did not advance monotonically"
        }
    }

    public fun schedule(): List<Sample> {
        val orders = listOf("ABC", "BCA", "CAB", "CBA", "BAC", "ACB")
        return orders
            .flatMapIndexed { block, arms ->
            arms.flatMap { arm ->
                (if (block % 2 == 0) listOf("full", "unique") else listOf("unique", "full"))
                    .map { mode -> Sample(index = 0, block = block + 1, arm = arm, mode = mode) }
            }
        }.mapIndexed { index, sample -> sample.copy(index = index + 1) }
    }

    /** Independent preflight: exactly one JVM per arm/mode, never a timing-acceptance series. */
    public fun readinessSchedule(): List<Sample> = schedule().take(6)

    /** A complete, source-matched six-fork smoke must precede a new diagnostic acquisition. */
    public fun requireReadiness(
        output: Path,
        revision: String,
        sourceDigest: String,
    ) {
        TierENativeTimingReadiness.verify(output, revision, sourceDigest)
    }

    public fun parse(
        arm: Char,
        mode: String,
        log: String,
    ): Observation {
        require(arm in "ABC" && mode in setOf("full", "unique")) { "Unknown timing diagnostic arm or mode" }

        fun row(prefix: String): String =
            log.lineSequence().filter { "$prefix:" in it }.singleOrNull()
            ?: throw IllegalArgumentException("Missing or duplicate timing diagnostic row: $prefix")

        fun field(
            line: String,
            key: String,
        ): Long =
            Regex("(?:^|\\s)$key=([0-9]+)(?:\\s|$)")
            .findAll(line)
            .map { it.groupValues[1].toLong() }
            .singleOrNull() ?: throw IllegalArgumentException("Missing or duplicate timing field: $key")

        val workload = field(row("agent-workload"), "workloadNs")
        val capture = log.lineSequence().filter { "mazewall-stack-agent: native-binds=" in it }.singleOrNull()
            ?: throw IllegalArgumentException("Missing or duplicate native capture summary")
        val count = field(capture, "stack-captures")
        require(
            workload > 0 &&
                count == 514L &&
                field(capture, "stack-failures") == 0L &&
                field(capture, "native-binds") > 0 &&
                field(capture, "proxied-binds") > 0,
        ) { "Native timing workload attribution changed" }
        val benchmark = row("tier-e-benchmark")
        val expected = if (mode == "full") "full-stream" else "unique-stack-syscall"
        require(Regex("(?:^|\\s)emission_mode=$expected(?:\\s|$)").containsMatchIn(benchmark)) { "Timing diagnostic mode differs" }
        require(field(benchmark, "cache_hits") == 504L && field(benchmark, "cache_misses") == 10L) {
            "Native timing cache population changed"
        }
        val stack = field(benchmark, "get_stack_trace_ns")
        require(stack > 0) { "Unmeasured stack phase" }
        val diagnostic = log.lineSequence().filter { "capture-timing-diagnostic:" in it }.toList()
        if (arm != 'C') {
            require(diagnostic.isEmpty()) { "Control arm emitted capture instrumentation" }
            return Observation(stack, workload, count, null, null, null)
        }
        require(diagnostic.size == 1) { "Instrumented arm requires one diagnostic row" }
        val data = diagnostic.single()
        require(field(data, "attempted") == count && field(data, "completed") == count && field(data, "invalid") == 0L) {
            "Incomplete diagnostic capture clocks"
        }
        val elapsed = field(data, "elapsed_ns")
        val cpu = field(data, "cpu_ns")
        // Wall and thread-CPU clocks are sampled in sequence, not at identical
        // boundaries. Their summed deltas can cross slightly without a failed
        // read; keep both raw values instead of inventing a clock error.
        require(elapsed > 0 && cpu > 0) { "Unmeasured capture CPU/elapsed timing" }
        val clockPair = field(data, "clock_pair_ns")
        require(field(data, "clock_pair_valid") == 1L && clockPair > 0) { "Invalid clock-pair overhead diagnostic" }
        for (counter in listOf("migrations", "voluntary_switches", "involuntary_switches")) field(data, counter)
        return Observation(stack, workload, count, elapsed, cpu, clockPair)
    }
}
