package io.mazewall.profiler.tierE.stress

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Benchmark-only syscall oracle. Each proxied `write(2)` uses a unique byte count as its
 * operation identity, so the raw syscall arguments can be matched without reading user memory.
 */
internal object TierEWriteOracleWorkload {
    const val FIRST_OPERATION_LENGTH = 4096

    data class Result(
        val completedOperations: Int,
        val checksum: Long,
        val workloadNanos: Long,
    )

    enum class BatchPhase { WARMUP, MEASURED }

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
        while (gate != null &&
            !java.nio.file.Files
            .exists(gate)
        ) {
                Thread.sleep(1)
            }
        runBatches(warmups, batches, iterations).forEach { batch ->
            println(
                "tier-e-write-oracle-batch: phase=${batch.phase.name.lowercase()} index=${batch.index} " +
                    "operations=${batch.result.completedOperations} checksum=${batch.result.checksum} " +
                    "verified=true workloadNs=${batch.result.workloadNanos}",
            )
        }
    }

    fun expectedOperationIds(
        warmups: Int,
        batches: Int,
        iterations: Int,
    ): Set<Int> = (0 until (warmups + batches) * iterations).mapTo(LinkedHashSet()) { FIRST_OPERATION_LENGTH + it }

    fun runBatches(
        warmups: Int,
        batches: Int,
        iterations: Int,
    ): List<Batch> =
        buildList(warmups + batches) {
            repeat(warmups + batches) { ordinal ->
                val phase = if (ordinal < warmups) BatchPhase.WARMUP else BatchPhase.MEASURED
                val index = if (ordinal < warmups) ordinal + 1 else ordinal - warmups + 1
                add(Batch(phase, index, run(iterations, ordinal * iterations)))
            }
        }

    fun run(
        iterations: Int,
        firstOperationOffset: Int = 0,
    ): Result {
        require(iterations > 0) { "iterations must be positive" }
        val started = System.nanoTime()
        var checksum = 0L
        FileChannel.open(Path.of("/dev/null"), StandardOpenOption.WRITE).use { channel ->
            repeat(iterations) { offset ->
                val operationId = FIRST_OPERATION_LENGTH + firstOperationOffset + offset
                val buffer = ByteBuffer.allocateDirect(operationId)
                while (buffer.hasRemaining()) channel.write(buffer)
                checksum = checksum * 31 + operationId
            }
        }
        return Result(iterations, checksum, System.nanoTime() - started)
    }
}
