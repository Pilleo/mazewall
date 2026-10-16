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
        require(rows.none { "phase_contract_version=2" in it }) { "Legacy phase gate cannot qualify the v2 measurement contract" }
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

    /** Version two separates key formation and canonical lookup and gates their complete historical total. */
    public fun comparable(
        mode: String,
        observations: String,
        baseline: Map<String, Long>,
    ): Map<String, Long> {
        require(mode in setOf("full", "unique")) { "Unknown native phase mode" }
        val rows = observations.lineSequence().filter { "tier-e-benchmark:" in it }.toList()
        val captures = observations.lineSequence().filter { "mazewall-stack-agent:" in it && "stack-captures=" in it }.toList()
        val workloads = observations.lineSequence().filter { "agent-workload: workloadNs=" in it }.toList()
        require(rows.size == 3 && captures.size == 3 && workloads.size == 3) {
            "Comparable phase gate requires three complete native workloads"
        }

        fun number(
            line: String,
            field: String,
        ): Long {
            val values = Regex("(?:^|\\s)$field=([0-9]+)(?:\\s|$)").findAll(line).map { it.groupValues[1].toLong() }.toList()
            require(values.size == 1) { "Missing or duplicate native phase field: $field" }
            return values.single()
        }
        val required = listOf("get_stack_trace_ns", "raw_key_ns", "logical_metadata_ns", "canonical_cache_lookup_ns")
        val samples = rows.indices.map { index ->
            val row = rows[index]
            require(number(row, "phase_contract_version") == 2L) { "Native phase contract must be version two" }
            val expectedMode = if (mode == "full") "full-stream" else "unique-stack-syscall"
            require(Regex("(?:^|\\s)emission_mode=$expectedMode(?:\\s|$)").findAll(row).count() == 1) {
                "Native phase evidence was measured in a different emission mode"
            }
            val capture = number(captures[index], "stack-captures")
            require(
                capture == 514L &&
                number(captures[index], "stack-failures") == 0L &&
                number(captures[index], "native-binds") > 0 &&
                number(captures[index], "proxied-binds") > 0 &&
                number(workloads[index], "workloadNs") > 0,
            ) { "Native workload or attribution completeness changed" }
            val hits = number(row, "cache_hits")
            val misses = number(row, "cache_misses")
            require(hits > 0 && misses > 0 && Math.addExact(hits, misses) == capture) {
                "Comparable phase requires the same active canonical-cache population"
            }
            required.associateWith { field -> number(row, field).also { require(it > 0) { "Unmeasured native phase: $field" } } }
        }
        val baselineStack = requireNotNull(baseline["get_stack_trace_ns"])
        val baselineKey = requireNotNull(baseline["raw_key_ns"])
        val baselineLogical = requireNotNull(baseline["logical_metadata_ns"])
        val historicalTotal = Math.addExact(baselineKey, baselineLogical)

        fun limit(value: Long) = Math.multiplyExact(value, 125) / 100

        fun median(values: List<Long>) = values.sorted()[1]
        val stack = median(samples.map { it.getValue("get_stack_trace_ns") })
        val key = median(samples.map { it.getValue("raw_key_ns") })
        val lookup = median(samples.map { it.getValue("canonical_cache_lookup_ns") })
        val logical = median(samples.map { it.getValue("logical_metadata_ns") })
        val total = median(
            samples.map { sample ->
            Math.addExact(
                Math.addExact(sample.getValue("raw_key_ns"), sample.getValue("canonical_cache_lookup_ns")),
                sample.getValue("logical_metadata_ns"),
            )
        },
        )
        require(stack <= limit(baselineStack)) { "GetStackTrace phase exceeds unchanged historical limit: $stack" }
        require(key <= limit(baselineKey)) { "Raw key-construction phase exceeds unchanged historical limit: $key" }
        require(total <= limit(historicalTotal)) { "Combined key+lookup+logical work exceeds unchanged historical total: $total" }
        return mapOf(
            "phase_contract_version" to 2L,
            "get_stack_trace_ns" to stack,
            "raw_key_ns" to key,
            "canonical_cache_lookup_ns" to lookup,
            "logical_metadata_ns" to logical,
            "key_lookup_logical_total_ns" to total,
            "historical_total_ns" to historicalTotal,
            "historical_total_limit_ns" to limit(historicalTotal),
        )
    }
}
