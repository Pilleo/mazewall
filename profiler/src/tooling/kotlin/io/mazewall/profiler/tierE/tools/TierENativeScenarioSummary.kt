package io.mazewall.profiler.tierE.tools

/** Native scenario percentile summaries; a missing sample is failure, never an observed zero. */
public object TierENativeScenarioSummary {
    public data class Percentiles(
        val p50: Long,
        val p95: Long,
        val p99: Long,
        val samples: Int,
    )

    public fun medianThree(
        log: String,
        metric: String,
    ): Long {
        require(metric.matches(Regex("[a-z_]+"))) { "Invalid native phase name" }
        val values = Regex("(?:^|\\s)$metric=([0-9]+)(?:\\s|$)")
            .findAll(log)
            .map { it.groupValues[1].toLong() }
            .toList()
            .sorted()
        require(values.size == 3) { "Native phase requires three real samples: $metric" }
        return values[1]
    }

    public fun release(
        log: String,
        metric: String,
    ): Percentiles {
        require(metric.matches(Regex("[a-z_]+"))) { "Invalid native metric name" }
        val values = Regex("(?:^|\\s)$metric=([0-9]+)(?:\\s|$)")
            .findAll(log)
            .map { it.groupValues[1].toLong() }
            .toList()
            .takeLast(10)
            .sorted()
        require(values.size == 10) { "Missing native scenario samples for $metric" }
        return Percentiles(values[4], values[9], values[9], values.size)
    }

    public fun matched(
        log: String,
        scenario: String,
    ): Percentiles {
        require(scenario.matches(Regex("agent_matched_benchmark_[a-z_]+"))) { "Invalid native scenario" }
        val values = mutableListOf<Long>()
        var active = false
        for (line in log.lineSequence()) {
            if (Regex("Start [0-9]+: $scenario(?:\\s|$)").containsMatchIn(line)) active = true
            if (active) Regex("agent-workload-batch:.*durationNs=([0-9]+)").find(line)?.let { values += it.groupValues[1].toLong() }
            if (Regex("Test #[0-9]+:").containsMatchIn(line)) active = false
        }
        require(values.size == 50) { "Missing matched native samples for $scenario" }
        val sorted = values.sorted()
        return Percentiles(sorted[24], sorted[47], sorted[49], values.size)
    }
}
