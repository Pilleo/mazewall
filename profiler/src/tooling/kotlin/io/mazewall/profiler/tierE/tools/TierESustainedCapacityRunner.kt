package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant

/** Pilot search, optional failure shadow, and paired decision stages over fresh trustworthy runs. */
public object TierESustainedCapacityRunner {
    public fun run(
        config: TierESustainedCapacitySettings,
        output: Path,
        repository: Path? = null,
        nodeNoise: (Path) -> Unit = { error("Node-noise controller not configured") },
        execute: (TierESustainedCapacitySettings.Stage, Path, Boolean) -> Unit,
    ) {
        Files.createDirectories(output)
        require(Files.list(output).use { it.findAny().isEmpty }) { "Tier E capacity requires fresh artifacts" }

        fun event(
            stage: String,
            state: String,
            message: String = "",
        ) {
            Files.writeString(
                output.resolve("capacity-runner-events.jsonl"),
                buildJsonObject {
                put("kind", "sustained_capacity_stage")
                put("timestamp", Instant.now().toString())
                put("stage", stage)
                put("state", state)
                put("message", message)
            }.toString() +
                "\n",
                    CREATE,
                APPEND,
            )
        }
        TierEMatrixTerminal.run(output) {
            if (config.runDecision) {
                val root = requireNotNull(repository) { "Capacity decision needs a Git checkout" }
                val state = TierETrustworthySourceState.read(root)
                require(state.getValue("benchmark_input_dirty_count").toString() == "0") {
                    "Capacity decision rejects dirty benchmark inputs"
                }
                event("runner", "PROVENANCE", "benchmark_input_dirty_entries=0")
            }
            event("runner", "STARTED", "rates=${config.pilotRates.joinToString(" ")}; load=${config.load}")
            var ceiling = 0

            fun shadow(stage: TierESustainedCapacitySettings.Stage) {
                if (!config.shadowOnFailure) return
                val shadow = stage.copy(forks = 1, label = "${stage.label}-shadow")
                val path = output.resolve("${shadow.label}-rate-${shadow.rate}")
                try {
                    event(shadow.label, "STARTED", "diagnostic_replay=true")
                    execute(shadow, path, true)
                    event(shadow.label, "COMPLETED", "diagnostic_replay=true")
                } catch (failure: Exception) {
                    event(shadow.label, "INCOMPLETE", "diagnostic_replay=true")
                }
            }
            for (rate in config.pilotRates) {
                val stage = config.plan(rate, config.stageSeconds, 1, "pilot")
                val label = "${stage.label}-rate-${stage.rate}"
                val path = output.resolve(label)
                event(label, "STARTED", "forks=1 batches=${stage.batches} max_retained_records=${stage.maxRetainedRecords}")
                val passed = try {
                    execute(stage, path, false)
                    TierESustainedCapacityStage.eligible(path.resolve("results.jsonl"), config.retainedBudgetBytes)
                } catch (failure: Exception) {
                    false
                }
                if (passed) {
                    ceiling = rate
                    event(label, "COMPLETED", "eligible=true")
                } else {
                    shadow(stage)
                    event(label, "INCOMPLETE", "eligible=false; ceiling_rate=$ceiling")
                    break
                }
            }
            Files.writeString(
                output.resolve("capacity-manifest.json"),
                buildJsonObject {
                put("kind", "tier_e_sustained_capacity_pilot")
                put("generated_at", Instant.now().toString())
                put("loss_free_ceiling_rate_per_second", ceiling)
                put("retained_budget_bytes", config.retainedBudgetBytes)
                put("retained_budget_fraction", 0.75)
                put("stage_seconds", config.stageSeconds)
                put("max_retained_records", config.maxRetainedRecordsOverride)
                put("shadow_on_failure", config.shadowOnFailure)
                put("policy_eligibility", false)
            }.toString() +
                "\n",
            )
            require(ceiling > 0) { "No eligible Tier E capacity pilot stage" }
            if (config.runDecision) {
                for (fraction in listOf(50, 75, 90)) {
                    val rate = ceiling * fraction / 100
                    if (rate <= 0) continue
                    val stage = config.plan(rate, config.decisionStageSeconds, config.decisionForks, "decision-${fraction}pct")
                    val label = "${stage.label}-rate-${stage.rate}"
                    val path = output.resolve(label)
                    event(label, "STARTED", "forks=${stage.forks} batches=${stage.batches}")
                    execute(stage, path, false)
                    require(TierESustainedCapacityStage.eligible(path.resolve("results.jsonl"), config.retainedBudgetBytes)) {
                        "Tier E capacity decision stage is ineligible: $label"
                    }
                    event(label, "COMPLETED", "eligible=true")
                }
            }
            if (config.runNodeNoiseProbe) {
                val path = output.resolve("node-noise")
                event("node-noise", "STARTED", "artifact=$path")
                nodeNoise(path)
                event("node-noise", "COMPLETED", "artifact=${path.resolve("summary.json")}")
            }
            event("runner", "PASSED", "loss_free_ceiling_rate=$ceiling")
        }
    }
}
