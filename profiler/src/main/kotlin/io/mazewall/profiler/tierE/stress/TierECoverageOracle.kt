package io.mazewall.profiler.tierE.stress

import java.util.Collections
import java.util.Random

/** Independent, seeded workload specification. No captured events define its denominator. */
internal object TierECoverageOracle {
    const val ENABLED_PROPERTY = "mazewall.tier-e.coverage-discovery"
    const val SEED_PROPERTY = "mazewall.tier-e.coverage-seed"
    const val PATH_COUNT = 72

    enum class Verdict { PASS, INCOMPLETE, FAILED, UNVERIFIED }

    fun schedule(
        operations: Int = 75_000,
        seed: Long,
    ): List<Int> {
        require(operations >= 72 && operations % 24 == 0)
        val infrequent = if (operations == 75_000) 124 else maxOf(1, (operations / 24 - 1) / 25)
        val common = operations / 24 - infrequent - 1
        require(common > 0)
        val repeated = buildList {
            repeat(24) { path -> repeat(common) { add(path) } }
            repeat(24) { path -> repeat(infrequent) { add(path + 24) } }
        }.toMutableList()
        Collections.shuffle(repeated, Random(seed))
        val singletons = (48 until 72).toMutableList()
        Collections.shuffle(singletons, Random(seed xor 0x5a5aL))
        val singletonPositions = (0 until 24).associate { index ->
            index * (operations - 1) / 23 to singletons[index]
        }
        var repeatedIndex = 0
        return List(operations) { position -> singletonPositions[position] ?: repeated[repeatedIndex++] }
    }

    data class Observation(
        val operationId: Int,
        val actualPaths: List<Int>,
        val exact: Boolean,
        val acknowledged: Boolean,
        val syscallMatches: Boolean = true,
        val elapsedNanos: Long? = null,
    )

    data class Report(
        val verdict: Verdict,
        val expected: Set<Int>,
        val recovered: Set<Int>,
        val wrong: Set<Int>,
        val unexpected: Set<Int>,
        val firstDiscoveryNanos: Map<Int, Long>,
    ) {
        val missing: Set<Int> get() = expected - recovered

        fun json(): String {
            fun Set<Int>.jsonArray() = sorted().joinToString(",", "[", "]")
            val groups = listOf("common" to 0..23, "infrequent" to 24..47, "singleton" to 48..71)
            val recall = groups.joinToString(",") { (name, range) ->
                val denominator = expected.count { it in range }
                val numerator = recovered.count { it in range }
                "\"$name\":{\"expected\":$denominator,\"recovered\":$numerator,\"recall\":${if (denominator == 0) "null" else (numerator.toDouble() / denominator).toString()}}"
            }
            val discoveries = firstDiscoveryNanos.entries
                .sortedBy { it.value }
                .mapIndexed { index, (path, time) ->
                "{\"path\":$path,\"elapsed_ns\":$time,\"recovered\":${index + 1}}"
            }.joinToString(",", "[", "]")
            return "{\"coverage_verdict\":\"$verdict\",\"expected\":${expected.jsonArray()}," +
                "\"recovered\":${recovered.jsonArray()},\"missing\":${missing.jsonArray()}," +
                "\"wrong_operation_ids\":${wrong.jsonArray()},\"unexpected_operation_ids\":${unexpected.jsonArray()}," +
                "\"recall\":{$recall},\"discoveries\":$discoveries}"
        }
    }

    fun evaluate(
        expectedOperations: Map<Int, Int>?,
        observations: Iterable<Observation>,
    ): Report {
        if (expectedOperations == null || expectedOperations.isEmpty()) {
            return Report(Verdict.UNVERIFIED, emptySet(), emptySet(), emptySet(), emptySet(), emptyMap())
        }
        require(expectedOperations.values.all { it in 0 until PATH_COUNT })
        val expected = expectedOperations.values.toSet()
        val recovered = mutableSetOf<Int>()
        val wrong = mutableSetOf<Int>()
        val unexpected = mutableSetOf<Int>()
        val firstDiscovery = mutableMapOf<Int, Long>()
        observations.forEach { observation ->
            val path = expectedOperations[observation.operationId]
            when {
                path == null -> unexpected.add(observation.operationId)
                !observation.exact -> Unit
                !observation.syscallMatches || observation.actualPaths != listOf(path) -> wrong.add(observation.operationId)
                observation.acknowledged -> {
                    recovered.add(path)
                    observation.elapsedNanos?.let { time ->
                        require(time >= 0)
                        firstDiscovery.merge(path, time, ::minOf)
                    }
                }
            }
        }
        val verdict = when {
            wrong.isNotEmpty() || unexpected.isNotEmpty() -> Verdict.FAILED
            recovered != expected -> Verdict.INCOMPLETE
            else -> Verdict.PASS
        }
        return Report(verdict, expected, recovered, wrong, unexpected, firstDiscovery)
    }
}
