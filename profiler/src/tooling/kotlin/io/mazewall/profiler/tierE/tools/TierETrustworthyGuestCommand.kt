package io.mazewall.profiler.tierE.tools

/** Exact JVM invocation after the host has admitted the pinned benchmark bundle. */
public object TierETrustworthyGuestCommand {
    public fun java(
        settings: TierETrustworthySettings,
        workloadCpu: Int,
        collectorCpu: Int,
    ): List<String> {
        require(workloadCpu >= 0 && collectorCpu >= 0 && workloadCpu != collectorCpu)
        val jvm = settings.benchmarkJavaOptions
            .trim()
            .takeIf(String::isNotEmpty)
            ?.split(Regex("\\s+")) ?: emptyList()
        val perfMap = settings.perfRecord && settings.perfJitMap
        return listOf("java") +
            jvm +
            (if (perfMap) listOf("-XX:+UnlockDiagnosticVMOptions", "-XX:+DumpPerfMapAtExit") else emptyList()) +
            listOf(
            "-Dmazewall.tier-e.canonical-cache=${settings.canonicalCache}",
            "-Dmazewall.tier-e.capture-profile=${settings.captureProfile}",
            "-Dmazewall.tier-e.sys-enter-attachment-backend=${settings.attachmentBackend}",
            "-Dmazewall.tier-e.kernel-coverage-mode=${settings.kernelCoverageMode}",
            "-Dmazewall.tier-e.sys-enter-attachment-shape=${settings.attachmentShape}",
            "-Dmazewall.tier-e.benchmark-scope=${settings.scope}",
            "-Dmazewall.tier-e.sustained-load=${settings.sustainedLoad}",
            "-Dmazewall.tier-e.sustained-rate-per-second=${settings.sustainedRate}",
            "-Dmazewall.tier-e.benchmark-workload=${settings.workload}",
            "-Dmazewall.tier-e.lifecycle-scenario=${settings.lifecycleScenario}",
            "-Dmazewall.tier-e.collector-receipt-clock=${settings.receiptClock}",
            "-Dmazewall.tier-e.calibration=${settings.calibration}",
            "-Dmazewall.tier-e.run-level=${settings.runLevel}",
            "-Dmazewall.tier-e.dump-perf-map=$perfMap",
            "-Dmazewall.tier-e.child-timeout-seconds=${settings.childTimeoutSeconds}",
            "-Dmazewall.tier-e.fixture-child-termination-after-millis=${settings.fixtureChildTerminationMillis}",
            "-Dmazewall.tier-e.user-notif-worker-timeout-seconds=${settings.workerTimeoutSeconds}",
            "-Dmazewall.tier-e.user-notif-child-timeout-seconds=${settings.userNotifChildTimeoutSeconds}",
            "--enable-native-access=ALL-UNNAMED",
                "-cp",
                "/bundle/runtime/*",
            "io.mazewall.profiler.tierE.stress.TierEMatchedBenchmark",
            "/bundle/libmazewall_stack_agent.so",
                settings.iterations.toString(),
                settings.warmups.toString(),
            settings.batches.toString(),
                settings.forks.toString(),
                workloadCpu.toString(),
                collectorCpu.toString(),
            "/artifacts/results.jsonl",
        )
    }
}
