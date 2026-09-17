package io.mazewall.profiler.tierE.stress

import java.nio.file.Files
import java.nio.file.Path

/**
 * Deterministic file workload shared by the no-profiler, Tier E, and USER_NOTIF benchmarks.
 *
 * `completedOperations` is a logical write/read pair count, not a claim about the number of
 * kernel syscalls. The latter varies by JDK and filesystem implementation.
 */
internal object TierECommonFileWorkload {
    enum class BatchPhase { WARMUP, MEASURED }

    data class Result(
        val completedOperations: Int,
        val checksum: Long,
        val workloadNanos: Long,
    )

    data class Batch(
        val phase: BatchPhase,
        val index: Int,
        val result: Result,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val benchmark = args.firstOrNull() == "benchmark"
        require(if (benchmark) args.size == 6 else args.size == 3) {
            "usage: [benchmark <gate:path|0> <platform|virtual> <warmups> <batches> <iterations>]"
        }
        val gateArgument = if (benchmark) args[1] else args[0]
        val warmups = if (benchmark) args[3].toInt() else 0
        val batches = if (benchmark) args[4].toInt() else 1
        val iterations = if (benchmark) args[5].toInt() else args[2].toInt()
        val gate = gateArgument.takeIf { it.startsWith("gate:") }?.removePrefix("gate:")?.let(Path::of)
        while (gate != null && !Files.exists(gate)) Thread.sleep(1)
        runBatches(warmups, batches, iterations).forEach { batch ->
            println(
                "tier-e-common-workload-batch: phase=${batch.phase.name.lowercase()} index=${batch.index} " +
                    "operations=${batch.result.completedOperations} checksum=${batch.result.checksum} " +
                    "verified=true workloadNs=${batch.result.workloadNanos}",
            )
        }
    }

    fun runBatches(
        warmups: Int,
        batches: Int,
        iterations: Int,
    ): List<Batch> {
        require(warmups >= 0) { "warmups must not be negative" }
        require(batches > 0) { "batches must be positive" }
        return buildList(warmups + batches) {
            repeat(warmups) { index -> add(Batch(BatchPhase.WARMUP, index + 1, run(iterations))) }
            repeat(batches) { index -> add(Batch(BatchPhase.MEASURED, index + 1, run(iterations))) }
        }
    }

    fun run(iterations: Int): Result {
        require(iterations > 0) { "iterations must be positive" }
        val payloadPrefix = "mazewall-tier-e-common-workload:"
        val started = System.nanoTime()
        var checksum = 0L
        val file = Files.createTempFile("mazewall-tier-e-common", ".txt")
        try {
            repeat(iterations) { index ->
                val payload = "$payloadPrefix$index"
                Files.writeString(file, payload)
                check(Files.readString(file) == payload) { "shared workload readback mismatch at iteration $index" }
                checksum = checksum * 31 + payload.hashCode().toLong()
            }
        } finally {
            Files.deleteIfExists(file)
        }
        return Result(iterations, checksum, System.nanoTime() - started)
    }
}
