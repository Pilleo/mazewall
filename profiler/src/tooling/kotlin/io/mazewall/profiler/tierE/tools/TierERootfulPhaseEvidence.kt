package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Replay-only analysis of three *retained* fresh CTest samples per phase mode. */
public object TierERootfulPhaseEvidence {
    public fun verify(
        output: Path,
        baseline: Path,
    ): Result<Unit> {
        val failures = mutableListOf<Throwable>()
        for (mode in listOf("full", "unique")) {
            val verdict = runCatching {
                val samples = (1..3)
                    .map { index ->
                    val path = output.resolve("$mode-$index.log")
                    require(Files.isRegularFile(path)) { "Missing native phase sample: $path" }
                    Files.readString(path)
                }.joinToString("\n")
                TierEBenchmarkPhaseGate.comparable(mode, samples, TierEBenchmarkPhaseGate.baselines(baseline, mode))
            }
            val evidence = buildJsonObject {
                put("mode", mode)
                put("status", if (verdict.isSuccess) "PASS" else "INCOMPLETE")
                put("reason", verdict.exceptionOrNull()?.message ?: "complete")
                verdict.getOrNull()?.forEach { (name, value) -> put(name, value) }
                put("policy_eligibility", false)
            }
            Files.writeString(output.resolve("phase-$mode-verdict.txt"), "$evidence\n")
            verdict.exceptionOrNull()?.let(failures::add)
        }
        if (failures.isEmpty()) return Result.success(Unit)
        return Result.failure(
            IllegalStateException("Native phase evidence incomplete", failures.first()).apply {
            failures.drop(1).forEach(::addSuppressed)
        },
        )
    }
}
