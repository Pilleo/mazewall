package io.mazewall.profiler.tierE.tools

/** Non-qualifying native timing comparison: fixed order and strict raw sample admission. */
public object TierENativeTimingDiagnostic {
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
        val capture = row("mazewall-stack-agent")
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
        require(elapsed > 0 && cpu > 0 && cpu <= elapsed) { "Invalid capture CPU/elapsed timing" }
        val clockPair = field(data, "clock_pair_ns")
        require(field(data, "clock_pair_valid") == 1L && clockPair > 0) { "Invalid clock-pair overhead diagnostic" }
        for (counter in listOf("migrations", "voluntary_switches", "involuntary_switches")) field(data, counter)
        return Observation(stack, workload, count, elapsed, cpu, clockPair)
    }
}
