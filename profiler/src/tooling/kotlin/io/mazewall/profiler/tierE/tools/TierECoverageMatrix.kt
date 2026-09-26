package io.mazewall.profiler.tierE.tools

/** Explicit experiment schedule; each long fork pairs its baseline with six rotated profiled arms. */
public object TierECoverageMatrix {
    public data class Arm(
        val fork: Int,
        val seed: Int,
        val name: String,
        val transport: String,
        val emission: String,
        val marker: String,
        val recovery: String,
        val baseline: Boolean = false,
    )

    private val legacy = listOf(
        Arm(0, 0, "dictionary-full-stream", "dictionary", "full-stream", "v1", "disabled"),
        Arm(0, 0, "dictionary-unique-edge", "dictionary", "unique-stack-syscall", "v1", "disabled"),
        Arm(0, 0, "inline-full-stream", "inline-per-invocation", "full-stream", "v1", "disabled"),
        Arm(0, 0, "inline-unique-edge", "inline-per-invocation", "unique-stack-syscall", "v1", "disabled"),
    )
    private val hybrid = listOf(
        Arm(0, 0, "dictionary-full-stream-v1", "dictionary", "full-stream", "v1", "disabled"),
        Arm(0, 0, "dictionary-full-stream-v2", "dictionary", "full-stream", "v2", "disabled"),
        Arm(0, 0, "dictionary-full-stream-hybrid", "dictionary", "full-stream", "v2", "recover"),
        Arm(0, 0, "dictionary-unique-edge-v1", "dictionary", "unique-stack-syscall", "v1", "disabled"),
        Arm(0, 0, "dictionary-unique-edge-v2", "dictionary", "unique-stack-syscall", "v2", "disabled"),
        Arm(0, 0, "dictionary-unique-edge-hybrid", "dictionary", "unique-stack-syscall", "v2", "recover"),
    )

    public fun plan(
        forks: Int,
        longMatched: Boolean,
        variants: String,
        hybridOnly: Boolean = false,
    ): List<Arm> {
        require(forks > 0)
        require(variants in setOf("legacy", "hybrid")) { "Unknown Tier E matrix variants: $variants" }
        val short = if (variants == "legacy") {
            legacy
        } else if (hybridOnly) {
            hybrid.filter { it.name.endsWith("hybrid") }
        } else {
            hybrid
        }
        if (longMatched) {
            return (1..forks).flatMap { fork ->
            val seed = 23063 + fork - 1
            listOf(Arm(fork, seed, "baseline", "dictionary", "full-stream", "v1", "disabled", baseline = true)) +
                hybrid.indices.map { index -> hybrid[(index + seed % hybrid.size) % hybrid.size].copy(fork = fork, seed = seed) }
        }
        }
        return short.flatMap { flavor -> (1..forks).map { fork -> flavor.copy(fork = fork, seed = 23063 + fork - 1) } }
    }
}
