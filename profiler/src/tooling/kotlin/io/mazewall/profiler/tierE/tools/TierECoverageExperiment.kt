package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerEndpoint
import java.nio.file.Files
import java.nio.file.Path

/** Coverage-discovery presets over the same trustworthy host controller and Kotlin summary. */
public object TierECoverageExperiment {
    public fun settings(
        level: String,
        environment: Map<String, String> = System.getenv(),
    ): TierETrustworthySettings {
        require(level in setOf("smoke", "full")) { "Coverage experiment must be smoke or full" }
        return TierETrustworthySettings.load(
            environment +
            mapOf(
            "MAZEWALL_TIER_E_RUN_LEVEL" to "custom",
            "MAZEWALL_TIER_E_BENCHMARK_SCOPE" to "tier-e-coverage-discovery",
            "MAZEWALL_TIER_E_BENCHMARK_WORKLOAD" to "oracle",
            "MAZEWALL_TIER_E_CALIBRATION" to "false",
            "MAZEWALL_TIER_E_WARMUPS" to "3",
            "MAZEWALL_TIER_E_ITERATIONS" to "100",
            "MAZEWALL_TIER_E_BATCHES" to if (level == "smoke") "24" else "750",
            "MAZEWALL_TIER_E_FORKS" to if (level == "smoke") "1" else "5",
            "MAZEWALL_TIER_E_SUSTAINED_RATE_PER_SECOND" to "1250",
            "MAZEWALL_TIER_E_COLLECTOR_RECEIPT_CLOCK" to "monotonic",
        ),
        )
    }

    public fun run(
        level: String,
        repository: Path,
        output: Path,
        endpoint: ContainerEndpoint,
        image: String,
    ) {
        var failure: Throwable? = null
        try {
            TierETrustworthyRunner.run(
                settings(level),
                endpoint,
                repository,
                output,
                image,
                System.getenv("MAZEWALL_TIER_E_BENCHMARK_CPUS"),
            )
        } catch (error: Throwable) {
            failure = error
        } finally {
            if (Files.exists(output.resolve("results.jsonl"))) {
                try {
                    TierECoverageReport.write(output)
                } catch (summary: Throwable) {
                    if (failure == null) failure = summary else failure.addSuppressed(summary)
                }
            }
        }
        if (failure != null) throw failure
    }
}
