package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Replay-only analysis of three *retained* fresh CTest samples per phase mode. */
public object TierERootfulPhaseEvidence {
    private val modes = listOf("full", "unique")

    /** Pure analysis of retained inputs. This never publishes into the sample directory. */
    private fun analyze(
        samples: Path,
        baseline: Path,
    ): List<Pair<String, Result<Map<String, Long>>>> =
        modes.map { mode ->
            mode to runCatching {
                val logs = (1..3)
                    .map { index ->
                    val path = samples.resolve("$mode-$index.log")
                    require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Missing native phase sample: $path" }
                    Files.readString(path)
                }.joinToString("\n")
                TierEBenchmarkPhaseGate.comparable(mode, logs, TierEBenchmarkPhaseGate.baselines(baseline, mode))
            }
        }

    private fun evidence(
        mode: String,
        verdict: Result<Map<String, Long>>,
    ): JsonObject =
        buildJsonObject {
        put("mode", mode)
        put("status", if (verdict.isSuccess) "PASS" else "INCOMPLETE")
        put("reason", verdict.exceptionOrNull()?.message ?: "complete")
        verdict.getOrNull()?.forEach { (name, value) -> put(name, value) }
        put("policy_eligibility", false)
    }

    /** Deterministic Gradle replay. Failed inputs cannot leave an earlier COMPLETE terminal. */
    public fun replay(
        samples: Path,
        baseline: Path,
        output: Path,
    ): Result<Unit> =
        runCatching {
        require(Files.isDirectory(samples, NOFOLLOW_LINKS) && !Files.isSymbolicLink(output)) {
            "Replay requires an unsymlinked native sample directory and output"
        }
        val input = samples.toRealPath()
        val destination = output.toAbsolutePath().normalize()
        require(!destination.startsWith(input) && !input.startsWith(destination)) { "Replay output must not contain native input" }
        Files.createDirectories(destination)
        val owned = setOf("terminal.json", "phase-full-verdict.txt", "phase-unique-verdict.txt")
        require(Files.list(destination).use { entries -> entries.allMatch { it.fileName.toString() in owned } }) {
            "Replay output contains externally owned files"
        }
        for (name in owned) Files.deleteIfExists(destination.resolve(name))
        TierEMatrixTerminal.run(destination) {
            val decisions = analyze(input, baseline)
            for ((mode, verdict) in decisions) {
                Files.writeString(destination.resolve("phase-$mode-verdict.txt"), "${evidence(mode, verdict)}\n")
            }
            val failures = decisions.mapNotNull { it.second.exceptionOrNull() }
            if (failures.isNotEmpty()) {
                throw IllegalStateException("Native phase evidence incomplete", failures.first()).apply {
                    failures.drop(1).forEach(::addSuppressed)
                }
            }
        }
    }

    public fun verify(
        output: Path,
        baseline: Path,
    ): Result<Unit> {
        val decisions = analyze(output, baseline)
        for ((mode, verdict) in decisions) {
            Files.writeString(output.resolve("phase-$mode-verdict.txt"), "${evidence(mode, verdict)}\n")
        }
        val failures = decisions.mapNotNull { it.second.exceptionOrNull() }
        if (failures.isEmpty()) return Result.success(Unit)
        return Result.failure(
            IllegalStateException("Native phase evidence incomplete", failures.first()).apply {
            failures.drop(1).forEach(::addSuppressed)
        },
        )
    }
}
