package io.mazewall.profiler.tierE.tools

import java.nio.file.Files
import java.nio.file.Path

/** Active scripts are launch shims; the archived fixture is explicitly outside this policy. */
public object TierEActiveEntrypointPolicy {
    public val active: List<String> = listOf(
        "build_tier_e_benchmark_bundle.sh",
        "run_tier_e_trustworthy_benchmark.sh",
        "run_tier_e_rootful_gate.sh",
        "run_tier_e_sustained_capacity.sh",
        "run_tier_e_coverage_benchmark.sh",
        "run_tier_e_node_noise_probe.sh",
        "run_tier_e_split_privilege_fixture.sh",
        "run_tier_e_split_coverage_matrix.sh",
        "run_tier_e_split_negative_matrix.sh",
        "run_tier_e_split_matched_benchmark.sh",
        "run_tier_e_hybrid_fault_matrix.sh",
        "run_tier_e_hybrid_live_comparison.sh",
        "run_tier_e_release_benchmark.sh",
        "run_tier_e_matched_flavour_benchmark.sh",
        "run_tier_e_mapped_drain_microbenchmark.sh",
        "run_tier_e_decoder_microbenchmark.sh",
        "compare_tier_e_stack_transports.sh",
        "check_tier_e_benchmark.sh",
        "run_tier_e_checks.sh",
    )
    private val substantive = Regex("(?:^|\\s)(?:node|python[0-9]*|jq|docker|podman|ctest)(?:\\s|$)|for\\s*\\(\\(", RegexOption.MULTILINE)

    public fun check(file: Path): List<String> {
        val source = Files.readString(file)
        return buildList {
            if (substantive.containsMatchIn(source)) add("Tier E launcher contains non-Kotlin orchestration")
            if ("exec bash" !in source || "scripts/tier_e_tools" !in source) add("Tier E launcher does not exec Kotlin tooling")
        }
    }
}
