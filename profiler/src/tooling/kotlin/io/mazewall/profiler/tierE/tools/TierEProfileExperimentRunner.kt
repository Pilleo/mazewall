package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerEndpoint
import java.nio.file.Path

/** Profile-selected experiments; the ambient environment supplies only the local engine endpoint. */
public object TierEProfileExperimentRunner {
    public fun stageSettings(
        base: TierETrustworthySettings,
        schedule: TierESustainedCapacitySettings,
        stage: TierESustainedCapacitySettings.Stage,
        shadow: Boolean,
    ): TierETrustworthySettings =
        base.copy(
            runLevel = "custom",
            scope = "tier-e-sustained-collector",
            sustainedRate = stage.rate,
            sustainedLoad = schedule.load,
            receiptClock = schedule.receiptClock,
            calibration = false,
            iterations = schedule.iterations,
            warmups = schedule.warmups,
            batches = stage.batches,
            forks = stage.forks,
            benchmarkJavaOptions = listOfNotNull(
                base.benchmarkJavaOptions.takeIf(String::isNotBlank),
                "-Dmazewall.tier-e.benchmark-max-retained-records=${stage.maxRetainedRecords}",
                "-Dmazewall.tier-e.benchmark-strace-output=/artifacts/strace-shadow.log".takeIf { shadow },
            ).joinToString(" "),
        )

    public fun run(
        profile: TierEExperimentProfile,
        root: Path,
        output: Path,
        endpoint: ContainerEndpoint,
    ) {
        val cpus = profile.cpuSet
        when (profile.kind) {
            "trustworthy" -> TierETrustworthyRunner.run(profile.trustworthy, endpoint, root, output, profile.image, cpus)
            "node-noise" -> TierENodeNoiseProbe.run(
                root,
                output,
                endpoint,
                profile.image,
                profile.nodeNoise.runs,
                profile.nodeNoise.durationSeconds,
                profile.nodeNoise.cpu,
                profile.trustworthy,
                cpus ?: System.getenv("MAZEWALL_TIER_E_BENCHMARK_CPUS") ?: "0,1",
            )
            "capacity" -> {
                requireNotNull(cpus) { "Capacity profile requires explicit workload and collector CPUs" }
                val schedule = requireNotNull(profile.capacity)
                TierESustainedCapacityRunner.run(schedule, output, root, nodeNoise = { destination ->
                    val target = profile.trustworthy.copy(
                        runLevel = "custom",
                        scope = "tier-e-sustained-collector",
                        workload = "syscall_dense",
                        sustainedLoad = "near_capacity",
                        sustainedRate = 1000,
                        iterations = 10000,
                        warmups = 0,
                        batches = 1,
                        forks = 1,
                        calibration = false,
                        benchmarkJavaOptions = "-Dmazewall.tier-e.attachment-ready-file=/artifacts/attachment-ready",
                    )
                    TierENodeNoiseProbe.run(
                        root,
                        destination,
                        endpoint,
                        profile.image,
                        profile.nodeNoise.runs,
                        profile.nodeNoise.durationSeconds,
                        profile.nodeNoise.cpu,
                        target,
                        cpus,
                    )
                }) { stage, artifact, shadow ->
                    TierETrustworthyRunner.run(
                        stageSettings(profile.trustworthy, schedule, stage, shadow),
                        endpoint,
                        root,
                        artifact,
                        profile.image,
                        cpus,
                    )
                }
            }
            else -> error("Unknown experiment kind: ${profile.kind}")
        }
    }
}
