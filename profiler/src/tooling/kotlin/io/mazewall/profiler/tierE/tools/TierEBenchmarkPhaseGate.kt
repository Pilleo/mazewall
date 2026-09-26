package io.mazewall.profiler.tierE.tools

import java.nio.file.Files
import java.nio.file.Path

/** Median-of-three native phase gate, independent of coverage and policy eligibility. */
public object TierEBenchmarkPhaseGate {
    public fun baselines(
        file: Path,
        mode: String,
    ): Map<String, Long> {
        require(mode in setOf("full", "unique"))
        val values = Files
            .readAllLines(file)
            .filter { it.matches(Regex("^(FULL|UNIQUE)_[A-Z_]+=[0-9]+$")) }
            .associate { line -> line.substringBefore('=') to line.substringAfter('=').toLong() }
        val prefix = mode.uppercase()
        return listOf("get_stack_trace_ns", "raw_key_ns", "logical_metadata_ns").associateWith { phase ->
            values["${prefix}_${phase.uppercase()}"] ?: throw IllegalArgumentException("Missing Tier E native baseline: $phase")
        }
    }

    public fun check(
        mode: String,
        observations: String,
        baseline: Map<String, Long>,
    ): Map<String, Long> {
        require(mode in setOf("full", "unique"))
        val rows = observations.lineSequence().filter { "tier-e-benchmark:" in it }.toList()
        require(rows.size == 3) { "Tier E native phase needs exactly three samples" }
        return listOf("get_stack_trace_ns", "raw_key_ns", "logical_metadata_ns").associateWith { phase ->
            val median = rows
                .map { line ->
                Regex("(?:^|\\s)$phase=([0-9]+)(?:\\s|$)")
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
                    ?: throw IllegalArgumentException("Missing native benchmark phase: $phase")
            }.sorted()[1]
            val limit = Math.multiplyExact(requireNotNull(baseline[phase]), 125) / 100
            require(median <= limit) { "Tier E native phase $phase median $median exceeds $limit" }
            median
        }
    }
}
