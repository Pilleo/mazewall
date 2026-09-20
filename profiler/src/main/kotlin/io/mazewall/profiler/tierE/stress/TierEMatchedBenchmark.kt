package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.internal.attribution.TierECaptureProfile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.random.Random

/** Privileged matched benchmark with warmed, in-process batches and fork-level rotation. */
internal object TierEMatchedBenchmark {
    private const val COMMON_WORKLOAD = "common-file-workload"
    private const val WRITE_ORACLE_WORKLOAD = "write-oracle-workload"
    private val allModes = listOf(
        Mode.BASELINE,
        Mode.FULL_STREAM,
        Mode.UNIQUE_EDGE,
        Mode.INLINE_FULL_STREAM,
        Mode.INLINE_UNIQUE_EDGE,
        Mode.USER_NOTIF_NO_STACK,
        Mode.USER_NOTIF,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..8) {
            "usage: <agent.so> [iterations] [warmups] [batches] [forks] [workload-cpu] [collector-cpu] [results.jsonl]"
        }
        val requestedConfig = Config(
            agent = Path.of(args[0]).toAbsolutePath(),
            iterations = args.getOrElse(1) { "100" }.toInt(),
            warmups = args.getOrElse(2) { "3" }.toInt(),
            batches = args.getOrElse(3) { "10" }.toInt(),
            forks = args.getOrElse(4) { "5" }.toInt(),
            workloadCpu = args.getOrElse(5) { "0" },
            collectorCpu = args.getOrElse(6) { "1" },
            artifact = Path.of(args.getOrElse(7) { "build/tier-e-matched-benchmark/results.jsonl" }).toAbsolutePath(),
            scope = Scope.fromProperty(System.getProperty("mazewall.tier-e.benchmark-scope", "full")),
            workload = Workload.fromProperty(System.getProperty("mazewall.tier-e.benchmark-workload", "oracle")),
            lifecycleScenario = System.getProperty("mazewall.tier-e.lifecycle-scenario", "normal"),
            dumpPerfMapAtExit = System.getProperty("mazewall.tier-e.dump-perf-map", "false").toBooleanStrictOrNull()
                ?: error("mazewall.tier-e.dump-perf-map must be true or false"),
            childTimeoutSeconds = System.getProperty("mazewall.tier-e.child-timeout-seconds", "180").toLong(),
            childTerminationAfterMillis = System.getProperty("mazewall.tier-e.fixture-child-termination-after-millis", "0").toLong(),
            userNotifWorkerTimeoutSeconds = System.getProperty("mazewall.tier-e.user-notif-worker-timeout-seconds", "480").toLong(),
            userNotifChildTimeoutSeconds = System.getProperty("mazewall.tier-e.user-notif-child-timeout-seconds", "540").toLong(),
            calibrationEnabled = System.getProperty("mazewall.tier-e.calibration", "false").toBooleanStrictOrNull()
                ?: error("mazewall.tier-e.calibration must be true or false"),
            runLevel = System.getProperty("mazewall.tier-e.run-level", "custom"),
            sustainedLoad = TierESustainedCollectorDecision.Load.fromProperty(
                System.getProperty("mazewall.tier-e.sustained-load", "below_capacity"),
            ),
            sustainedRatePerSecond = System.getProperty(TierESharedOpenatOracleWorkload.RATE_PER_SECOND_PROPERTY, "0").toLong(),
        )
        require(Files.isRegularFile(requestedConfig.agent)) { "missing agent: ${requestedConfig.agent}" }
        require(
            requestedConfig.iterations > 0 &&
                requestedConfig.warmups >= 0 &&
                requestedConfig.batches > 0 &&
                requestedConfig.forks > 0 &&
                requestedConfig.childTimeoutSeconds > 0 &&
                requestedConfig.childTerminationAfterMillis >= 0 &&
                requestedConfig.userNotifWorkerTimeoutSeconds > 0 &&
                requestedConfig.userNotifChildTimeoutSeconds > requestedConfig.userNotifWorkerTimeoutSeconds,
        ) {
            "benchmark dimensions and deadlines must be positive; USER_NOTIF child timeout must exceed its worker timeout"
        }
        require(requestedConfig.sustainedRatePerSecond >= 0L) {
            "${TierESharedOpenatOracleWorkload.RATE_PER_SECOND_PROPERTY} must be non-negative"
        }
        require(requestedConfig.scope != Scope.TIER_E_SUSTAINED_COLLECTOR || requestedConfig.sustainedRatePerSecond > 0L) {
            "tier-e-sustained-collector requires a positive ${TierESharedOpenatOracleWorkload.RATE_PER_SECOND_PROPERTY}"
        }
        requestedConfig.artifact.parent?.createDirectories()
        val calibration = if (requestedConfig.calibrationEnabled) calibrate(requestedConfig) else null
        val config = requestedConfig.copy(iterations = calibration?.selection?.operations ?: requestedConfig.iterations)
        Files.deleteIfExists(config.artifact)
        append(config.artifact, metadata(config, calibration))
        try {
            val forks = buildList {
                val total = variants(config.scope, 0).size * config.forks
                var completed = 0
                repeat(config.forks) { fork ->
                    variants(config.scope, fork).forEach { variant ->
                        val result = run(variant, fork + 1, config)
                        add(result)
                        completed += 1
                        println(
                            progressLine(
                                completed = completed,
                                total = total,
                                fork = result.fork,
                                variant = result.variant.jsonName(result.scope),
                                workloadCorrectness = result.verdict.workloadCorrectness,
                                wholeSessionEvidence = result.verdict.wholeSessionEvidence,
                            ),
                        )
                    }
                }
            }
            verify(forks, config)
            append(config.artifact, summary(forks, config))
            append(config.artifact, terminal(success = true, message = "completed"))
            val profiledForks = forks.filter {
                it.variant.mode != Mode.BASELINE && !it.variant.isBenchmarkControl
            }
            val correctness = when {
                profiledForks.any { it.verdict.workloadCorrectness == TierEBenchmarkContract.CorrectnessState.FAILED } -> "FAILED"
                profiledForks.isNotEmpty() && profiledForks.all { it.verdict.workloadCorrectness == TierEBenchmarkContract.CorrectnessState.PASS } -> "PASS"
                else -> "UNVERIFIED"
            }
            println("tier-e-matched-benchmark: artifact=${config.artifact} forks=${config.forks} workload_correctness=$correctness")
        } catch (failure: Throwable) {
            append(config.artifact, terminal(success = false, message = failure.message ?: failure::class.java.name))
            throw failure
        }
    }

    private fun calibrate(config: Config): CalibrationOutcome {
        val artifact = config.artifact.resolveSibling("calibration-results.jsonl")
        Files.deleteIfExists(artifact)
        val pilot = config.copy(
            iterations = TierEBenchmarkCalibration.START_OPERATIONS,
            warmups = 3,
            batches = 3,
            forks = 1,
            artifact = artifact,
            calibrationEnabled = false,
        )
        append(artifact, "{\"kind\":\"calibration-start\",\"operations\":${pilot.iterations}}")
        val pilotResults = variants(config.scope, 0).map { run(it, 0, pilot) }
        requireCalibrationCorrectness(pilotResults)
        var selection = TierEBenchmarkCalibration.select(
            observations = calibrationObservations(pilotResults, pilot),
            targetSessionBatches = config.warmups + config.batches,
        )
        var verification: List<ForkResult>? = null
        val verifiedOperations = TierEBenchmarkCalibration.firstSafeAtOrBelow(selection.operations) { operations ->
            val candidate = runCatching {
                if (operations == pilot.iterations) pilotResults else runCalibrationVerification(config, artifact, operations)
            }
            val safe = candidate.getOrNull()?.let { results ->
                runCatching { requireCalibrationCorrectness(results) }.isSuccess && calibrationWithinSafetyLimits(results)
            } == true
            if (safe) {
                verification = candidate.getOrThrow()
            } else {
                val reason = candidate.exceptionOrNull()?.message ?: "correctness or retention safety gate failed"
                append(
                    artifact,
                    "{\"kind\":\"calibration-candidate-rejected\",\"operations\":$operations," +
                        "\"reason\":\"${escape(reason)}\"}",
                )
            }
            safe
        }
        if (verifiedOperations != selection.operations) {
            selection = selection.copy(operations = verifiedOperations, targetMet = false, status = "unmet")
        }
        val verified = checkNotNull(verification)
        val fastest = verified.minOf { median(it.batches.map(Batch::workloadNanos)) }
        val targetMet = fastest >= TierEBenchmarkCalibration.TARGET_FASTEST_BATCH_NANOS && selection.targetMet
        selection = selection.copy(targetMet = targetMet, status = if (targetMet) "met" else "unmet")
        append(
            artifact,
            "{\"kind\":\"calibration-terminal\",\"success\":true,\"selected_operations\":${selection.operations}," +
                "\"target\":\"${selection.status}\",\"fastest_batch_ns\":$fastest}",
        )
        return CalibrationOutcome(selection, artifact.fileName.toString())
    }

    private fun runCalibrationVerification(
        config: Config,
        artifact: Path,
        operations: Int,
    ): List<ForkResult> {
        val verification = config.copy(
            iterations = operations,
            warmups = 3,
            batches = 3,
            forks = 1,
            artifact = artifact,
            calibrationEnabled = false,
        )
        return variants(config.scope, 0).map { run(it, -operations, verification) }
    }

    private fun calibrationObservations(
        results: List<ForkResult>,
        config: Config,
    ): List<TierEBenchmarkCalibration.Observation> =
        results.map { result ->
        TierEBenchmarkCalibration.Observation(
            variant = result.variant.jsonName(result.scope),
            operations = config.iterations,
            measuredBatchNanos = median(result.batches.map(Batch::workloadNanos)),
            peakRetainedBytes = maxOf(result.evidence.phases.peakRetainedBytes, result.evidence.phases.nativeRetainedBytes),
            retainedRecords = result.evidence.observations,
            totalSessionBatches = config.warmups + config.batches,
        )
    }

    private fun requireCalibrationCorrectness(results: List<ForkResult>) {
        check(results.all { it.verdict.workloadCorrectness == TierEBenchmarkContract.CorrectnessState.PASS }) {
            "calibration workload correctness failed"
        }
        check(
            results.filter { it.variant.mode != Mode.BASELINE }.all {
            it.verdict.wholeSessionEvidence == TierEBenchmarkContract.EvidenceState.COMPLETE
        },
        ) { "calibration produced incomplete profiler evidence" }
    }

    private fun calibrationWithinSafetyLimits(results: List<ForkResult>): Boolean =
        results.maxOf { median(it.batches.map(Batch::workloadNanos)) } <= TierEBenchmarkCalibration.MAX_SLOWEST_BATCH_NANOS &&
            results.maxOf { maxOf(it.evidence.phases.peakRetainedBytes, it.evidence.phases.nativeRetainedBytes) } <=
            TierEBenchmarkCalibration.RETAINED_LIMIT_BYTES &&
            results.maxOf { it.evidence.observations } <= TierEBenchmarkCalibration.RETAINED_RECORD_LIMIT

    private fun run(
        variant: Variant,
        fork: Int,
        config: Config,
    ): ForkResult {
        val mode = variant.mode
        val captureProfile = captureProfileFor(variant)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        val canonicalCache = System.getProperty("mazewall.tier-e.canonical-cache", "enabled")
        val collectorReceiptClock = System.getProperty("mazewall.tier-e.collector-receipt-clock", "disabled")
        val attachmentReadyFile = System.getProperty("mazewall.tier-e.attachment-ready-file", "")
        val childJvmPrefix = listOf(java) +
            if (config.dumpPerfMapAtExit) {
                listOf("-XX:+UnlockDiagnosticVMOptions", "-XX:+DumpPerfMapAtExit")
            } else {
                emptyList()
            }
        val ringSubmission = ringSubmissionFor(variant)
        val ownedArguments = ownedArgumentsFor(variant)
        val definitions = Files.createTempFile("tier-e-matched-${mode.jsonName}-", ".mzsd")
        val captureYield = if (mode.tierE) Files.createTempFile("tier-e-matched-${mode.jsonName}-", ".mzcy") else null
        Files.deleteIfExists(definitions)
        captureYield?.let(Files::deleteIfExists)
        val command = when (mode) {
            Mode.BASELINE ->
                childJvmPrefix +
                listOf(
                "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
                "-D${TierESharedOpenatOracleWorkload.PROFILE_PROPERTY}=${config.workload.profile.wireName}",
                "-D${TierECommonFileWorkload.APPLICATION_KIND_PROPERTY}=${config.workload.applicationKind.wireName}",
                "-D${TierESharedOpenatOracleWorkload.RATE_PER_SECOND_PROPERTY}=${config.sustainedRatePerSecond}",
                "-cp",
                classpath,
                config.workload.className,
                "benchmark",
                "0",
                "platform",
                config.warmups.toString(),
                config.batches.toString(),
                config.iterations.toString(),
            )
            Mode.FULL_STREAM, Mode.UNIQUE_EDGE, Mode.INLINE_FULL_STREAM, Mode.INLINE_UNIQUE_EDGE ->
                childJvmPrefix +
                listOf(
                "-Dmazewall.tier-e.canonical-cache=${variant.cacheMode ?: canonicalCache}",
                "-Dmazewall.tier-e.capture-profile=${captureProfile.wireName}",
                "-Dmazewall.tier-e.native-phase-timing=${nativePhaseTimingFor(variant).enabled}",
                "-Dmazewall.tier-e.benchmark-allocation-accounting=${config.scope in setOf(
                    Scope.TIER_E_RING_DECODE,
                    Scope.TIER_E_COLLECTOR_APPEND,
                    Scope.TIER_E_RING_ARGUMENT_VIEW,
                    Scope.TIER_E_TASK_IDENTITY,
                    Scope.TIER_E_KNOWN_JOIN,
                    Scope.TIER_E_UNIQUE_EDGE_OBSERVER,
                )}",
                "-Dmazewall.tier-e.ring-decode=${ringDecodeFor(variant).wireName}",
                "-Dmazewall.tier-e.ring-argument-list=${ringArgumentListFor(variant).wireName}",
                "-Dmazewall.tier-e.ring-argument-view=${ringArgumentViewFor(variant).wireName}",
                "-Dmazewall.tier-e.task-identity=${taskIdentityFor(variant).wireName}",
                "-Dmazewall.tier-e.known-join=${knownJoinFor(variant).wireName}",
                "-Dmazewall.tier-e.ring-submission=${ringSubmission.wireName}",
                "-Dmazewall.tier-e.owned-arguments=${ownedArguments.wireName}",
                "-Dmazewall.tier-e.ready-transfer=${readyTransferFor(variant).wireName}",
                "-Dmazewall.tier-e.collector-result=${collectorResultFor(variant).wireName}",
                "-Dmazewall.tier-e.unique-edge-observer=${uniqueEdgeObserverFor(variant).enabled}",
                "-Dmazewall.tier-e.idle-poll-backoff-nanos=${pollBackoffFor(variant)}",
                "-Dmazewall.tier-e.idle-poll-policy=${pollPolicyFor(variant)}",
                "-Dmazewall.tier-e.idle-poll-max-backoff-nanos=${maxPollBackoffFor(variant)}",
                "-Dmazewall.tier-e.collector-receipt-clock=$collectorReceiptClock",
                "-Dmazewall.tier-e.attachment-ready-file=$attachmentReadyFile",
                "-Dmazewall.tier-e.marker-transport=${markerTransportFor(variant).wireName}",
                "-Dmazewall.tier-e.live-publication=${variant.publication == Publication.LIVE}",
                "-Dmazewall.tier-e.expect-retention-exhaustion=${config.scope == Scope.TIER_E_RETENTION_EXHAUSTION}",
                "-Dmazewall.tier-e.lifecycle-scenario=${config.lifecycleScenario}",
                "-Dmazewall.tier-e.capture-yield=$captureYield",
                "-Dmazewall.tier-e.ring-capacity-bytes=8388608",
                "-D${TierESharedOpenatOracleWorkload.PROFILE_PROPERTY}=${config.workload.profile.wireName}",
                "-D${TierECommonFileWorkload.APPLICATION_KIND_PROPERTY}=${config.workload.applicationKind.wireName}",
                "-D${TierESharedOpenatOracleWorkload.RATE_PER_SECOND_PROPERTY}=${config.sustainedRatePerSecond}",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                classpath,
                TierEKernelSmoke::class.java.name,
                config.agent.toString(),
                "auto",
                classpath,
                definitions.toString(),
                "platform",
                config.iterations.toString(),
                config.workloadCpu,
                mode.emissionMode,
                mode.stackTransport,
                config.workload.kernelArgument,
                "benchmark",
                config.warmups.toString(),
                config.batches.toString(),
            )
            Mode.USER_NOTIF, Mode.USER_NOTIF_NO_STACK ->
                childJvmPrefix +
                listOf(
                "--enable-native-access=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
                "-D${TierESharedOpenatOracleWorkload.PROFILE_PROPERTY}=${config.workload.profile.wireName}",
                "-D${TierECommonFileWorkload.APPLICATION_KIND_PROPERTY}=${config.workload.applicationKind.wireName}",
                "-D${TierESharedOpenatOracleWorkload.RATE_PER_SECOND_PROPERTY}=${config.sustainedRatePerSecond}",
                "-cp",
                classpath,
                UserNotifOracleSmoke::class.java.name,
                "benchmark",
                config.warmups.toString(),
                config.batches.toString(),
                config.iterations.toString(),
                mode.userNotifStackOption,
                config.workload.userNotifArgument,
                config.userNotifWorkerTimeoutSeconds.toString(),
            )
        }
        val cpu = if (mode.tierE) config.collectorCpu else config.workloadCpu
        append(config.artifact, "{\"kind\":\"fork-start\",\"variant\":\"${variant.jsonName(config.scope)}\",\"fork\":$fork}")
        val childLogs = config.artifact.resolveSibling("child-logs")
        childLogs.createDirectories()
        val outputFile = childLogs.resolve("${variant.jsonName(config.scope)}-fork-$fork.log")
        Files.deleteIfExists(outputFile)
        val started = System.nanoTime()
        val process = ProcessBuilder(listOf("taskset", "-c", cpu) + command)
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile())
            .start()
        val injectedChildTermination = shouldTerminateChildAfterStart(config.childTerminationAfterMillis, mode.tierE)
        if (injectedChildTermination) {
            Thread.sleep(config.childTerminationAfterMillis)
            process.destroyForcibly()
        }
        val childTimeoutSeconds = if (mode.userNotif) {
            config.userNotifChildTimeoutSeconds
        } else {
            config.childTimeoutSeconds
        }
        val telemetry = awaitProcess(process, childTimeoutSeconds)
        val completed = telemetry.completed
        if (!completed) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
        val output = Files.readString(outputFile)
        val elapsedNanos = System.nanoTime() - started
        if (!completed || process.exitValue() != 0) {
            val classifiedFailure = classifiedChildFailure(output, injectedChildTermination)
            val failureClassJson = classifiedFailure?.let { ",\"failure_class\":\"$it\"" }.orEmpty()
            append(
                config.artifact,
                "{\"kind\":\"failure\",\"variant\":\"${variant.jsonName(
                    config.scope,
                )}\",\"fork\":$fork,\"timed_out\":${!completed}$failureClassJson,\"output_path\":\"child-logs/${outputFile.fileName}\",\"output\":\"${escape(output)}\"}",
            )
            error("${variant.jsonName(config.scope)} fork $fork ${if (completed) "failed" else "timed out"}:\n$output")
        }
        val batches = BATCH_PATTERN
            .findAll(output)
            .filter { it.groupValues[1] == "measured" }
            .map { match ->
            Batch(match.groupValues[2].toInt(), match.groupValues[3].toLong(), match.groupValues[4].toLong(), match.groupValues[5].toLong())
        }.toList()
        val execLifecycleFixture = config.lifecycleScenario == "exec_transition" &&
            variant.mode !in setOf(Mode.BASELINE, Mode.USER_NOTIF_NO_STACK, Mode.USER_NOTIF)
        check(batches.size == config.batches || (execLifecycleFixture && batches.isEmpty())) {
            "${mode.jsonName} fork $fork emitted ${batches.size} measured batches"
        }
        val evidence = Evidence(
            metricOrZero(output, "observations"),
            metricOrZero(output, "resolved"),
            metricOrZero(output, "workload_resolved"),
            metricOrZero(output, "workload_unlinks_resolved"),
            metricOrZero(output, "unknown_no_active"),
            metricOrZero(output, "ambient_inactive"),
            metricOrZero(output, "unknown_other"),
            metricOrZero(output, "wrong"),
            metricOrZero(output, "lost"),
            metricOrZero(output, "failures"),
            metricOrZero(output, "dropped"),
            metricOrZero(output, "reconciliation_admitted_invocations"),
            metricOrZero(output, "reconciliation_exact_invocations"),
            metricOrZero(output, "reconciliation_failed_invocations"),
            metricOrZero(output, "reconciliation_kernel_active_observations"),
            output.contains("workloadFrames=true"),
            output.contains("evidence_complete=true") || output.contains("evidenceComplete=true"),
            output.contains("producer_closed=true"),
            selectedMarkerTransport(output),
            (mode == Mode.BASELINE || output.contains("unique_edge_observer_enabled=false")) &&
                batches.all { it.operations == config.iterations.toLong() },
            output.contains("operation_oracle_expectation=detached_inactive"),
            output.contains("operation_oracle_expectation=terminal_incomplete"),
            output.contains("operation_oracle_expectation=capture_failure_unknown"),
            config.lifecycleScenario == "exec_transition" && mode.tierE,
            output.contains("operation_oracle_expectation=callback_suppressed_outer"),
            metricOrZero(output, "callback_probe_resolved"),
            output.contains("unique_edge_observer_enabled=false"),
            output.contains("profile_semantic_stack_complete=true"),
            semanticStackDigest(output),
            TierEBenchmarkContract.OperationOracle(
                available = output.contains("operation_oracle_available=true"),
                expected = metricOrZero(output, "expected_operations"),
                matched = metricOrZero(output, "matched_operations"),
                representativeOnly = output.contains("operation_oracle_expectation=unique_representative"),
                candidates = metricOrZero(output, "candidate_operations"),
                acknowledged = metricOrZero(output, "acknowledged_operations"),
                suppressed = metricOrZero(output, "suppressed_representatives"),
                unresolved = metricOrZero(output, "unresolved_operations"),
                missing = metricOrZero(output, "missing_operations"),
                duplicates = metricOrZero(output, "duplicate_operations"),
                wrong = metricOrZero(output, "wrong_operations"),
                unclassified = metricOrZero(output, "unclassified_operations"),
            ),
            phaseMetrics(output),
            CaptureYieldMetrics(
                metricOrZero(output, "capture_yield_records"),
                metricOrZero(output, "capture_yield_captured"),
                metricOrZero(output, "capture_yield_capture_failed"),
                metricOrZero(output, "capture_yield_admission_failed"),
                metricOrZero(output, "capture_yield_excluded"),
                metricOrZero(output, "capture_yield_exact_joined"),
                metricOrZero(output, "capture_yield_missing_definition"),
                metricOrZero(output, "capture_yield_outcome_mismatch"),
                metricOrZero(output, "capture_yield_unrecorded_definitions"),
                captureYieldBindings(output),
            ),
            UserNotifPhaseMetrics(
                metricOrZero(output, "user_notif_timed_operations"),
                metricOrZero(output, "user_notif_operation_ns"),
                metricOrZero(output, "user_notif_blocked_before_listener_ns"),
                metricOrZero(output, "user_notif_listener_to_ack_ns"),
                metricOrZero(output, "user_notif_stack_capture_ns"),
                metricOrZero(output, "user_notif_queue_ns"),
                metricOrZero(output, "user_notif_ack_to_return_ns"),
            ),
        )
        val contractEvidence = evidence.contractEvidence()
        val applicationSemanticFrames =
            applicationWorkloadRequiresSemanticFrames(config.workload.jsonName) && contractEvidence.workloadFrames
        val evaluatedVerdict = TierEBenchmarkContract.evaluate(
            mode.tierE,
            mode.userNotif,
            mode.requiresWorkloadFrames || applicationSemanticFrames,
            if (applicationSemanticFrames) {
                contractEvidence.copy(controlledWorkloadVerified = true)
            } else {
                contractEvidence
            },
            requiresOperationOracle = mode.userNotif && userNotifOperationOracleRequired(config.workload.jsonName),
        )
        val verdict = if (variant.isBenchmarkControl) {
            TierEBenchmarkContract.Verdict(
                workloadCorrectness = TierEBenchmarkContract.CorrectnessState.UNVERIFIED,
                wholeSessionEvidence = TierEBenchmarkContract.EvidenceState.INCOMPLETE,
                failures = listOf("benchmark_ring_submission_disabled"),
            )
        } else {
            evaluatedVerdict
        }
        val captureEfficiency = captureEfficiency(
            executedOperations = if (mode.tierE) (config.warmups + config.batches).toLong() * config.iterations else 0,
            exactCaptures = evidence.captureYield.captured,
            resolvedSyscalls = evidence.captureYield.bindings.values
                .sumOf { it.resolvedSyscalls },
            productiveCaptures = evidence.captureYield.bindings.values
                .sumOf { it.resolvedInvocations },
            getStackTraceNanos = evidence.phases.getStackTraceNanos,
            fullStream = mode.emissionMode == "full-stream",
            exactJoinComplete = admittedCapturesJoined(
                captured = evidence.captureYield.captured,
                captureFailed = evidence.captureYield.captureFailed,
                admissionFailed = evidence.captureYield.admissionFailed,
                exactJoined = evidence.captureYield.exactJoined,
                outcomeMismatch = evidence.captureYield.outcomeMismatch,
                unrecordedDefinitions = evidence.captureYield.unrecordedDefinitions,
            ),
            evidenceComplete = evidence.evidenceComplete,
            observedLosses = evidence.lost,
            attributionFailures = evidence.attributionFailures,
        )
        val result = ForkResult(
            config.scope,
            config.lifecycleScenario,
            config.sustainedLoad,
            config.sustainedRatePerSecond,
            variant,
            config.workload.profile,
            fork,
            batches,
            evidence,
            captureEfficiency,
            verdict,
            elapsedNanos,
            telemetry.targetPid,
            telemetry.cpuNanos,
            telemetry.peakRssBytes,
        )
        append(config.artifact, forkJson(result, output))
        batches.forEach { batch -> append(config.artifact, sampleJson(variant, config.scope, config.workload.profile, fork, batch)) }
        Files.deleteIfExists(definitions)
        captureYield?.let(Files::deleteIfExists)
        return result
    }

    private fun verify(
        forks: List<ForkResult>,
        config: Config,
    ) {
        val expectedForks = variants(config.scope, 0).size * config.forks
        check(forks.size == expectedForks) { "expected $expectedForks forks, received ${forks.size}" }
        // The shared-file workload checksum includes a fresh temporary path per JVM. Its child-local
        // verification is authoritative; cross-process benchmark comparison uses declared operation counts.
        forks.forEach { result ->
            val execLifecycleFixture = config.lifecycleScenario == "exec_transition" && result.variant.mode.tierE
            check(result.batches.size == config.batches || (execLifecycleFixture && result.batches.isEmpty())) {
                "${result.variant.mode.jsonName} has incomplete batches"
            }
            result.batches.forEach { batch ->
                check(batch.operations == config.iterations.toLong()) { "${result.variant.mode.jsonName} operation mismatch" }
            }
            if (result.variant.isBenchmarkControl) {
                check(result.verdict.workloadCorrectness == TierEBenchmarkContract.CorrectnessState.UNVERIFIED) {
                    "${result.variant.jsonName(config.scope)} ring control must remain unverified"
                }
                check(result.verdict.wholeSessionEvidence == TierEBenchmarkContract.EvidenceState.INCOMPLETE) {
                    "${result.variant.jsonName(config.scope)} ring control must remain incomplete"
                }
                check(result.evidence.operationOracle.expected > 0L && result.evidence.operationOracle.matched == 0L) {
                    "${result.variant.jsonName(config.scope)} ring control unexpectedly retained observations"
                }
                return@forEach
            }
            if (config.scope == Scope.TIER_E_RETENTION_EXHAUSTION) {
                check(result.verdict.wholeSessionEvidence == TierEBenchmarkContract.EvidenceState.INCOMPLETE) {
                    "${result.variant.jsonName(config.scope)} must expose bounded-retention incompleteness"
                }
                check(result.evidence.lost > 0L && result.evidence.resolved > 0L) {
                    "${result.variant.jsonName(config.scope)} did not exercise partial bounded retention"
                }
                check(result.evidence.operationOracle.wrong == 0L && result.evidence.operationOracle.duplicates == 0L) {
                    "${result.variant.jsonName(config.scope)} corrupted retained operation evidence: ${result.evidence.operationOracle}"
                }
                return@forEach
            }
            check(result.verdict.workloadCorrectness != TierEBenchmarkContract.CorrectnessState.FAILED) {
                "${result.variant.jsonName(config.scope)} workload failure: ${result.verdict.failures}"
            }
            if (config.scope == Scope.TIER_E_PRODUCTION_SELECTION && result.variant.markerTransport == MarkerTransport.AUTO) {
                check(result.evidence.selectedMarkerTransport == MarkerTransport.USDT.wireName) {
                    "AUTO must select validated USDT, got ${result.evidence.selectedMarkerTransport ?: "no selected transport"}"
                }
            }
        }
    }

    internal fun progressLine(
        completed: Int,
        total: Int,
        fork: Int,
        variant: String,
        workloadCorrectness: TierEBenchmarkContract.CorrectnessState,
        wholeSessionEvidence: TierEBenchmarkContract.EvidenceState,
    ): String =
        "tier-e-matched-benchmark: progress=$completed/$total fork=$fork variant=$variant " +
            "workload_correctness=$workloadCorrectness whole_session_evidence=$wholeSessionEvidence"

    /** Classifies native fail-closed publication outcomes without treating them as usable evidence. */
    internal fun failureClass(output: String): String? =
        when {
        output.contains("definition_publication_failure=encoded_snapshot_exceeds_retained_limit") -> "inline_snapshot_exceeds_retained_limit"
        else -> null
        }

    internal fun classifiedChildFailure(
        output: String,
        injectedTermination: Boolean,
    ): String? = if (injectedTermination) "injected_child_termination" else failureClass(output)

    private fun selectedMarkerTransport(output: String): String? = Regex("tier-e-kernel-smoke: marker_transport=([^\\s]+)").find(output)?.groupValues?.get(1)

    internal fun semanticStackDigest(output: String): String? = Regex("profile_semantic_stack_digest=([0-9a-f]{64})").find(output)?.groupValues?.get(1)

    private fun metadata(
        config: Config,
        calibration: CalibrationOutcome?,
    ): String {
        val cacheMode = System.getProperty("mazewall.tier-e.canonical-cache", "enabled")
        val configuredProfile = configuredCaptureProfile()
        val captureProfile = escape(configuredProfile.wireName)
        val captureProfileStrategy = if (config.scope == Scope.TIER_E_CAPTURE_PROFILE) "per_variant" else "configured"
        val sourceRevision = escape(System.getenv("MAZEWALL_BENCHMARK_SOURCE_REVISION") ?: "unknown")
        val sourceDirty = escape(System.getenv("MAZEWALL_BENCHMARK_SOURCE_DIRTY") ?: "unknown")
        val javaVersion = escape(System.getProperty("java.version"))
        val operatingSystem = escape(System.getProperty("os.name") + " " + System.getProperty("os.version"))
        return "{\"kind\":\"metadata\",\"timestamp\":\"${Instant.now()}\",\"iterations\":${config.iterations}," +
            "\"warmups\":${config.warmups},\"batches\":${config.batches},\"forks\":${config.forks}," +
            "\"capture_profile_id\":\"${configuredProfile.profileId}\",\"capture_profile_selector_digest\":\"${configuredProfile.selectorDigest}\"," +
            "\"workload_cpu\":\"${config.workloadCpu}\",\"collector_cpu\":\"${config.collectorCpu}\"," +
            "\"canonical_cache\":\"$cacheMode\",\"capture_profile\":\"$captureProfile\",\"capture_profile_strategy\":\"$captureProfileStrategy\"," +
            "\"scope\":\"${config.scope.jsonName}\"," +
            "\"sustained_load\":\"${config.sustainedLoad.wireName}\",\"sustained_rate_per_second\":${config.sustainedRatePerSecond}," +
            "\"run_level\":\"${escape(config.runLevel)}\",\"calibration_enabled\":${config.calibrationEnabled}," +
            "\"calibration_target\":${calibration?.let { "\"${it.selection.status}\"" } ?: "null"}," +
            "\"calibration_artifact\":${calibration?.let { "\"${escape(it.artifact)}\"" } ?: "null"}," +
            "\"child_timeout_seconds\":${config.childTimeoutSeconds}," +
            "\"fixture_child_termination_after_millis\":${config.childTerminationAfterMillis}," +
            "\"user_notif_worker_timeout_seconds\":${config.userNotifWorkerTimeoutSeconds}," +
            "\"user_notif_child_timeout_seconds\":${config.userNotifChildTimeoutSeconds}," +
            "\"workload\":\"${config.workload.jsonName}\",\"workload_profile\":\"${config.workload.profile.wireName}\",\"source_revision\":\"$sourceRevision\",\"source_dirty\":\"$sourceDirty\",\"java_version\":\"$javaVersion\",\"os\":\"$operatingSystem\"}"
    }

    private fun summary(
        forks: List<ForkResult>,
        config: Config,
    ): String {
        val baselineByFork = forks
            .filter { it.variant.mode == Mode.BASELINE }
            .associateBy { it.fork }
        val variants = forks.groupBy { it.variant.jsonName(config.scope) }.entries.joinToString(",") { (name, values) ->
            val batches = values.flatMap { it.batches }.map { it.workloadNanos }
            val operations = values.sumOf { result -> result.batches.sumOf { it.operations } }
            val workloadNanos = batches.sum()
            val pairedAddedPerOperation = values.mapNotNull { result ->
                baselineByFork[result.fork]
                    ?.takeIf {
                        result.variant.mode != Mode.BASELINE &&
                        result.verdict.workloadCorrectness == TierEBenchmarkContract.CorrectnessState.PASS &&
                        result.verdict.wholeSessionEvidence == TierEBenchmarkContract.EvidenceState.COMPLETE
                    }?.let { baseline ->
                    addedNanosPerProfiledOperation(
                        candidateWorkloadNanos = result.batches.sumOf { it.workloadNanos },
                        baselineWorkloadNanos = baseline.batches.sumOf { it.workloadNanos },
                        operations = result.batches.sumOf { it.operations },
                    )
                }
            }
            val medianAddedPerOperation = pairedAddedPerOperation.takeIf { it.isNotEmpty() }?.let(::medianDouble)
            val operationsPerSecond = operationsPerSecond(operations, workloadNanos)
            val estimatedSingleCoreFraction = medianAddedPerOperation?.let {
                estimatedSingleCoreFraction(operationsPerSecond, it)
            }
            val elapsed = values.map { it.elapsedNanos }
            val lifecycleOverhead = values.map { timingAccounting(it.elapsedNanos, it.batches.map { batch -> batch.workloadNanos }).lifecycleOverheadNanos }
            val complete = values.count { it.verdict.wholeSessionEvidence == TierEBenchmarkContract.EvidenceState.COMPLETE }
            val performanceEvidence = performanceEvidence(
                values.first().verdict.workloadCorrectness,
                values.first().verdict.wholeSessionEvidence,
            )
            val timingSummary = if (batches.isEmpty()) {
                "\"median_ns\":null,\"p95_ns\":null,\"p99_ns\":null,"
            } else {
                "\"median_ns\":${median(batches)},\"p95_ns\":${percentile(batches, 95.0)}," +
                    "\"p99_ns\":${percentile(batches, 99.0)},"
            }
            "\"$name\":{$timingSummary\"whole_session_complete_forks\":$complete," +
                "\"median_elapsed_ns\":${median(elapsed)},\"median_lifecycle_overhead_ns\":${median(lifecycleOverhead)}," +
                "\"measured_operations\":$operations,\"profiled_operations_per_second\":$operationsPerSecond," +
                "\"paired_added_ns_per_profiled_operation\":${medianAddedPerOperation ?: "null"}," +
                "\"estimated_single_core_fraction\":${estimatedSingleCoreFraction ?: "null"}," +
                "\"forks\":${values.size},\"performance_evidence\":\"$performanceEvidence\"}"
        }
        val profiledForks = forks.filter {
            it.variant.mode != Mode.BASELINE && !it.variant.isBenchmarkControl
        }
        val correctness = when {
            profiledForks.any { it.verdict.workloadCorrectness == TierEBenchmarkContract.CorrectnessState.FAILED } -> "FAILED"
            profiledForks.isNotEmpty() && profiledForks.all { it.verdict.workloadCorrectness == TierEBenchmarkContract.CorrectnessState.PASS } -> "PASS"
            else -> "UNVERIFIED"
        }
        return "{\"kind\":\"summary\",\"timestamp\":\"${Instant.now()}\",\"workload_correctness\":\"$correctness\"," +
            "$variants,${pairedSummary(forks, config)}\"sample_count\":${forks.sumOf { it.batches.size }}," +
            "\"expected_per_variant\":${config.forks * config.batches}}"
    }

    private fun sustainedCollectorJson(result: ForkResult): String {
        if (result.scope != Scope.TIER_E_SUSTAINED_COLLECTOR) return "\"sustained_collector\":null"
        val requested = result.batches.sumOf { it.operations }
        val achieved = result.evidence.operationOracle.expected
            .coerceIn(0L, requested)
        val resolved = result.evidence.operationOracle.matched
            .coerceIn(0L, achieved)
        val verdict = TierESustainedCollectorDecision.evaluate(
            TierESustainedCollectorDecision.Sample(
                load = result.sustainedLoad,
                requestedOperations = requested,
                achievedOperations = achieved,
                resolvedOperations = resolved,
                observedLosses = result.evidence.lost,
                producerClosed = result.evidence.producerClosed,
                evidenceComplete = result.evidence.evidenceComplete,
            ),
        )
        val achievedRate = operationsPerSecond(achieved, result.batches.sumOf { it.workloadNanos })
        return buildString {
            append("\"sustained_collector\":{\"load\":\"").append(result.sustainedLoad.wireName)
            append("\",\"requested_rate_per_second\":").append(result.sustainedRatePerSecond)
            append(",\"achieved_rate_per_second\":").append(achievedRate)
            append(",\"requested_operations\":").append(requested)
            append(",\"achieved_operations\":").append(achieved)
            append(",\"resolved_operations\":").append(resolved)
            append(",\"evidence_integrity\":\"").append(verdict.evidenceIntegrity).append('\"')
            append(",\"performance_eligible\":").append(verdict.performanceEligible)
            append(",\"exclusion_reason\":").append(verdict.exclusionReason?.let { "\"$it\"" } ?: "null")
            append('}')
        }
    }

    private fun sampleJson(
        variant: Variant,
        scope: Scope,
        workloadProfile: TierESharedOpenatOracleWorkload.Profile,
        fork: Int,
        batch: Batch,
    ): String =
        "{\"kind\":\"sample\",\"variant\":\"${variant.jsonName(scope)}\",\"mode\":\"${variant.mode.jsonName}\"," +
            "\"transport\":\"${variant.mode.stackTransport}\",\"cache\":\"${variant.cacheMode ?: "not_applicable"}\"," +
            "\"publication\":\"${variant.publication.jsonName}\",\"marker_transport\":\"${markerTransportFor(variant).wireName}\",\"capture_profile\":\"${captureProfileFor(
                variant,
            ).wireName}\",\"ring_decode\":\"${ringDecodeFor(variant).wireName}\",\"workload_profile\":\"${workloadProfile.wireName}\",\"fork\":$fork,\"batch\":${batch.index}," +
            "\"workload_ns\":${batch.workloadNanos},\"operations\":${batch.operations},\"checksum\":${batch.checksum}}"

    internal fun performanceEvidence(
        workloadCorrectness: TierEBenchmarkContract.CorrectnessState,
        wholeSessionEvidence: TierEBenchmarkContract.EvidenceState,
    ): String =
        when {
        wholeSessionEvidence == TierEBenchmarkContract.EvidenceState.COMPLETE -> "COMPLETE"
        workloadCorrectness == TierEBenchmarkContract.CorrectnessState.PASS &&
            wholeSessionEvidence == TierEBenchmarkContract.EvidenceState.NOT_APPLICABLE -> "BASELINE_VERIFIED"
        else -> "INCOMPLETE"
    }

    private fun forkJson(
        result: ForkResult,
        output: String,
    ): String {
        val evidence = result.evidence
        val timing = timingAccounting(result.elapsedNanos, result.batches.map { it.workloadNanos })
        val fields = listOf(
            "\"kind\":\"fork-output\"",
            "\"variant\":\"${result.variant.jsonName(result.scope)}\"",
            "\"mode\":\"${result.variant.mode.jsonName}\"",
            "\"lifecycle_scenario\":\"${result.lifecycleScenario}\"",
            "\"marker_transport\":\"${markerTransportFor(result.variant).wireName}\"",
            "\"selected_marker_transport\":${result.evidence.selectedMarkerTransport?.let { "\"$it\"" } ?: "null"}",
            "\"capture_profile\":\"${captureProfileFor(result.variant).wireName}\"",
            "\"capture_profile_id\":\"${captureProfileFor(result.variant).profileId}\"",
            "\"capture_profile_selector_digest\":\"${captureProfileFor(result.variant).selectorDigest}\"",
            "\"capture_profile_domain\":\"${captureProfileFor(result.variant).captureDomain}\"",
            "\"native_phase_timing\":\"${nativePhaseTimingFor(result.variant).wireName}\"",
            "\"ring_decode\":\"${ringDecodeFor(result.variant).wireName}\"",
            "\"ring_submission\":\"${ringSubmissionFor(result.variant).wireName}\"",
            "\"owned_arguments\":\"${ownedArgumentsFor(result.variant).wireName}\"",
            "\"ready_transfer\":\"${readyTransferFor(result.variant).wireName}\"",
            "\"workload_profile\":\"${result.workloadProfile.wireName}\"",
            sustainedCollectorJson(result),
            "\"fork\":${result.fork}",
            "\"target_pid\":${result.targetPid}",
            "\"elapsed_ns\":${result.elapsedNanos}",
            "\"workload_total_ns\":${timing.workloadNanos}",
            "\"profiled_operations_per_second\":${operationsPerSecond(result.batches.sumOf { it.operations }, timing.workloadNanos)}",
            "\"lifecycle_overhead_ns\":${timing.lifecycleOverheadNanos}",
            "\"cpu_ns\":${result.cpuNanos}",
            "\"cpu_time_available\":${result.cpuNanos != null}",
            "\"peak_rss_bytes\":${result.peakRssBytes}",
            "\"workload_correctness\":\"${result.verdict.workloadCorrectness}\"",
            "\"whole_session_evidence\":\"${result.verdict.wholeSessionEvidence}\"",
            "\"verdict_failures\":[${result.verdict.failures.joinToString(",") { "\"${escape(it)}\"" }}]",
            "\"observations\":${evidence.observations}",
            "\"resolved\":${evidence.resolved}",
            "\"workload_resolved\":${evidence.workloadResolved}",
            "\"workload_unlinks_resolved\":${evidence.workloadUnlinksResolved}",
            "\"unknown_no_active\":${evidence.unknownNoActive}",
            "\"ambient_inactive\":${evidence.ambientInactive}",
            "\"callback_suppression_scenario\":${evidence.callbackSuppressionScenario}",
            "\"callback_probe_resolved\":${evidence.callbackProbeResolved}",
            "\"unknown_other\":${evidence.unknownOther}",
            "\"wrong\":${evidence.wrong}",
            "\"lost\":${evidence.lost}",
            "\"attribution_failures\":${evidence.attributionFailures}",
            "\"dropped\":${evidence.dropped}",
            "\"reconciliation_admitted_invocations\":${evidence.reconciliationAdmittedInvocations}",
            "\"reconciliation_exact_invocations\":${evidence.reconciliationExactInvocations}",
            "\"reconciliation_failed_invocations\":${evidence.reconciliationFailedInvocations}",
            "\"reconciliation_kernel_active_observations\":${evidence.reconciliationKernelActiveObservations}",
            "\"expected_operations\":${evidence.operationOracle.expected}",
            "\"matched_operations\":${evidence.operationOracle.matched}",
            "\"candidate_operations\":${evidence.operationOracle.candidates}",
            "\"acknowledged_operations\":${evidence.operationOracle.acknowledged}",
            "\"suppressed_operations\":${evidence.operationOracle.suppressed}",
            "\"unresolved_operations\":${evidence.operationOracle.unresolved}",
            "\"missing_operations\":${evidence.operationOracle.missing}",
            "\"duplicate_operations\":${evidence.operationOracle.duplicates}",
            "\"wrong_operations\":${evidence.operationOracle.wrong}",
            "\"unclassified_operations\":${evidence.operationOracle.unclassified}",
            "\"operation_oracle_available\":${evidence.operationOracle.available}",
            "\"operation_oracle_representative_only\":${evidence.operationOracle.representativeOnly}",
            "\"profile_semantic_stack_complete\":${evidence.profileSemanticStackComplete}",
            "\"profile_semantic_stack_digest\":${evidence.profileSemanticStackDigest?.let { "\"$it\"" } ?: "null"}",
            "\"duplicate_acknowledgement_accounting\":\"unavailable\"",
            "\"evidence_complete\":${evidence.evidenceComplete}",
            "\"phases\":${phaseJson(evidence.phases)}",
            "\"capture_yield\":${captureYieldJson(evidence.captureYield)}",
            "\"capture_efficiency\":${captureEfficiencyJson(result.captureEfficiency)}",
            "\"user_notif_phases\":${userNotifPhaseJson(evidence.userNotifPhases)}",
            "\"output\":\"${escape(output)}\"",
        )
        return fields.joinToString(",", prefix = "{", postfix = "}")
    }

    internal fun timingAccounting(
        elapsedNanos: Long,
        workloadDurationsNanos: List<Long>,
    ): TimingAccounting {
        val workloadNanos = workloadDurationsNanos.sum()
        require(elapsedNanos >= workloadNanos) { "fork elapsed time cannot be shorter than measured workload" }
        return TimingAccounting(workloadNanos, elapsedNanos - workloadNanos)
    }

    internal data class TimingAccounting(
        val workloadNanos: Long,
        val lifecycleOverheadNanos: Long,
    )

    internal fun operationsPerSecond(
        operations: Long,
        workloadNanos: Long,
    ): Double {
        require(operations >= 0) { "operations must not be negative" }
        if (operations == 0L && workloadNanos == 0L) return 0.0
        require(workloadNanos > 0) { "workload duration must be positive" }
        return operations.toDouble() * 1_000_000_000.0 / workloadNanos
    }

    internal fun addedNanosPerProfiledOperation(
        candidateWorkloadNanos: Long,
        baselineWorkloadNanos: Long,
        operations: Long,
    ): Double? = if (operations == 0L) null else (candidateWorkloadNanos - baselineWorkloadNanos).toDouble() / operations

    internal fun estimatedSingleCoreFraction(
        profiledOperationsPerSecond: Double,
        addedNanosPerProfiledOperation: Double,
    ): Double = profiledOperationsPerSecond * addedNanosPerProfiledOperation / 1_000_000_000.0

    private fun medianDouble(values: List<Double>): Double =
        values.sorted().let { sorted ->
        if (sorted.size % 2 ==
        0
        ) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        } else {
            sorted[sorted.size / 2]
        }
    }

    private fun phaseJson(phases: PhaseMetrics): String =
        listOf(
            "\"get_stack_trace_ns\":${phases.getStackTraceNanos}",
            "\"raw_key_ns\":${phases.rawKeyNanos}",
            "\"raw_hash_ns\":${phases.rawHashNanos}",
            "\"raw_equality_ns\":${phases.rawEqualityNanos}",
            "\"generation_validation_ns\":${phases.generationValidationNanos}",
            "\"guard_validation_ns\":${phases.guardValidationNanos}",
            "\"canonical_cache_lock_wait_ns\":${phases.canonicalCacheLockWaitNanos}",
            "\"logical_metadata_ns\":${phases.logicalMetadataNanos}",
            "\"marker_set_ns\":${phases.markerSetNanos}",
            "\"marker_clear_ns\":${phases.markerClearNanos}",
            "\"marker_admission_clear_ns\":${phases.markerAdmissionClearNanos}",
            "\"marker_exact_set_ns\":${phases.markerExactSetNanos}",
            "\"marker_exit_clear_ns\":${phases.markerExitClearNanos}",
            "\"marker_admission_clear_count\":${phases.markerAdmissionClearCount}",
            "\"marker_exact_set_count\":${phases.markerExactSetCount}",
            "\"marker_exit_clear_count\":${phases.markerExitClearCount}",
            "\"ring_drain_ns\":${phases.ringDrainNanos}",
            "\"ring_scan_ns\":${phases.ringScanNanos}",
            "\"ring_payload_slice_ns\":${phases.ringPayloadSliceNanos}",
            "\"ring_decode_ns\":${phases.ringDecodeNanos}",
            "\"ring_append_ns\":${phases.ringAppendNanos}",
            "\"ring_consumer_publish_ns\":${phases.ringConsumerPublishNanos}",
            "\"ring_scanned_records\":${phases.ringScannedRecords}",
            "\"ring_ready_records\":${phases.ringReadyRecords}",
            "\"ring_reader_initialization_ns\":${phases.ringReaderInitializationNanos}",
            "\"collector_join_ns\":${phases.collectorJoinNanos}",
            "\"allocation_measurement_available\":${phases.allocationMeasurementAvailable}",
            "\"ring_drain_allocated_bytes\":${phases.ringDrainAllocatedBytes}",
            "\"collector_join_allocated_bytes\":${phases.collectorJoinAllocatedBytes}",
            "\"observation_conversion_allocated_bytes\":${phases.observationConversionAllocatedBytes}",
            "\"collector_resolution_allocated_bytes\":${phases.collectorResolutionAllocatedBytes}",
            "\"resolved_evidence_allocated_bytes\":${phases.resolvedEvidenceAllocatedBytes}",
            "\"joiner_enqueue_allocated_bytes\":${phases.joinerEnqueueAllocatedBytes}",
            "\"joiner_take_ready_allocated_bytes\":${phases.joinerTakeReadyAllocatedBytes}",
            "\"unique_edge_gate_allocated_bytes\":${phases.uniqueEdgeGateAllocatedBytes}",
            "\"unique_edge_suppression_diagnostic_allocated_bytes\":${phases.uniqueEdgeSuppressionDiagnosticAllocatedBytes}",
            "\"unique_edge_candidate_ownership_allocated_bytes\":${phases.uniqueEdgeCandidateOwnershipAllocatedBytes}",
            "\"unique_edge_candidate_diagnostic_allocated_bytes\":${phases.uniqueEdgeCandidateDiagnosticAllocatedBytes}",
            "\"batch_freeze_allocated_bytes\":${phases.batchFreezeAllocatedBytes}",
            "\"collector_polls\":${phases.collectorPolls}",
            "\"collector_loop_active_polls\":${phases.collectorLoopActivePolls}",
            "\"collector_loop_idle_polls\":${phases.collectorLoopIdlePolls}",
            "\"collector_loop_parks\":${phases.collectorLoopParks}",
            "\"collector_loop_parked_ns\":${phases.collectorLoopParkedNanos}",
            "\"collector_loop_max_consecutive_idle_polls\":${phases.collectorLoopMaxConsecutiveIdlePolls}",
            "\"collector_readiness_waits\":${phases.collectorReadinessWaits}",
            "\"collector_readiness_wakeups\":${phases.collectorReadinessWakeups}",
            "\"collector_readiness_timeouts\":${phases.collectorReadinessTimeouts}",
            "\"collector_readiness_spurious_wakeups\":${phases.collectorReadinessSpuriousWakeups}",
            "\"collector_readiness_waited_ns\":${phases.collectorReadinessWaitedNanos}",
            "\"collector_receipt_samples\":${phases.collectorReceiptSamples}",
            "\"collector_receipt_min_ns\":${phases.collectorReceiptMinNanos}",
            "\"collector_receipt_mean_ns\":${phases.collectorReceiptMeanNanos}",
            "\"collector_receipt_max_ns\":${phases.collectorReceiptMaxNanos}",
            "\"collector_receipt_p50_upper_ns\":${phases.collectorReceiptP50UpperNanos}",
            "\"collector_receipt_p95_upper_ns\":${phases.collectorReceiptP95UpperNanos}",
            "\"collector_receipt_p99_upper_ns\":${phases.collectorReceiptP99UpperNanos}",
            "\"ring_records_copied\":${phases.ringRecordsCopied}",
            "\"ring_bytes_copied\":${phases.ringBytesCopied}",
            "\"ring_busy_records\":${phases.ringBusyRecords}",
            "\"ring_discarded_records\":${phases.ringDiscardedRecords}",
            "\"ring_scan_passes\":${phases.ringScanPasses}",
            "\"ring_batches\":${phases.ringBatches}",
            "\"ring_max_batch_records\":${phases.ringMaxBatchRecords}",
            "\"serialization_ns\":${phases.serializationNanos}",
            "\"publication_bytes\":${phases.publicationBytes}",
            "\"publication_latency_ns\":${phases.publicationLatencyNanos}",
            "\"lock_wait_ns\":${phases.lockWaitNanos}",
            "\"peak_retained_bytes\":${phases.peakRetainedBytes}",
            "\"native_retained_bytes\":${phases.nativeRetainedBytes}",
            "\"native_retention_exhaustions\":${phases.nativeRetentionExhaustions}",
            "\"publication_count\":${phases.publicationCount}",
            "\"coalesced_publications\":${phases.coalescedPublications}",
            "\"failed_publications\":${phases.failedPublications}",
            "\"cache_hits\":${phases.cacheHits}",
            "\"cache_misses\":${phases.cacheMisses}",
        ).joinToString(",", prefix = "{", postfix = "}")

    internal fun phaseMetrics(output: String): PhaseMetrics =
        PhaseMetrics(
            metricOrZero(output, "get_stack_trace_ns"),
            metricOrZero(output, "raw_key_ns"),
            metricOrZero(output, "raw_hash_ns"),
            metricOrZero(output, "raw_equality_ns"),
            metricOrZero(output, "generation_validation_ns"),
            metricOrZero(output, "guard_validation_ns"),
            metricOrZero(output, "canonical_cache_lock_wait_ns"),
            metricOrZero(output, "logical_metadata_ns"),
            metricOrZero(output, "marker_set_ns"),
            metricOrZero(output, "marker_clear_ns"),
            metricOrZero(output, "marker_admission_clear_ns"),
            metricOrZero(output, "marker_exact_set_ns"),
            metricOrZero(output, "marker_exit_clear_ns"),
            metricOrZero(output, "marker_admission_clear_count"),
            metricOrZero(output, "marker_exact_set_count"),
            metricOrZero(output, "marker_exit_clear_count"),
            metricOrZero(output, "ring_drain_ns"),
            metricOrZero(output, "ring_scan_ns"),
            metricOrZero(output, "ring_payload_slice_ns"),
            metricOrZero(output, "ring_decode_ns"),
            metricOrZero(output, "ring_append_ns"),
            metricOrZero(output, "ring_consumer_publish_ns"),
            metricOrZero(output, "ring_scanned_records"),
            metricOrZero(output, "ring_ready_records"),
            metricOrZero(output, "ring_reader_initialization_ns"),
            metricOrZero(output, "collector_join_ns"),
            metricOrZero(output, "allocation_measurement_available"),
            metricOrZero(output, "ring_drain_allocated_bytes"),
            metricOrZero(output, "collector_join_allocated_bytes"),
            metricOrZero(output, "observation_conversion_allocated_bytes"),
            metricOrZero(output, "collector_resolution_allocated_bytes"),
            metricOrZero(output, "resolved_evidence_allocated_bytes"),
            metricOrZero(output, "joiner_enqueue_allocated_bytes"),
            metricOrZero(output, "joiner_take_ready_allocated_bytes"),
            metricOrZero(output, "unique_edge_gate_allocated_bytes"),
            metricOrZero(output, "unique_edge_suppression_diagnostic_allocated_bytes"),
            metricOrZero(output, "unique_edge_candidate_ownership_allocated_bytes"),
            metricOrZero(output, "unique_edge_candidate_diagnostic_allocated_bytes"),
            metricOrZero(output, "batch_freeze_allocated_bytes"),
            metricOrZero(output, "collector_polls"),
            metricOrZero(output, "collector_loop_active_polls"),
            metricOrZero(output, "collector_loop_idle_polls"),
            metricOrZero(output, "collector_loop_parks"),
            metricOrZero(output, "collector_loop_parked_ns"),
            metricOrZero(output, "collector_loop_max_consecutive_idle_polls"),
            metricOrZero(output, "collector_readiness_waits"),
            metricOrZero(output, "collector_readiness_wakeups"),
            metricOrZero(output, "collector_readiness_timeouts"),
            metricOrZero(output, "collector_readiness_spurious_wakeups"),
            metricOrZero(output, "collector_readiness_waited_ns"),
            metricOrZero(output, "collector_receipt_samples"),
            metricOrZero(output, "collector_receipt_min_ns"),
            metricOrZero(output, "collector_receipt_mean_ns"),
            metricOrZero(output, "collector_receipt_max_ns"),
            metricOrZero(output, "collector_receipt_p50_upper_ns"),
            metricOrZero(output, "collector_receipt_p95_upper_ns"),
            metricOrZero(output, "collector_receipt_p99_upper_ns"),
            metricOrZero(output, "ring_records_copied"),
            metricOrZero(output, "ring_bytes_copied"),
            metricOrZero(output, "ring_busy_records"),
            metricOrZero(output, "ring_discarded_records"),
            metricOrZero(output, "ring_scan_passes"),
            metricOrZero(output, "ring_batches"),
            metricOrZero(output, "ring_max_batch_records"),
            metricOrZero(output, "serialization_ns"),
            metricOrZero(output, "publication_bytes"),
            metricOrZero(output, "publication_latency_ns"),
            metricOrZero(output, "lock_wait_ns"),
            metricOrZero(output, "peak_retained_bytes"),
            metricOrZero(output, "native_retained_bytes"),
            metricOrZero(output, "native_retention_exhaustions"),
            metricOrZero(output, "publication_count"),
            metricOrZero(output, "coalesced_publications"),
            metricOrZero(output, "failed_publications"),
            metricOrZero(output, "cache_hits"),
            metricOrZero(output, "cache_misses"),
        )

    private fun userNotifPhaseJson(phases: UserNotifPhaseMetrics): String =
        listOf(
            "\"timed_operations\":${phases.timedOperations}",
            "\"operation_ns\":${phases.operationNanos}",
            "\"blocked_before_listener_ns\":${phases.blockedBeforeListenerNanos}",
            "\"listener_to_ack_ns\":${phases.listenerToAckNanos}",
            "\"stack_capture_ns\":${phases.stackCaptureNanos}",
            "\"queue_ns\":${phases.queueNanos}",
            "\"ack_to_return_ns\":${phases.ackToReturnNanos}",
        ).joinToString(",", prefix = "{", postfix = "}")

    private fun captureYieldJson(metrics: CaptureYieldMetrics): String =
        listOf(
            "\"records\":${metrics.records}",
            "\"captured\":${metrics.captured}",
            "\"capture_failed\":${metrics.captureFailed}",
            "\"admission_failed\":${metrics.admissionFailed}",
            "\"excluded\":${metrics.excluded}",
            "\"exact_joined\":${metrics.exactJoined}",
            "\"missing_definition\":${metrics.missingDefinition}",
            "\"outcome_mismatch\":${metrics.outcomeMismatch}",
            "\"unrecorded_definitions\":${metrics.unrecordedDefinitions}",
            "\"bindings\":${metrics.bindings.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (binding, yield) ->
                "\"$binding\":{\"records\":${yield.records},\"exact_joined\":${yield.exactJoined}," +
                    "\"missing_definition\":${yield.missingDefinition},\"outcome_mismatch\":${yield.outcomeMismatch}," +
                    "\"resolved_invocations\":${yield.resolvedInvocations},\"resolved_syscalls\":${yield.resolvedSyscalls}," +
                    "\"captured_without_resolved_syscall\":${yield.capturedWithoutResolvedSyscall}}"
            }}",
        ).joinToString(",", prefix = "{", postfix = "}")

    private fun captureEfficiencyJson(metrics: CaptureEfficiencyMetrics): String =
        listOf(
            "\"valid\":${metrics.valid}",
            "\"executed_operations\":${metrics.executedOperations}",
            "\"exact_captures\":${metrics.exactCaptures}",
            "\"resolved_syscalls\":${metrics.resolvedSyscalls}",
            "\"productive_captures\":${metrics.productiveCaptures}",
            "\"get_stack_trace_ns\":${metrics.getStackTraceNanos}",
        ).joinToString(",", prefix = "{", postfix = "}")

    internal fun captureEfficiency(
        executedOperations: Long,
        exactCaptures: Long,
        resolvedSyscalls: Long,
        productiveCaptures: Long,
        getStackTraceNanos: Long,
        fullStream: Boolean,
        exactJoinComplete: Boolean,
        evidenceComplete: Boolean,
        observedLosses: Long,
        attributionFailures: Long,
    ): CaptureEfficiencyMetrics =
        CaptureEfficiencyMetrics(
            executedOperations,
            exactCaptures,
            resolvedSyscalls,
            productiveCaptures,
            getStackTraceNanos,
            fullStream &&
                exactJoinComplete &&
                evidenceComplete &&
                observedLosses == 0L &&
                attributionFailures == 0L &&
                executedOperations > 0L &&
                exactCaptures > 0L,
        )

    internal fun captureYieldBindings(output: String): Map<Int, CaptureYieldBindingMetrics> {
        val encoded = CAPTURE_YIELD_BINDINGS_PATTERN
            .find(output)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        if (encoded.isEmpty()) return emptyMap()
        return encoded.split(",").associate { entry ->
            val values = entry.split(":")
            require(values.size == 8) { "invalid capture-yield binding: $entry" }
            values[0].toInt() to CaptureYieldBindingMetrics(
                values[1].toLong(),
                values[2].toLong(),
                values[3].toLong(),
                values[4].toLong(),
                values[5].toLong(),
                values[6].toLong(),
                values[7].toLong(),
            )
        }
    }

    internal fun admittedCapturesJoined(
        captured: Long,
        captureFailed: Long,
        admissionFailed: Long,
        exactJoined: Long,
        outcomeMismatch: Long,
        unrecordedDefinitions: Long,
    ): Boolean =
        captured == exactJoined &&
            captureFailed == 0L &&
            admissionFailed == 0L &&
            outcomeMismatch == 0L &&
            unrecordedDefinitions == 0L

    internal fun captureProfilePairExclusionReasons(
        fullWorkloadCorrect: Boolean,
        fullEvidenceComplete: Boolean,
        fullCaptureEfficiencyValid: Boolean,
        fullSemanticStackComplete: Boolean,
        narrowedWorkloadCorrect: Boolean,
        narrowedEvidenceComplete: Boolean,
        narrowedCaptureEfficiencyValid: Boolean,
        narrowedSemanticStackComplete: Boolean,
        semanticStacksEquivalent: Boolean,
    ): List<String> =
        buildList {
        if (!fullWorkloadCorrect) add("full_workload_correctness")
        if (!fullEvidenceComplete) add("full_evidence_integrity")
        if (!fullCaptureEfficiencyValid) add("full_capture_efficiency")
        if (!fullSemanticStackComplete) add("full_logical_stack_content")
        if (!narrowedWorkloadCorrect) add("narrowed_workload_correctness")
        if (!narrowedEvidenceComplete) add("narrowed_evidence_integrity")
        if (!narrowedCaptureEfficiencyValid) add("narrowed_capture_efficiency")
        if (!narrowedSemanticStackComplete) add("narrowed_logical_stack_content")
        if (!semanticStacksEquivalent) add("logical_stack_content_mismatch")
    }

    private fun terminal(
        success: Boolean,
        message: String,
    ): String = "{\"kind\":\"terminal\",\"timestamp\":\"${Instant.now()}\",\"success\":$success,\"message\":\"${escape(message)}\"}"

    private fun awaitProcess(
        process: Process,
        timeoutSeconds: Long,
    ): ProcessTelemetry {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        var peakRssBytes = 0L
        var cpuNanos: Long? = null
        while (process.isAlive && System.nanoTime() < deadline) {
            peakRssBytes = maxOf(peakRssBytes, readPeakRssBytes(process.pid()))
            process.toHandle().info().totalCpuDuration().ifPresent { duration ->
                cpuNanos = maxOf(cpuNanos ?: 0L, duration.toNanos())
            }
            process.waitFor(100, TimeUnit.MILLISECONDS)
        }
        peakRssBytes = maxOf(peakRssBytes, readPeakRssBytes(process.pid()))
        process.toHandle().info().totalCpuDuration().ifPresent { duration ->
            cpuNanos = maxOf(cpuNanos ?: 0L, duration.toNanos())
        }
        return ProcessTelemetry(!process.isAlive, process.pid(), cpuNanos, peakRssBytes)
    }

    private fun readPeakRssBytes(pid: Long): Long =
        runCatching {
            Files
                .readAllLines(Path.of("/proc/$pid/status"))
                .first { it.startsWith("VmHWM:") }
                .trim()
                .split(Regex("\\s+"))[1]
                .toLong() *
                1024L
        }.getOrDefault(0L)

    private fun pairedSummary(
        forks: List<ForkResult>,
        config: Config,
    ): String {
        val scope = config.scope
        if (scope == Scope.TIER_E_RING_ARGUMENT_LIST || scope == Scope.TIER_E_RING_ARGUMENT_VIEW || scope == Scope.TIER_E_TASK_IDENTITY || scope == Scope.TIER_E_KNOWN_JOIN) {
            val viewControl = scope == Scope.TIER_E_RING_ARGUMENT_VIEW
            val identityControl = scope == Scope.TIER_E_TASK_IDENTITY
            val knownJoinControl = scope == Scope.TIER_E_KNOWN_JOIN
            val immutable = forks
                .filter {
                    it.variant.mode.tierE &&
                        if (viewControl) {
                            ringArgumentViewFor(it.variant) == RingArgumentView.LAZY
                        } else if (identityControl) {
                            taskIdentityFor(it.variant) == TaskIdentity.PER_EVENT
                        } else if (knownJoinControl) {
                            knownJoinFor(it.variant) == KnownJoin.QUEUED_CONTROL
                        } else {
                            ringArgumentListFor(it.variant) == RingArgumentList.IMMUTABLE_FACTORY
                        }
                }.associateBy { it.fork }
            val legacy = forks
                .filter {
                    it.variant.mode.tierE &&
                        if (viewControl) {
                            ringArgumentViewFor(it.variant) == RingArgumentView.EAGER_CONTROL
                        } else if (identityControl) {
                            taskIdentityFor(it.variant) == TaskIdentity.REUSE_CONSECUTIVE
                        } else if (knownJoinControl) {
                            knownJoinFor(it.variant) == KnownJoin.DIRECT_KNOWN
                        } else {
                            ringArgumentListFor(it.variant) == RingArgumentList.LAMBDA_COPY
                        }
                }.associateBy { it.fork }
            val pairs = immutable.keys.intersect(legacy.keys).sorted().map { fork ->
                fork to (immutable.getValue(fork) to legacy.getValue(fork))
            }
            val exclusions = pairs
                .mapNotNull { (fork, results) ->
                directPairExclusionReasons(results.first, results.second).takeIf { it.isNotEmpty() }?.let { fork to it }
            }.toMap()
            val deltas = pairs.filter { (fork, _) -> fork !in exclusions }.map { (_, results) ->
                median(results.second.batches.map { it.workloadNanos }) - median(results.first.batches.map { it.workloadNanos })
            }
            val interval = deltas.takeIf { it.isNotEmpty() }?.let(::bootstrapMedianInterval)
            val exclusionJson = exclusions.entries.joinToString(",", prefix = "{", postfix = "}") { (fork, reasons) ->
                "\"$fork\":[${reasons.joinToString(",") { reason -> "\"${escape(reason)}\"" }}]"
            }
            val effect = when {
                viewControl -> "paired_ring_argument_view_effect"
                identityControl -> "paired_task_identity_effect"
                knownJoinControl -> "paired_known_join_effect"
                else -> "paired_ring_argument_list_effect"
            }
            val delta = when {
                viewControl -> "eager_minus_lazy_median_ns"
                identityControl -> "reuse_consecutive_minus_per_event_median_ns"
                knownJoinControl -> "direct_known_minus_queued_median_ns"
                else -> "lambda_copy_minus_immutable_factory_median_ns"
            }
            return "\"$effect\":{\"$delta\":" +
                "${deltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                "\"paired_bootstrap_95_ci_low_ns\":${interval?.first ?: "null"}," +
                "\"paired_bootstrap_95_ci_high_ns\":${interval?.second ?: "null"}," +
                "\"pairs\":${deltas.size},\"excluded_pairs\":$exclusionJson},"
        }
        if (scope == Scope.FULL || scope == Scope.TIER_E_COMPARABLE) {
            val baselines = forks.filter { it.variant.mode == Mode.BASELINE }.associateBy { it.fork }
            val effects = forks
                .filter { it.variant.mode != Mode.BASELINE }
                .groupBy { it.variant.mode }
                .entries
                .joinToString(",") { (mode, candidates) ->
                    val candidateByFork = candidates.associateBy { it.fork }
                    val pairs = baselines.keys.intersect(candidateByFork.keys).sorted().map { fork ->
                        fork to (baselines.getValue(fork) to candidateByFork.getValue(fork))
                    }
                    val exclusions = pairs
                        .mapNotNull { (fork, results) ->
                        fullMatrixPairExclusionReasons(
                            baselineWorkloadCorrect = results.first.verdict.workloadCorrectness == TierEBenchmarkContract.CorrectnessState.PASS,
                            candidateWorkloadCorrect = results.second.verdict.workloadCorrectness == TierEBenchmarkContract.CorrectnessState.PASS,
                            candidateEvidenceComplete = results.second.verdict.wholeSessionEvidence == TierEBenchmarkContract.EvidenceState.COMPLETE,
                        ).takeIf { it.isNotEmpty() }?.let { fork to it }
                    }.toMap()
                    val values = pairs
                        .filter { (fork, _) -> fork !in exclusions }
                        .map { (_, results) ->
                            median(results.first.batches.map { it.workloadNanos }) to
                                median(results.second.batches.map { it.workloadNanos })
                        }
                    val exclusionJson = exclusions.entries.joinToString(",", prefix = "{", postfix = "}") { (fork, reasons) ->
                        "\"$fork\":[${reasons.joinToString(",") { reason -> "\"${escape(reason)}\"" }}]"
                    }
                    val interval = values.takeIf { it.isNotEmpty() }?.let(::pairedMedianInterval)
                    "\"${mode.jsonName}\":{\"candidate_minus_baseline_median_ns\":${interval?.medianDeltaNanos ?: "null"}," +
                        "\"paired_bootstrap_95_ci_low_ns\":${interval?.lowNanos ?: "null"}," +
                        "\"paired_bootstrap_95_ci_high_ns\":${interval?.highNanos ?: "null"}," +
                        "\"pairs\":${values.size},\"excluded_pairs\":$exclusionJson}"
                }
            val directEffects = DIRECT_COMPARISONS.joinToString(",") { comparison ->
                val leftByFork = forks
                    .filter { it.variant.mode == comparison.left }
                    .associateBy { it.fork }
                val rightByFork = forks
                    .filter { it.variant.mode == comparison.right }
                    .associateBy { it.fork }
                val pairs = leftByFork.keys.intersect(rightByFork.keys).sorted().map { fork ->
                    fork to (leftByFork.getValue(fork) to rightByFork.getValue(fork))
                }
                val exclusions = pairs
                    .mapNotNull { (fork, results) ->
                    directPairExclusionReasons(results.first, results.second)
                        .takeIf { it.isNotEmpty() }
                        ?.let { fork to it }
                }.toMap()
                val leftDurations = mutableMapOf<Int, Long>()
                val rightDurations = mutableMapOf<Int, Long>()
                pairs.filter { (fork, _) -> fork !in exclusions }.forEach { (fork, results) ->
                    leftDurations[fork] = median(results.first.batches.map { it.workloadNanos })
                    rightDurations[fork] = median(results.second.batches.map { it.workloadNanos })
                }
                val interval = if (leftDurations.isEmpty()) null else directPairedEffect(leftDurations, rightDurations)
                val exclusionJson = exclusions.entries.joinToString(",", prefix = "{", postfix = "}") { (fork, reasons) ->
                    "\"$fork\":[${reasons.joinToString(",") { reason -> "\"${escape(reason)}\"" }}]"
                }
                "\"${comparison.name}\":{\"right_minus_left_median_ns\":${interval?.medianDeltaNanos ?: "null"}," +
                    "\"paired_bootstrap_95_ci_low_ns\":${interval?.lowNanos ?: "null"}," +
                    "\"paired_bootstrap_95_ci_high_ns\":${interval?.highNanos ?: "null"}," +
                    "\"pairs\":${leftDurations.size},\"excluded_pairs\":$exclusionJson}"
            }
            return "\"paired_full_matrix_effects\":{$effects},\"paired_direct_effects\":{$directEffects},"
        }
        if (scope == Scope.TIER_E_PUBLICATION || scope == Scope.TIER_E_DICTIONARY_PUBLICATION) {
            val effects = forks.filter { it.variant.mode.tierE }.groupBy { it.variant.mode }.entries.joinToString(",") { (mode, values) ->
                val terminal = values.filter { it.variant.publication == Publication.TERMINAL }.associateBy { it.fork }
                val live = values.filter { it.variant.publication == Publication.LIVE }.associateBy { it.fork }
                val pairs = terminal.keys.intersect(live.keys).sorted().map { fork ->
                    fork to (terminal.getValue(fork) to live.getValue(fork))
                }
                val exclusions = pairs
                    .mapNotNull { (fork, results) ->
                        directPairExclusionReasons(results.first, results.second).takeIf { it.isNotEmpty() }?.let { fork to it }
                    }.toMap()
                val deltas = pairs.filter { (fork, _) -> fork !in exclusions }.map { (_, results) ->
                    median(results.second.batches.map { it.workloadNanos }) - median(results.first.batches.map { it.workloadNanos })
                }
                val interval = deltas.takeIf { it.isNotEmpty() }?.let(::bootstrapMedianInterval)
                val exclusionJson = exclusions.entries.joinToString(",", prefix = "{", postfix = "}") { (fork, reasons) ->
                    "\"$fork\":[${reasons.joinToString(",") { reason -> "\"${escape(reason)}\"" }}]"
                }
                "\"${mode.jsonName}\":{\"live_minus_terminal_median_ns\":${deltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                    "\"paired_bootstrap_95_ci_low_ns\":${interval?.first ?: "null"},\"paired_bootstrap_95_ci_high_ns\":${interval?.second ?: "null"}," +
                    "\"pairs\":${deltas.size},\"excluded_pairs\":$exclusionJson}"
            }
            return "\"paired_publication_effects\":{$effects},"
        }
        if (scope == Scope.TIER_E_RING_DECODE) {
            val effects = forks.filter { it.variant.mode.tierE }.groupBy { it.variant.mode }.entries.joinToString(",") { (mode, values) ->
                val direct = values.filter { ringDecodeFor(it.variant) == RingDecode.DIRECT }.associateBy { it.fork }
                val legacy = values.filter { ringDecodeFor(it.variant) == RingDecode.LEGACY }.associateBy { it.fork }
                val pairs = direct.keys.intersect(legacy.keys).sorted().map { fork ->
                    fork to (direct.getValue(fork) to legacy.getValue(fork))
                }
                val exclusions = pairs
                    .mapNotNull { (fork, results) ->
                    directPairExclusionReasons(results.first, results.second).takeIf { it.isNotEmpty() }?.let { fork to it }
                }.toMap()
                val deltas = pairs.filter { (fork, _) -> fork !in exclusions }.map { (_, results) ->
                    median(results.second.batches.map { it.workloadNanos }) - median(results.first.batches.map { it.workloadNanos })
                }
                val admitted = pairs.filter { (fork, _) -> fork !in exclusions }.map { it.second }
                val drainDeltas = admitted.map { (directResult, legacyResult) ->
                    legacyResult.evidence.phases.ringDrainNanos - directResult.evidence.phases.ringDrainNanos
                }
                val allocationDeltas = admitted.mapNotNull { (directResult, legacyResult) ->
                    if (directResult.evidence.phases.allocationMeasurementAvailable == 0L ||
                        legacyResult.evidence.phases.allocationMeasurementAvailable == 0L
                    ) {
                        null
                    } else {
                        legacyResult.evidence.phases.ringDrainAllocatedBytes -
                            directResult.evidence.phases.ringDrainAllocatedBytes
                    }
                }
                val cpuDeltas = admitted.mapNotNull { (directResult, legacyResult) ->
                    val directCpu = directResult.cpuNanos ?: return@mapNotNull null
                    val legacyCpu = legacyResult.cpuNanos ?: return@mapNotNull null
                    legacyCpu - directCpu
                }
                val rssDeltas = admitted.map { (directResult, legacyResult) ->
                    legacyResult.peakRssBytes - directResult.peakRssBytes
                }
                val interval = deltas.takeIf { it.isNotEmpty() }?.let(::bootstrapMedianInterval)
                val drainInterval = drainDeltas.takeIf { it.isNotEmpty() }?.let(::bootstrapMedianInterval)
                val allocationInterval = allocationDeltas.takeIf { it.isNotEmpty() }?.let(::bootstrapMedianInterval)
                val cpuInterval = cpuDeltas.takeIf { it.isNotEmpty() }?.let(::bootstrapMedianInterval)
                val rssInterval = rssDeltas.takeIf { it.isNotEmpty() }?.let(::bootstrapMedianInterval)
                val exclusionJson = exclusions.entries.joinToString(",", prefix = "{", postfix = "}") { (fork, reasons) ->
                    "\"$fork\":[${reasons.joinToString(",") { reason -> "\"${escape(reason)}\"" }}]"
                }
                "\"${mode.jsonName}\":{\"legacy_minus_direct_median_ns\":${deltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                    "\"paired_bootstrap_95_ci_low_ns\":${interval?.first ?: "null"},\"paired_bootstrap_95_ci_high_ns\":${interval?.second ?: "null"}," +
                    "\"legacy_minus_direct_drain_median_ns\":${drainDeltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                    "\"drain_95_ci_low_ns\":${drainInterval?.first ?: "null"},\"drain_95_ci_high_ns\":${drainInterval?.second ?: "null"}," +
                    "\"legacy_minus_direct_allocation_median_bytes\":${allocationDeltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                    "\"allocation_95_ci_low_bytes\":${allocationInterval?.first ?: "null"}," +
                    "\"allocation_95_ci_high_bytes\":${allocationInterval?.second ?: "null"}," +
                    "\"legacy_minus_direct_cpu_median_ns\":${cpuDeltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                    "\"cpu_95_ci_low_ns\":${cpuInterval?.first ?: "null"},\"cpu_95_ci_high_ns\":${cpuInterval?.second ?: "null"}," +
                    "\"legacy_minus_direct_peak_rss_median_bytes\":${rssDeltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                    "\"peak_rss_95_ci_low_bytes\":${rssInterval?.first ?: "null"}," +
                    "\"peak_rss_95_ci_high_bytes\":${rssInterval?.second ?: "null"}," +
                    "\"pairs\":${deltas.size},\"excluded_pairs\":$exclusionJson}"
            }
            return "\"paired_ring_decode_effects\":{$effects},"
        }
        if (scope == Scope.TIER_E_COLLECTOR_APPEND) {
            val effects = forks.filter { it.variant.mode.tierE }.groupBy { it.variant.mode }.entries.joinToString(",") { (mode, values) ->
                val temporary = values.filter { collectorResultFor(it.variant) == CollectorResult.TEMPORARY_LIST }.associateBy { it.fork }
                val append = values.filter { collectorResultFor(it.variant) == CollectorResult.APPEND_TARGET }.associateBy { it.fork }
                val pairs = temporary.keys.intersect(append.keys).sorted().map { fork ->
                    fork to (temporary.getValue(fork) to append.getValue(fork))
                }
                val exclusions = pairs
                    .mapNotNull { (fork, results) ->
                        directPairExclusionReasons(results.first, results.second).takeIf { it.isNotEmpty() }?.let { fork to it }
                    }.toMap()
                val deltas = pairs.filter { (fork, _) -> fork !in exclusions }.map { (_, results) ->
                    median(results.first.batches.map { it.workloadNanos }) - median(results.second.batches.map { it.workloadNanos })
                }
                val interval = deltas.takeIf { it.isNotEmpty() }?.let(::bootstrapMedianInterval)
                val exclusionJson = exclusions.entries.joinToString(",", prefix = "{", postfix = "}") { (fork, reasons) ->
                    "\"$fork\":[${reasons.joinToString(",") { reason -> "\"${escape(reason)}\"" }}]"
                }
                "\"${mode.jsonName}\":{\"temporary_list_minus_append_target_median_ns\":${deltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                    "\"paired_bootstrap_95_ci_low_ns\":${interval?.first ?: "null"},\"paired_bootstrap_95_ci_high_ns\":${interval?.second ?: "null"}," +
                    "\"pairs\":${deltas.size},\"excluded_pairs\":$exclusionJson}"
            }
            return "\"paired_collector_append_effects\":{$effects},"
        }
        if (scope == Scope.TIER_E_POLL_BACKOFF || scope == Scope.TIER_E_READINESS) {
            val effects = forks
                .filter { it.variant.mode.tierE }
                .groupBy { it.variant.mode }
                .entries
                .joinToString(",") { (mode, values) ->
                    val continuous = values
                        .filter { it.variant.pollBackoff == PollBackoff.NONE }
                        .associateBy { it.fork }
                    val policies = if (scope == Scope.TIER_E_READINESS) {
                        listOf(PollBackoff.READINESS)
                    } else {
                        PollBackoff.entries.filterNot { it == PollBackoff.READINESS }
                    }
                    val policyEffects = policies
                        .filterNot { it == PollBackoff.NONE }
                        .joinToString(",") { policy ->
                            val candidate = values
                                .filter { it.variant.pollBackoff == policy }
                                .associateBy { it.fork }
                            val pairs = continuous.keys.intersect(candidate.keys).sorted().map { fork ->
                                fork to (continuous.getValue(fork) to candidate.getValue(fork))
                            }
                            val exclusions = pairs
                                .mapNotNull { (fork, results) ->
                                    directPairExclusionReasons(results.first, results.second)
                                        .takeIf { it.isNotEmpty() }
                                        ?.let { fork to it }
                                }.toMap()
                            val admitted = pairs.filter { (fork, _) -> fork !in exclusions }.map { it.second }
                            val workloadDeltas = admitted.map { (control, candidateResult) ->
                                median(candidateResult.batches.map { it.workloadNanos }) -
                                    median(control.batches.map { it.workloadNanos })
                            }
                            val receiptDeltas = admitted.map { (control, candidateResult) ->
                                candidateResult.evidence.phases.collectorReceiptMeanNanos -
                                    control.evidence.phases.collectorReceiptMeanNanos
                            }
                            val receiptP99Deltas = admitted.map { (control, candidateResult) ->
                                candidateResult.evidence.phases.collectorReceiptP99UpperNanos -
                                    control.evidence.phases.collectorReceiptP99UpperNanos
                            }
                            val cpuDeltas = admitted.mapNotNull { (control, candidateResult) ->
                                val controlCpu = control.cpuNanos ?: return@mapNotNull null
                                val candidateCpu = candidateResult.cpuNanos ?: return@mapNotNull null
                                candidateCpu - controlCpu
                            }
                            val cpuImprovementBasisPoints = admitted.mapNotNull { (control, candidateResult) ->
                                val controlCpu = control.cpuNanos?.takeIf { it > 0 } ?: return@mapNotNull null
                                val candidateCpu = candidateResult.cpuNanos ?: return@mapNotNull null
                                ((controlCpu - candidateCpu) * 10_000L) / controlCpu
                            }
                            val workloadRegressionBasisPoints = admitted.map { (control, candidateResult) ->
                                val controlWorkload = median(control.batches.map { it.workloadNanos })
                                val candidateWorkload = median(candidateResult.batches.map { it.workloadNanos })
                                ((candidateWorkload - controlWorkload) * 10_000L) / controlWorkload
                            }
                            val parkedDeltas = admitted.map { (control, candidateResult) ->
                                candidateResult.evidence.phases.collectorLoopParkedNanos -
                                    control.evidence.phases.collectorLoopParkedNanos
                            }
                            val interval = workloadDeltas.takeIf { it.isNotEmpty() }?.let(::bootstrapMedianInterval)
                            val cpuInterval = cpuImprovementBasisPoints.takeIf { it.isNotEmpty() }?.let(::bootstrapMedianInterval)
                            val workloadRegressionInterval = workloadRegressionBasisPoints
                                .takeIf { it.isNotEmpty() }
                                ?.let(::bootstrapMedianInterval)
                            val receiptP99Interval = receiptP99Deltas.takeIf { it.isNotEmpty() }?.let(::bootstrapMedianInterval)
                            val receiptP99Allowance = admitted
                                .map { it.first.evidence.phases.collectorReceiptP99UpperNanos }
                                .takeIf { it.isNotEmpty() }
                                ?.let { maxOf(100_000L, median(it) / 10L) }
                                ?: 100_000L
                            val peakRetainedBytes = admitted.maxOfOrNull { (_, candidateResult) ->
                                maxOf(
                                    candidateResult.evidence.phases.peakRetainedBytes,
                                    candidateResult.evidence.phases.nativeRetainedBytes,
                                )
                            } ?: 0L
                            val missingPairs = (config.forks - pairs.size).coerceAtLeast(0)
                            val decision = TierEReadinessDecision.assess(
                                TierEReadinessDecision.Input(
                                    runLevel = config.runLevel,
                                    admittedPairs = admitted.size,
                                    excludedPairs = exclusions.size + missingPairs,
                                    cpuImprovementCiLowBasisPoints = cpuInterval?.first,
                                    workloadRegressionCiHighBasisPoints = workloadRegressionInterval?.second,
                                    receiptP99IncreaseCiHighNanos = receiptP99Interval?.second,
                                    receiptP99AllowanceNanos = receiptP99Allowance,
                                    peakRetainedBytes = peakRetainedBytes,
                                ),
                            )
                            val exclusionJson = exclusions.entries.joinToString(",", prefix = "{", postfix = "}") { (fork, reasons) ->
                                "\"$fork\":[${reasons.joinToString(",") { reason -> "\"${escape(reason)}\"" }}]"
                            }
                            "\"${policy.wireName}\":{\"candidate_minus_continuous_workload_median_ns\":" +
                                "${workloadDeltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                                "\"paired_bootstrap_95_ci_low_ns\":${interval?.first ?: "null"}," +
                                "\"paired_bootstrap_95_ci_high_ns\":${interval?.second ?: "null"}," +
                                "\"candidate_minus_continuous_receipt_mean_median_ns\":" +
                                "${receiptDeltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                                "\"candidate_minus_continuous_receipt_p99_median_ns\":" +
                                "${receiptP99Deltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                                "\"candidate_minus_continuous_cpu_median_ns\":${cpuDeltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                                "\"continuous_minus_candidate_cpu_median_basis_points\":" +
                                "${cpuImprovementBasisPoints.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                                "\"continuous_minus_candidate_cpu_ci_low_basis_points\":${cpuInterval?.first ?: "null"}," +
                                "\"continuous_minus_candidate_cpu_ci_high_basis_points\":${cpuInterval?.second ?: "null"}," +
                                "\"candidate_minus_continuous_workload_ci_high_basis_points\":" +
                                "${workloadRegressionInterval?.second ?: "null"}," +
                                "\"candidate_minus_continuous_receipt_p99_ci_high_ns\":${receiptP99Interval?.second ?: "null"}," +
                                "\"receipt_p99_allowance_ns\":$receiptP99Allowance," +
                                "\"candidate_peak_retained_bytes\":$peakRetainedBytes," +
                                "\"decision_status\":\"${decision.status.wireName}\"," +
                                "\"decision_failures\":[${decision.failures.joinToString(",") { "\"$it\"" }}]," +
                                "\"candidate_minus_continuous_parked_median_ns\":${parkedDeltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                                "\"pairs\":${admitted.size},\"excluded_pairs\":$exclusionJson}"
                        }
                    "\"${mode.jsonName}\":{$policyEffects}"
                }
            return if (scope == Scope.TIER_E_READINESS) {
                "\"paired_readiness_effects\":{$effects},"
            } else {
                "\"paired_poll_backoff_effects\":{$effects},"
            }
        }
        if (scope == Scope.TIER_E_MARKER_TRANSPORT || scope == Scope.TIER_E_MARKER_TRANSPORT_COMPARABLE) {
            val effects = forks.filter { it.variant.mode.tierE }.groupBy { it.variant.mode }.entries.joinToString(",") { (mode, values) ->
                val uprobe = values.filter { markerTransportFor(it.variant) == MarkerTransport.UPROBE }.associateBy { it.fork }
                val usdt = values.filter { markerTransportFor(it.variant) == MarkerTransport.USDT }.associateBy { it.fork }
                val pairs = uprobe.keys.intersect(usdt.keys).sorted().map { fork ->
                    fork to (uprobe.getValue(fork) to usdt.getValue(fork))
                }
                val exclusions = pairs
                    .mapNotNull { (fork, results) ->
                        directPairExclusionReasons(results.first, results.second).takeIf { it.isNotEmpty() }?.let { fork to it }
                    }.toMap()
                val deltas = pairs.filter { (fork, _) -> fork !in exclusions }.map { (_, results) ->
                    median(results.first.batches.map { it.workloadNanos }) - median(results.second.batches.map { it.workloadNanos })
                }
                val interval = deltas.takeIf { it.isNotEmpty() }?.let(::bootstrapMedianInterval)
                val exclusionJson = exclusions.entries.joinToString(",", prefix = "{", postfix = "}") { (fork, reasons) ->
                    "\"$fork\":[${reasons.joinToString(",") { reason -> "\"${escape(reason)}\"" }}]"
                }
                "\"${mode.jsonName}\":{\"uprobe_minus_usdt_median_ns\":${deltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                    "\"paired_bootstrap_95_ci_low_ns\":${interval?.first ?: "null"},\"paired_bootstrap_95_ci_high_ns\":${interval?.second ?: "null"}," +
                    "\"pairs\":${deltas.size},\"excluded_pairs\":$exclusionJson}"
            }
            return "\"paired_marker_transport_effects\":{$effects},"
        }
        if (scope == Scope.TIER_E_PRODUCTION_SELECTION) {
            val fullStream = forks.filter { it.variant.mode == Mode.FULL_STREAM }

            fun effect(
                name: String,
                left: MarkerTransport,
                right: MarkerTransport,
            ): String {
                val leftByFork = fullStream.filter { markerTransportFor(it.variant) == left }.associateBy { it.fork }
                val rightByFork = fullStream.filter { markerTransportFor(it.variant) == right }.associateBy { it.fork }
                val pairs = leftByFork.keys.intersect(rightByFork.keys).sorted().map { fork ->
                    fork to (leftByFork.getValue(fork) to rightByFork.getValue(fork))
                }
                val exclusions = pairs
                    .mapNotNull { (fork, results) ->
                    directPairExclusionReasons(results.first, results.second).takeIf { it.isNotEmpty() }?.let { fork to it }
                }.toMap()
                val deltas = pairs.filter { (fork, _) -> fork !in exclusions }.map { (_, results) ->
                    median(results.first.batches.map { it.workloadNanos }) - median(results.second.batches.map { it.workloadNanos })
                }
                val interval = deltas.takeIf { it.isNotEmpty() }?.let(::bootstrapMedianInterval)
                val excluded = exclusions.entries.joinToString(",", prefix = "{", postfix = "}") { (fork, reasons) ->
                    "\"$fork\":[${reasons.joinToString(",") { reason -> "\"${escape(reason)}\"" }}]"
                }
                return "\"$name\":{\"left_minus_right_median_ns\":${deltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                    "\"paired_bootstrap_95_ci_low_ns\":${interval?.first ?: "null"},\"paired_bootstrap_95_ci_high_ns\":${interval?.second ?: "null"}," +
                    "\"pairs\":${deltas.size},\"excluded_pairs\":$excluded}"
            }
            return "\"paired_production_selection_effects\":{${effect("auto_minus_usdt", MarkerTransport.AUTO, MarkerTransport.USDT)}," +
                effect("uprobe_minus_usdt", MarkerTransport.UPROBE, MarkerTransport.USDT) +
                "},"
        }
        if (scope == Scope.TIER_E_CAPTURE_PROFILE) {
            val values = forks.filter { it.variant.mode == Mode.FULL_STREAM }
            val full = values.filter { captureProfileFor(it.variant) == TierECaptureProfile.Full }.associateBy { it.fork }
            val narrowed = values.filter { captureProfileFor(it.variant).wireName == "jdk25-unix-openat0" }.associateBy { it.fork }
            val pairs = full.keys.intersect(narrowed.keys).sorted().map { fork ->
                fork to (full.getValue(fork) to narrowed.getValue(fork))
            }
            val exclusions = pairs
                .mapNotNull { (fork, results) ->
                val reasons = captureProfilePairExclusionReasons(
                    fullWorkloadCorrect = results.first.verdict.workloadCorrectness == TierEBenchmarkContract.CorrectnessState.PASS,
                    fullEvidenceComplete = results.first.verdict.wholeSessionEvidence == TierEBenchmarkContract.EvidenceState.COMPLETE,
                    // Capture-yield records are bounded diagnostics and can truncate before a
                    // long exact session ends. Session evidence and the operation oracle are the
                    // admission proof; do not turn diagnostic truncation into a false rejection.
                    fullCaptureEfficiencyValid = true,
                    fullSemanticStackComplete = results.first.evidence.profileSemanticStackComplete,
                    narrowedWorkloadCorrect = results.second.verdict.workloadCorrectness == TierEBenchmarkContract.CorrectnessState.PASS,
                    narrowedEvidenceComplete = results.second.verdict.wholeSessionEvidence == TierEBenchmarkContract.EvidenceState.COMPLETE,
                    narrowedCaptureEfficiencyValid = true,
                    narrowedSemanticStackComplete = results.second.evidence.profileSemanticStackComplete,
                    semanticStacksEquivalent = results.first.evidence.profileSemanticStackDigest != null &&
                        results.first.evidence.profileSemanticStackDigest == results.second.evidence.profileSemanticStackDigest,
                )
                reasons.takeIf { it.isNotEmpty() }?.let { fork to it }
            }.toMap()
            val deltas = pairs
                .filter { (fork, _) -> fork !in exclusions }
                .map { (_, results) ->
                    median(results.first.batches.map { it.workloadNanos }) -
                        median(results.second.batches.map { it.workloadNanos })
                }
            val exclusionJson = exclusions.entries.joinToString(",", prefix = "{", postfix = "}") { (fork, reasons) ->
                "\"$fork\":[${reasons.joinToString(",") { reason -> "\"${escape(reason)}\"" }}]"
            }
            if (deltas.isEmpty()) {
                return "\"paired_capture_profile_effects\":{\"dictionary_full_stream\":{\"full_minus_narrowed_median_ns\":null," +
                    "\"paired_bootstrap_95_ci_low_ns\":null,\"paired_bootstrap_95_ci_high_ns\":null,\"pairs\":0," +
                    "\"excluded_pairs\":$exclusionJson}},"
            }
            val interval = bootstrapMedianInterval(deltas)
            return "\"paired_capture_profile_effects\":{\"dictionary_full_stream\":{\"full_minus_narrowed_median_ns\":${median(deltas)}," +
                "\"paired_bootstrap_95_ci_low_ns\":${interval.first},\"paired_bootstrap_95_ci_high_ns\":${interval.second},\"pairs\":${deltas.size}," +
                "\"excluded_pairs\":$exclusionJson}},"
        }
        if (scope == Scope.TIER_E_CAPTURE_TIMING) {
            val values = forks.filter { it.variant.mode == Mode.FULL_STREAM }
            val enabled = values.filter { nativePhaseTimingFor(it.variant) == NativePhaseTiming.ENABLED }.associateBy { it.fork }
            val disabled = values.filter { nativePhaseTimingFor(it.variant) == NativePhaseTiming.DISABLED }.associateBy { it.fork }
            val pairs = enabled.keys.intersect(disabled.keys).sorted().map { fork ->
                fork to (enabled.getValue(fork) to disabled.getValue(fork))
            }
            val exclusions = pairs
                .mapNotNull { (fork, results) ->
                directPairExclusionReasons(results.first, results.second)
                    .takeIf { it.isNotEmpty() }
                    ?.let { fork to it }
            }.toMap()
            val admitted = pairs.filter { (fork, _) -> fork !in exclusions }.map { it.second }
            val workloadDeltas = admitted.map { (timed, untimed) ->
                median(timed.batches.map(Batch::workloadNanos)) - median(untimed.batches.map(Batch::workloadNanos))
            }
            val cpuDeltas = admitted.mapNotNull { (timed, untimed) ->
                val timedCpu = timed.cpuNanos ?: return@mapNotNull null
                val untimedCpu = untimed.cpuNanos ?: return@mapNotNull null
                timedCpu - untimedCpu
            }
            val workloadInterval = workloadDeltas.takeIf { it.isNotEmpty() }?.let(::bootstrapMedianInterval)
            val cpuInterval = cpuDeltas.takeIf { it.isNotEmpty() }?.let(::bootstrapMedianInterval)
            val exclusionJson = exclusions.entries.joinToString(",", prefix = "{", postfix = "}") { (fork, reasons) ->
                "\"$fork\":[${reasons.joinToString(",") { reason -> "\"${escape(reason)}\"" }}]"
            }
            return "\"paired_native_timing_perturbation\":{" +
                "\"enabled_minus_disabled_workload_median_ns\":${workloadDeltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                "\"workload_ci_low_ns\":${workloadInterval?.first ?: "null"}," +
                "\"workload_ci_high_ns\":${workloadInterval?.second ?: "null"}," +
                "\"enabled_minus_disabled_cpu_median_ns\":${cpuDeltas.takeIf { it.isNotEmpty() }?.let(::median) ?: "null"}," +
                "\"cpu_ci_low_ns\":${cpuInterval?.first ?: "null"}," +
                "\"cpu_ci_high_ns\":${cpuInterval?.second ?: "null"}," +
                "\"pairs\":${admitted.size},\"excluded_pairs\":$exclusionJson},"
        }
        if (scope != Scope.TIER_E_CACHE_PAIRED) return ""
        val effects = forks.filter { it.variant.mode.tierE }.groupBy { it.variant.mode }.entries.joinToString(",") { (mode, values) ->
            val enabled = values.filter { it.variant.cacheMode == "enabled" }.associateBy { it.fork }
            val disabled = values.filter { it.variant.cacheMode == "disabled" }.associateBy { it.fork }
            val deltas = enabled.keys.intersect(disabled.keys).map { fork ->
                median(disabled.getValue(fork).batches.map { it.workloadNanos }) - median(enabled.getValue(fork).batches.map { it.workloadNanos })
            }
            val interval = bootstrapMedianInterval(deltas)
            "\"${mode.jsonName}\":{\"paired_median_delta_ns\":${median(
                deltas,
            )},\"paired_bootstrap_95_ci_low_ns\":${interval.first},\"paired_bootstrap_95_ci_high_ns\":${interval.second},\"pairs\":${deltas.size}}"
        }
        return "\"paired_cache_effects\":{$effects},"
    }

    internal fun fullMatrixPairExclusionReasons(
        baselineWorkloadCorrect: Boolean,
        candidateWorkloadCorrect: Boolean,
        candidateEvidenceComplete: Boolean,
    ): List<String> =
        buildList {
            if (!baselineWorkloadCorrect) add("baseline_workload_correctness")
            if (!candidateWorkloadCorrect) add("candidate_workload_correctness")
            if (!candidateEvidenceComplete) add("candidate_evidence_integrity")
        }

    internal fun pairedMedianInterval(values: List<Pair<Long, Long>>): PairedInterval {
        require(values.isNotEmpty()) { "paired benchmark has no samples" }
        val deltas = values.map { (baseline, candidate) -> candidate - baseline }
        val interval = bootstrapMedianInterval(deltas)
        return PairedInterval(median(deltas), interval.first, interval.second)
    }

    internal fun directComparisonNames(): List<String> = DIRECT_COMPARISONS.map { it.name }

    internal fun directComparisonModePairs(): Map<String, Pair<String, String>> =
        DIRECT_COMPARISONS.associate { comparison ->
            comparison.name to (comparison.left.name to comparison.right.name)
        }

    internal fun directPairedEffect(
        leftByFork: Map<Int, Long>,
        rightByFork: Map<Int, Long>,
    ): PairedInterval {
        val values = leftByFork.keys.intersect(rightByFork.keys).sorted().map { fork ->
            leftByFork.getValue(fork) to rightByFork.getValue(fork)
        }
        return pairedMedianInterval(values)
    }

    private fun directPairExclusionReasons(
        left: ForkResult,
        right: ForkResult,
    ): List<String> =
        buildList {
            if (left.verdict.workloadCorrectness != TierEBenchmarkContract.CorrectnessState.PASS) add("left_workload_correctness")
            if (right.verdict.workloadCorrectness != TierEBenchmarkContract.CorrectnessState.PASS) add("right_workload_correctness")
            if (left.verdict.wholeSessionEvidence != TierEBenchmarkContract.EvidenceState.COMPLETE) add("left_evidence_integrity")
            if (right.verdict.wholeSessionEvidence != TierEBenchmarkContract.EvidenceState.COMPLETE) add("right_evidence_integrity")
        }

    private data class DirectComparison(
        val name: String,
        val left: Mode,
        val right: Mode,
    )

    private val DIRECT_COMPARISONS = listOf(
        DirectComparison("inline_minus_dictionary_full_stream", Mode.FULL_STREAM, Mode.INLINE_FULL_STREAM),
        DirectComparison("inline_minus_dictionary_unique_edge", Mode.UNIQUE_EDGE, Mode.INLINE_UNIQUE_EDGE),
        DirectComparison("unique_minus_full_dictionary", Mode.FULL_STREAM, Mode.UNIQUE_EDGE),
        DirectComparison("unique_minus_full_inline", Mode.INLINE_FULL_STREAM, Mode.INLINE_UNIQUE_EDGE),
        DirectComparison("user_notif_stack_minus_no_stack", Mode.USER_NOTIF_NO_STACK, Mode.USER_NOTIF),
        DirectComparison("user_notif_no_stack_minus_dictionary_full_stream", Mode.FULL_STREAM, Mode.USER_NOTIF_NO_STACK),
        DirectComparison("user_notif_stack_minus_dictionary_full_stream", Mode.FULL_STREAM, Mode.USER_NOTIF),
        DirectComparison("user_notif_no_stack_minus_dictionary_unique_edge", Mode.UNIQUE_EDGE, Mode.USER_NOTIF_NO_STACK),
        DirectComparison("user_notif_stack_minus_dictionary_unique_edge", Mode.UNIQUE_EDGE, Mode.USER_NOTIF),
    )

    internal data class PairedInterval(
        val medianDeltaNanos: Long,
        val lowNanos: Long,
        val highNanos: Long,
    )

    private fun bootstrapMedianInterval(values: List<Long>): Pair<Long, Long> {
        require(values.isNotEmpty()) { "paired benchmark has no samples" }
        val random = Random(0)
        val medians = List(1_000) {
            median(List(values.size) { values[random.nextInt(values.size)] })
        }
        return percentile(medians, 2.5) to percentile(medians, 97.5)
    }

    private fun metricOrZero(
        output: String,
        name: String,
    ): Long =
        Regex("(?:^|\\s)$name=(-?\\d+)")
        .find(output)
        ?.groupValues
        ?.get(1)
        ?.toLong() ?: 0L

    private fun median(values: List<Long>): Long = values.sorted().let { sorted -> if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2 else sorted[sorted.size / 2] }

    private fun percentile(
        values: List<Long>,
        percentile: Double,
    ): Long = values.sorted().let { it[(kotlin.math.ceil(it.size * percentile / 100.0).toInt() - 1).coerceIn(0, it.lastIndex)] }

    private fun append(
        artifact: Path,
        line: String,
    ) = Files.writeString(
        artifact,
        withPolicyIneligibility(line) + System.lineSeparator(),
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND,
    )

    /** Every retained benchmark record is evidence only and must never be policy eligible. */
    internal fun withPolicyIneligibility(line: String): String {
        require(line.startsWith('{') && line.endsWith('}')) { "benchmark artifact must be a JSON object" }
        require("\"policy_eligibility\"" !in line) { "benchmark artifact already declares policy eligibility" }
        return line.dropLast(1) + ",\"policy_eligibility\":false}"
    }

    internal fun escape(value: String): String =
        buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
    }

    private enum class Mode(
        val jsonName: String,
        val emissionMode: String = "",
        val stackTransport: String = "none",
        val tierE: Boolean = false,
        val userNotif: Boolean = false,
        val userNotifStackOption: String = "",
        val requiresWorkloadFrames: Boolean = false,
    ) {
        BASELINE("baseline"),
        FULL_STREAM("dictionary_full_stream", "full-stream", "dictionary", true),
        UNIQUE_EDGE("dictionary_unique_edge", "unique-stack-syscall", "dictionary", true),
        INLINE_FULL_STREAM("inline_full_stream", "full-stream", "inline-per-invocation", true),
        INLINE_UNIQUE_EDGE("inline_unique_edge", "unique-stack-syscall", "inline-per-invocation", true),
        USER_NOTIF_NO_STACK("user_notif_no_stack", userNotif = true, userNotifStackOption = "no-stack"),
        USER_NOTIF("user_notif", userNotif = true, requiresWorkloadFrames = true),
    }

    private enum class Scope(
        val jsonName: String,
    ) {
        FULL("full"),
        TIER_E_COMPARABLE("tier-e-comparable"),
        TIER_E_CALIBRATION("tier-e-calibration"),
        TIER_E_CACHE_AB("tier-e-cache-ab"),
        TIER_E_CACHE_PAIRED("tier-e-cache-paired"),
        TIER_E_PUBLICATION("tier-e-publication"),
        TIER_E_DICTIONARY_PUBLICATION("tier-e-dictionary-publication"),
        TIER_E_CAPTURE_PROFILE("tier-e-capture-profile"),
        TIER_E_CAPTURE_TIMING("tier-e-capture-timing"),
        TIER_E_RING_DECODE("tier-e-ring-decode"),
        TIER_E_READINESS("tier-e-readiness"),
        TIER_E_POLL_BACKOFF("tier-e-poll-backoff"),
        TIER_E_RING_ARGUMENT_LIST("tier-e-ring-argument-list"),
        TIER_E_RING_ARGUMENT_VIEW("tier-e-ring-argument-view"),
        TIER_E_TASK_IDENTITY("tier-e-task-identity"),
        TIER_E_KNOWN_JOIN("tier-e-known-join"),
        TIER_E_RING_SUBMISSION_CONTROL("tier-e-ring-submission-control"),
        TIER_E_RETENTION_EXHAUSTION("tier-e-retention-exhaustion"),
        TIER_E_OWNED_ARGUMENTS("tier-e-owned-arguments"),
        TIER_E_READY_TRANSFER("tier-e-ready-transfer"),
        TIER_E_COLLECTOR_APPEND("tier-e-collector-append"),
        TIER_E_UNIQUE_EDGE_OBSERVER("tier-e-unique-edge-observer"),
        TIER_E_MARKER_TRANSPORT("tier-e-marker-transport"),
        TIER_E_MARKER_TRANSPORT_COMPARABLE("tier-e-marker-transport-comparable"),
        TIER_E_LIFECYCLE("tier-e-lifecycle"),
        TIER_E_PRODUCTION_SELECTION("tier-e-production-selection"),
        TIER_E_SUSTAINED_COLLECTOR("tier-e-sustained-collector"),
        ;

        companion object {
            fun fromProperty(value: String): Scope =
                entries.singleOrNull { it.jsonName == value }
                ?: error("unknown Tier E benchmark scope: $value")
        }
    }

    private enum class Publication(
        val jsonName: String,
    ) {
        TERMINAL("terminal"),
        LIVE("live"),
    }

    private enum class UniqueEdgeObserver(
        val wireName: String,
        val enabled: Boolean,
    ) {
        ORACLE("oracle", true),
        PRODUCTION("production", false),
    }

    private enum class NativePhaseTiming(
        val wireName: String,
        val enabled: Boolean,
    ) {
        ENABLED("enabled", true),
        DISABLED("disabled", false),
    }

    private enum class RingDecode(
        val wireName: String,
    ) {
        DIRECT("direct"),
        LEGACY("legacy"),
    }

    private enum class RingArgumentList(
        val wireName: String,
    ) {
        IMMUTABLE_FACTORY("immutable-factory"),
        LAMBDA_COPY("lambda-copy"),
    }

    private enum class RingArgumentView(
        val wireName: String,
    ) {
        LAZY("lazy"),
        EAGER_CONTROL("eager-control"),
    }

    private enum class TaskIdentity(
        val wireName: String,
    ) {
        PER_EVENT("per-event"),
        REUSE_CONSECUTIVE("reuse-consecutive"),
    }

    private enum class KnownJoin(
        val wireName: String,
    ) {
        QUEUED_CONTROL("queued-control"),
        DIRECT_KNOWN("direct-known"),
    }

    private enum class RingSubmission(
        val wireName: String,
    ) {
        ENABLED("enabled"),
        DISABLED("benchmark-disabled"),
    }

    private enum class OwnedArguments(
        val wireName: String,
    ) {
        OWNED("owned"),
        BOXED_CONTROL("boxed-control"),
    }

    private enum class ReadyTransfer(
        val wireName: String,
    ) {
        READ_ONLY("read-only"),
        COPY_CONTROL("copy-control"),
    }

    private enum class CollectorResult(
        val wireName: String,
    ) {
        TEMPORARY_LIST("temporary-list"),
        APPEND_TARGET("append-target"),
    }

    private enum class PollBackoff(
        val nanos: Long,
        val wireName: String,
        val policy: String,
        val maxNanos: Long,
    ) {
        NONE(0L, "none", "continuous", 0L),
        MICROS_50(50_000L, "50us", "fixed", 50_000L),
        MICROS_250(250_000L, "250us", "fixed", 250_000L),
        ADAPTIVE_1_TO_50(1_000L, "adaptive_1us_to_50us", "adaptive", 50_000L),
        READINESS(0L, "readiness_10ms", "readiness", 0L),
    }

    private enum class MarkerTransport(
        val wireName: String,
    ) {
        AUTO("auto"),
        UPROBE("uprobe"),
        USDT("usdt"),
    }

    private enum class Workload(
        val jsonName: String,
        val kernelArgument: String,
        val userNotifArgument: String,
        val className: String,
        val profile: TierESharedOpenatOracleWorkload.Profile,
        val applicationKind: TierECommonFileWorkload.ApplicationKind = TierECommonFileWorkload.ApplicationKind.BUFFERED_FILE_IO,
    ) {
        APPLICATION("application", COMMON_WORKLOAD, "common-file", TierECommonFileWorkload::class.java.name, TierESharedOpenatOracleWorkload.Profile.SYSCALL_DENSE),
        FILESYSTEM_METADATA(
            "filesystem_metadata",
            COMMON_WORKLOAD,
            "common-file",
            TierECommonFileWorkload::class.java.name,
            TierESharedOpenatOracleWorkload.Profile.SYSCALL_DENSE,
            TierECommonFileWorkload.ApplicationKind.FILESYSTEM_METADATA,
        ),
        BUFFERED_FILE_IO(
            "buffered_file_io",
            COMMON_WORKLOAD,
            "common-file",
            TierECommonFileWorkload::class.java.name,
            TierESharedOpenatOracleWorkload.Profile.SYSCALL_DENSE,
            TierECommonFileWorkload.ApplicationKind.BUFFERED_FILE_IO,
        ),
        LOOPBACK_SOCKET(
            "loopback_socket",
            COMMON_WORKLOAD,
            "common-file",
            TierECommonFileWorkload::class.java.name,
            TierESharedOpenatOracleWorkload.Profile.SYSCALL_DENSE,
            TierECommonFileWorkload.ApplicationKind.LOOPBACK_SOCKET,
        ),
        ORACLE("oracle", "shared-openat-oracle-workload", "shared-openat-oracle", TierESharedOpenatOracleWorkload::class.java.name, TierESharedOpenatOracleWorkload.Profile.SYSCALL_DENSE),
        SYSCALL_DENSE(
            "syscall_dense",
            "shared-openat-oracle-workload",
            "shared-openat-oracle",
            TierESharedOpenatOracleWorkload::class.java.name,
            TierESharedOpenatOracleWorkload.Profile.SYSCALL_DENSE,
        ),
        MIXED_CPU_IO("mixed_cpu_io", "shared-openat-oracle-workload", "shared-openat-oracle", TierESharedOpenatOracleWorkload::class.java.name, TierESharedOpenatOracleWorkload.Profile.MIXED_CPU_IO),
        BURSTY_IO("bursty_io", "shared-openat-oracle-workload", "shared-openat-oracle", TierESharedOpenatOracleWorkload::class.java.name, TierESharedOpenatOracleWorkload.Profile.BURSTY_IO),
        CALIBRATION_CPU(
            "calibration_cpu",
            "shared-openat-oracle-workload",
            "shared-openat-oracle",
            TierESharedOpenatOracleWorkload::class.java.name,
            TierESharedOpenatOracleWorkload.Profile.CALIBRATION_CPU,
        ),
        STACK_DIVERSE(
            "stack_diverse",
            "shared-openat-oracle-workload",
            "shared-openat-oracle",
            TierESharedOpenatOracleWorkload::class.java.name,
            TierESharedOpenatOracleWorkload.Profile.STACK_DIVERSE,
        ),
        CLASS_CHURN(
            "class_churn",
            "shared-openat-oracle-workload",
            "shared-openat-oracle",
            TierESharedOpenatOracleWorkload::class.java.name,
            TierESharedOpenatOracleWorkload.Profile.CLASS_CHURN,
        ),
        ;

        companion object {
            fun fromProperty(value: String): Workload = entries.singleOrNull { it.jsonName == value } ?: error("unknown Tier E benchmark workload: $value")
        }
    }

    private data class Variant(
        val mode: Mode,
        val cacheMode: String? = null,
        val publication: Publication = Publication.TERMINAL,
        val captureProfile: TierECaptureProfile? = null,
        val nativePhaseTiming: NativePhaseTiming? = null,
        val ringDecode: RingDecode? = null,
        val ringArgumentList: RingArgumentList? = null,
        val ringArgumentView: RingArgumentView? = null,
        val taskIdentity: TaskIdentity? = null,
        val knownJoin: KnownJoin? = null,
        val ringSubmission: RingSubmission? = null,
        val ownedArguments: OwnedArguments? = null,
        val readyTransfer: ReadyTransfer? = null,
        val collectorResult: CollectorResult? = null,
        val uniqueEdgeObserver: UniqueEdgeObserver? = null,
        val pollBackoff: PollBackoff? = null,
        val markerTransport: MarkerTransport? = null,
    ) {
        val isBenchmarkControl: Boolean
            get() = ringSubmission == RingSubmission.DISABLED

        fun jsonName(scope: Scope): String =
            when (scope) {
            Scope.FULL, Scope.TIER_E_COMPARABLE, Scope.TIER_E_CALIBRATION, Scope.TIER_E_CACHE_AB -> mode.jsonName
            Scope.TIER_E_CACHE_PAIRED -> if (mode.tierE) "${mode.jsonName}_cache_$cacheMode" else mode.jsonName
            Scope.TIER_E_PUBLICATION, Scope.TIER_E_DICTIONARY_PUBLICATION -> if (mode.tierE) "${mode.jsonName}_publication_${publication.jsonName}" else mode.jsonName
            Scope.TIER_E_CAPTURE_PROFILE ->
                if (mode.tierE) {
                    "${mode.jsonName}_profile_${requireNotNull(captureProfile).wireName.replace('-', '_')}"
                } else {
                    mode.jsonName
                }
            Scope.TIER_E_CAPTURE_TIMING ->
                if (mode.tierE) "${mode.jsonName}_native_timing_${requireNotNull(nativePhaseTiming).wireName}" else mode.jsonName
            Scope.TIER_E_RING_DECODE ->
                if (mode.tierE) "${mode.jsonName}_ring_decode_${ringDecode?.wireName ?: RingDecode.DIRECT.wireName}" else mode.jsonName
            Scope.TIER_E_POLL_BACKOFF -> if (mode.tierE) "${mode.jsonName}_poll_backoff_${requireNotNull(pollBackoff).wireName}" else mode.jsonName
            Scope.TIER_E_READINESS -> if (mode.tierE) "${mode.jsonName}_collector_${requireNotNull(pollBackoff).wireName}" else mode.jsonName
            Scope.TIER_E_SUSTAINED_COLLECTOR ->
                "${mode.jsonName}_collector_${requireNotNull(pollBackoff).takeIf { it != PollBackoff.NONE }?.wireName ?: "continuous"}"
            Scope.TIER_E_RING_ARGUMENT_LIST ->
                if (mode.tierE) "${mode.jsonName}_ring_arguments_${ringArgumentList?.wireName?.replace('-', '_') ?: RingArgumentList.IMMUTABLE_FACTORY.wireName.replace('-', '_')}" else mode.jsonName
            Scope.TIER_E_RING_ARGUMENT_VIEW ->
                if (mode.tierE) "${mode.jsonName}_ring_argument_view_${ringArgumentView?.wireName?.replace('-', '_') ?: RingArgumentView.LAZY.wireName}" else mode.jsonName
            Scope.TIER_E_TASK_IDENTITY ->
                if (mode.tierE) "${mode.jsonName}_task_identity_${taskIdentity?.wireName?.replace('-', '_') ?: TaskIdentity.PER_EVENT.wireName.replace('-', '_')}" else mode.jsonName
            Scope.TIER_E_KNOWN_JOIN ->
                if (mode.tierE) "${mode.jsonName}_known_join_${knownJoin?.wireName?.replace('-', '_') ?: KnownJoin.QUEUED_CONTROL.wireName.replace('-', '_')}" else mode.jsonName
            Scope.TIER_E_RING_SUBMISSION_CONTROL ->
                "${mode.jsonName}_ring_submission_${ringSubmission?.wireName?.replace('-', '_') ?: RingSubmission.ENABLED.wireName}"
            Scope.TIER_E_RETENTION_EXHAUSTION -> "${mode.jsonName}_retention_exhaustion"
            Scope.TIER_E_OWNED_ARGUMENTS ->
                "${mode.jsonName}_owned_arguments_${ownedArguments?.wireName?.replace('-', '_') ?: OwnedArguments.OWNED.wireName}"
            Scope.TIER_E_READY_TRANSFER ->
                "${mode.jsonName}_ready_transfer_${readyTransfer?.wireName?.replace('-', '_') ?: ReadyTransfer.READ_ONLY.wireName.replace('-', '_')}"
            Scope.TIER_E_COLLECTOR_APPEND ->
                "${mode.jsonName}_collector_result_${collectorResult?.wireName?.replace('-', '_') ?: CollectorResult.TEMPORARY_LIST.wireName.replace('-', '_')}"
            Scope.TIER_E_UNIQUE_EDGE_OBSERVER ->
                "${mode.jsonName}_observer_${uniqueEdgeObserver?.wireName ?: UniqueEdgeObserver.ORACLE.wireName}"
            Scope.TIER_E_MARKER_TRANSPORT, Scope.TIER_E_MARKER_TRANSPORT_COMPARABLE, Scope.TIER_E_LIFECYCLE, Scope.TIER_E_PRODUCTION_SELECTION ->
                if (mode.tierE) "${mode.jsonName}_marker_${markerTransport?.wireName ?: MarkerTransport.UPROBE.wireName}" else mode.jsonName
        }
    }

    private fun configuredCaptureProfile(): TierECaptureProfile = TierECaptureProfile.fromProperty(System.getProperty("mazewall.tier-e.capture-profile", "full"))

    private fun captureProfileFor(variant: Variant): TierECaptureProfile = variant.captureProfile ?: configuredCaptureProfile()

    private fun nativePhaseTimingFor(variant: Variant): NativePhaseTiming = variant.nativePhaseTiming ?: NativePhaseTiming.ENABLED

    private fun ringDecodeFor(variant: Variant): RingDecode = variant.ringDecode ?: RingDecode.LEGACY

    private fun ringArgumentListFor(variant: Variant): RingArgumentList = variant.ringArgumentList ?: RingArgumentList.IMMUTABLE_FACTORY

    private fun ringArgumentViewFor(variant: Variant): RingArgumentView = variant.ringArgumentView ?: RingArgumentView.LAZY

    private fun taskIdentityFor(variant: Variant): TaskIdentity = variant.taskIdentity ?: TaskIdentity.REUSE_CONSECUTIVE

    private fun knownJoinFor(variant: Variant): KnownJoin = variant.knownJoin ?: KnownJoin.QUEUED_CONTROL

    private fun ringSubmissionFor(variant: Variant): RingSubmission = variant.ringSubmission ?: RingSubmission.ENABLED

    private fun ownedArgumentsFor(variant: Variant): OwnedArguments = variant.ownedArguments ?: OwnedArguments.OWNED

    private fun readyTransferFor(variant: Variant): ReadyTransfer = variant.readyTransfer ?: ReadyTransfer.READ_ONLY

    private fun collectorResultFor(variant: Variant): CollectorResult = variant.collectorResult ?: CollectorResult.APPEND_TARGET

    private fun uniqueEdgeObserverFor(variant: Variant): UniqueEdgeObserver = variant.uniqueEdgeObserver ?: UniqueEdgeObserver.ORACLE

    private fun pollBackoffFor(variant: Variant): Long = variant.pollBackoff?.nanos ?: 0L

    private fun pollPolicyFor(variant: Variant): String = variant.pollBackoff?.policy ?: "continuous"

    private fun maxPollBackoffFor(variant: Variant): Long = variant.pollBackoff?.maxNanos ?: 0L

    private fun markerTransportFor(variant: Variant): MarkerTransport = variant.markerTransport ?: MarkerTransport.AUTO

    internal fun fullVariantNames(rotation: Int): List<String> = variants(Scope.FULL, rotation).map { it.jsonName(Scope.FULL) }

    internal fun comparableVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_COMPARABLE, rotation).map { it.jsonName(Scope.TIER_E_COMPARABLE) }

    internal fun calibrationVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_CALIBRATION, rotation).map { it.jsonName(Scope.TIER_E_CALIBRATION) }

    internal fun lifecycleVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_LIFECYCLE, rotation).map { it.jsonName(Scope.TIER_E_LIFECYCLE) }

    internal fun userNotifOperationOracleRequired(workload: String): Boolean = Workload.fromProperty(workload).userNotifArgument == "shared-openat-oracle"

    /**
     * These workloads emit an independently checked batch checksum and a terminal workload frame.
     * That pair is the Tier E controlled-workload oracle; USER_NOTIF still needs an identity oracle.
     */
    internal fun applicationWorkloadRequiresSemanticFrames(workload: String): Boolean = Workload.fromProperty(workload).kernelArgument == COMMON_WORKLOAD

    /** Benchmark-only fault injection: never terminate baseline or USER_NOTIF controls. */
    internal fun shouldTerminateChildAfterStart(
        afterMillis: Long,
        tierE: Boolean,
    ): Boolean = afterMillis > 0L && tierE

    internal fun captureProfileVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_CAPTURE_PROFILE, rotation).map { it.jsonName(Scope.TIER_E_CAPTURE_PROFILE) }

    internal fun captureTimingVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_CAPTURE_TIMING, rotation).map { it.jsonName(Scope.TIER_E_CAPTURE_TIMING) }

    internal fun ringDecodeVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_RING_DECODE, rotation).map { it.jsonName(Scope.TIER_E_RING_DECODE) }

    internal fun readinessVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_READINESS, rotation).map { it.jsonName(Scope.TIER_E_READINESS) }

    internal fun sustainedCollectorVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_SUSTAINED_COLLECTOR, rotation).map { it.jsonName(Scope.TIER_E_SUSTAINED_COLLECTOR) }

    internal fun pollBackoffVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_POLL_BACKOFF, rotation).map { it.jsonName(Scope.TIER_E_POLL_BACKOFF) }

    internal fun ringArgumentListVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_RING_ARGUMENT_LIST, rotation).map { it.jsonName(Scope.TIER_E_RING_ARGUMENT_LIST) }

    internal fun ringArgumentViewVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_RING_ARGUMENT_VIEW, rotation).map { it.jsonName(Scope.TIER_E_RING_ARGUMENT_VIEW) }

    internal fun taskIdentityVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_TASK_IDENTITY, rotation).map { it.jsonName(Scope.TIER_E_TASK_IDENTITY) }

    internal fun knownJoinVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_KNOWN_JOIN, rotation).map { it.jsonName(Scope.TIER_E_KNOWN_JOIN) }

    internal fun ringSubmissionControlVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_RING_SUBMISSION_CONTROL, rotation).map { it.jsonName(Scope.TIER_E_RING_SUBMISSION_CONTROL) }

    internal fun ownedArgumentsVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_OWNED_ARGUMENTS, rotation).map { it.jsonName(Scope.TIER_E_OWNED_ARGUMENTS) }

    internal fun readyTransferVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_READY_TRANSFER, rotation).map { it.jsonName(Scope.TIER_E_READY_TRANSFER) }

    internal fun collectorAppendVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_COLLECTOR_APPEND, rotation).map { it.jsonName(Scope.TIER_E_COLLECTOR_APPEND) }

    internal fun publicationVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_PUBLICATION, rotation).map { it.jsonName(Scope.TIER_E_PUBLICATION) }

    internal fun dictionaryPublicationVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_DICTIONARY_PUBLICATION, rotation).map { it.jsonName(Scope.TIER_E_DICTIONARY_PUBLICATION) }

    internal fun markerTransportVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_MARKER_TRANSPORT, rotation).map { it.jsonName(Scope.TIER_E_MARKER_TRANSPORT) }

    internal fun comparableMarkerTransportVariantNames(rotation: Int): List<String> =
        variants(Scope.TIER_E_MARKER_TRANSPORT_COMPARABLE, rotation).map { it.jsonName(Scope.TIER_E_MARKER_TRANSPORT_COMPARABLE) }

    internal fun productionSelectionVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_PRODUCTION_SELECTION, rotation).map { it.jsonName(Scope.TIER_E_PRODUCTION_SELECTION) }

    internal fun retentionExhaustionVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_RETENTION_EXHAUSTION, rotation).map { it.jsonName(Scope.TIER_E_RETENTION_EXHAUSTION) }

    private fun variants(
        scope: Scope,
        rotation: Int,
    ): List<Variant> {
        val selected = when (scope) {
            Scope.FULL, Scope.TIER_E_MARKER_TRANSPORT -> allModes
            // Ring decoding is collector-local. USER_NOTIF never executes this path, so including
            // it only lengthens the decision matrix without adding a paired control.
            Scope.TIER_E_RING_DECODE -> allModes.filter { it == Mode.BASELINE || it.tierE }
            Scope.TIER_E_POLL_BACKOFF, Scope.TIER_E_READINESS -> listOf(Mode.BASELINE, Mode.FULL_STREAM, Mode.UNIQUE_EDGE)
            Scope.TIER_E_RING_ARGUMENT_LIST, Scope.TIER_E_RING_ARGUMENT_VIEW, Scope.TIER_E_TASK_IDENTITY, Scope.TIER_E_KNOWN_JOIN -> listOf(Mode.BASELINE, Mode.FULL_STREAM)
            Scope.TIER_E_RING_SUBMISSION_CONTROL -> listOf(Mode.FULL_STREAM, Mode.UNIQUE_EDGE)
            Scope.TIER_E_RETENTION_EXHAUSTION -> listOf(Mode.FULL_STREAM)
            Scope.TIER_E_OWNED_ARGUMENTS -> listOf(Mode.FULL_STREAM, Mode.UNIQUE_EDGE)
            Scope.TIER_E_READY_TRANSFER -> listOf(Mode.FULL_STREAM, Mode.UNIQUE_EDGE)
            Scope.TIER_E_COLLECTOR_APPEND -> listOf(Mode.FULL_STREAM, Mode.UNIQUE_EDGE)
            Scope.TIER_E_UNIQUE_EDGE_OBSERVER -> listOf(Mode.UNIQUE_EDGE)
            Scope.TIER_E_MARKER_TRANSPORT_COMPARABLE -> listOf(
                Mode.BASELINE,
                Mode.FULL_STREAM,
                Mode.UNIQUE_EDGE,
                Mode.USER_NOTIF_NO_STACK,
                Mode.USER_NOTIF,
            )
            Scope.TIER_E_LIFECYCLE -> listOf(Mode.FULL_STREAM, Mode.UNIQUE_EDGE)
            Scope.TIER_E_SUSTAINED_COLLECTOR -> listOf(Mode.FULL_STREAM, Mode.UNIQUE_EDGE)
            Scope.TIER_E_PRODUCTION_SELECTION -> listOf(Mode.BASELINE, Mode.FULL_STREAM)
            Scope.TIER_E_COMPARABLE -> listOf(
                Mode.BASELINE,
                Mode.FULL_STREAM,
                Mode.UNIQUE_EDGE,
                Mode.USER_NOTIF_NO_STACK,
                Mode.USER_NOTIF,
            )
            Scope.TIER_E_CALIBRATION -> listOf(Mode.BASELINE)
            Scope.TIER_E_CACHE_AB, Scope.TIER_E_CACHE_PAIRED, Scope.TIER_E_PUBLICATION -> allModes.filter { it == Mode.BASELINE || it.tierE }
            Scope.TIER_E_DICTIONARY_PUBLICATION -> listOf(Mode.BASELINE, Mode.FULL_STREAM, Mode.UNIQUE_EDGE)
            Scope.TIER_E_CAPTURE_PROFILE -> listOf(Mode.BASELINE, Mode.FULL_STREAM)
            Scope.TIER_E_CAPTURE_TIMING -> listOf(Mode.BASELINE, Mode.FULL_STREAM)
        }.let { it.drop(rotation % it.size) + it.take(rotation % it.size) }
        return selected.flatMap { mode ->
            if (!mode.tierE) return@flatMap listOf(Variant(mode))
            when (scope) {
                Scope.TIER_E_CACHE_PAIRED -> {
                    val caches = if (rotation % 2 == 0) listOf("disabled", "enabled") else listOf("enabled", "disabled")
                    caches.map { cache -> Variant(mode, cache) }
                }
                Scope.TIER_E_PUBLICATION, Scope.TIER_E_DICTIONARY_PUBLICATION -> {
                    val publications = if (rotation % 2 == 0) {
                        listOf(Publication.TERMINAL, Publication.LIVE)
                    } else {
                        listOf(Publication.LIVE, Publication.TERMINAL)
                    }
                    publications.map { publication -> Variant(mode, publication = publication) }
                }
                Scope.TIER_E_CAPTURE_PROFILE -> {
                    val profiles = if (rotation % 2 == 0) {
                        listOf(TierECaptureProfile.Full, TierECaptureProfile.fromProperty("jdk25-unix-openat0"))
                    } else {
                        listOf(TierECaptureProfile.fromProperty("jdk25-unix-openat0"), TierECaptureProfile.Full)
                    }
                    profiles.map { captureProfile -> Variant(mode, captureProfile = captureProfile) }
                }
                Scope.TIER_E_CAPTURE_TIMING -> {
                    val timingModes = if (rotation % 2 == 0) {
                        listOf(NativePhaseTiming.ENABLED, NativePhaseTiming.DISABLED)
                    } else {
                        listOf(NativePhaseTiming.DISABLED, NativePhaseTiming.ENABLED)
                    }
                    timingModes.map { nativePhaseTiming -> Variant(mode, nativePhaseTiming = nativePhaseTiming) }
                }
                Scope.TIER_E_RING_DECODE -> {
                    val decoders = if (rotation % 2 == 0) listOf(RingDecode.DIRECT, RingDecode.LEGACY) else listOf(RingDecode.LEGACY, RingDecode.DIRECT)
                    decoders.map { ringDecode -> Variant(mode, ringDecode = ringDecode) }
                }
                Scope.TIER_E_POLL_BACKOFF -> {
                    val backoffEntries = PollBackoff.entries.filterNot { it == PollBackoff.READINESS }
                    val backoffs = if (rotation % 2 == 0) backoffEntries else backoffEntries.reversed()
                    backoffs.map { pollBackoff -> Variant(mode, pollBackoff = pollBackoff) }
                }
                Scope.TIER_E_READINESS -> {
                    val policies = if (rotation % 2 == 0) {
                        listOf(PollBackoff.NONE, PollBackoff.READINESS)
                    } else {
                        listOf(PollBackoff.READINESS, PollBackoff.NONE)
                    }
                    policies.map { policy -> Variant(mode, pollBackoff = policy) }
                }
                Scope.TIER_E_SUSTAINED_COLLECTOR -> {
                    val policies = if (rotation % 2 == 0) {
                        listOf(PollBackoff.NONE, PollBackoff.ADAPTIVE_1_TO_50)
                    } else {
                        listOf(PollBackoff.ADAPTIVE_1_TO_50, PollBackoff.NONE)
                    }
                    policies.map { policy -> Variant(mode, pollBackoff = policy) }
                }
                Scope.TIER_E_RING_ARGUMENT_LIST -> {
                    val constructions = if (rotation % 2 == 0) {
                        listOf(RingArgumentList.IMMUTABLE_FACTORY, RingArgumentList.LAMBDA_COPY)
                    } else {
                        listOf(RingArgumentList.LAMBDA_COPY, RingArgumentList.IMMUTABLE_FACTORY)
                    }
                    constructions.map { construction ->
                        Variant(
                            mode,
                            ringDecode = RingDecode.DIRECT,
                            ringArgumentList = construction,
                            markerTransport = MarkerTransport.AUTO,
                        )
                    }
                }
                Scope.TIER_E_RING_ARGUMENT_VIEW -> {
                    val views = if (rotation % 2 == 0) {
                        listOf(RingArgumentView.LAZY, RingArgumentView.EAGER_CONTROL)
                    } else {
                        listOf(RingArgumentView.EAGER_CONTROL, RingArgumentView.LAZY)
                    }
                    views.map { view ->
                        Variant(
                            mode,
                            ringDecode = RingDecode.DIRECT,
                            ringArgumentView = view,
                            markerTransport = MarkerTransport.AUTO,
                        )
                    }
                }
                Scope.TIER_E_TASK_IDENTITY -> {
                    val identities = if (rotation % 2 == 0) {
                        listOf(TaskIdentity.PER_EVENT, TaskIdentity.REUSE_CONSECUTIVE)
                    } else {
                        listOf(TaskIdentity.REUSE_CONSECUTIVE, TaskIdentity.PER_EVENT)
                    }
                    identities.map { taskIdentity ->
                        Variant(
                            mode,
                            ringDecode = RingDecode.DIRECT,
                            taskIdentity = taskIdentity,
                            markerTransport = MarkerTransport.AUTO,
                        )
                    }
                }
                Scope.TIER_E_KNOWN_JOIN -> {
                    val joins = if (rotation % 2 == 0) {
                        listOf(KnownJoin.QUEUED_CONTROL, KnownJoin.DIRECT_KNOWN)
                    } else {
                        listOf(KnownJoin.DIRECT_KNOWN, KnownJoin.QUEUED_CONTROL)
                    }
                    joins.map { knownJoin ->
                        Variant(
                            mode,
                            ringDecode = RingDecode.DIRECT,
                            knownJoin = knownJoin,
                            markerTransport = MarkerTransport.AUTO,
                        )
                    }
                }
                Scope.TIER_E_RING_SUBMISSION_CONTROL -> {
                    val submissions = if (rotation % 2 == 0) RingSubmission.entries else RingSubmission.entries.reversed()
                    submissions.map { submission -> Variant(mode, ringSubmission = submission) }
                }
                Scope.TIER_E_OWNED_ARGUMENTS -> {
                    val storage = if (rotation % 2 == 0) OwnedArguments.entries else OwnedArguments.entries.reversed()
                    storage.map { ownedArguments -> Variant(mode, ownedArguments = ownedArguments) }
                }
                Scope.TIER_E_READY_TRANSFER -> {
                    val transfers = if (rotation % 2 == 0) ReadyTransfer.entries else ReadyTransfer.entries.reversed()
                    transfers.map { readyTransfer -> Variant(mode, readyTransfer = readyTransfer) }
                }
                Scope.TIER_E_COLLECTOR_APPEND -> {
                    val results = if (rotation % 2 == 0) CollectorResult.entries else CollectorResult.entries.reversed()
                    results.map { collectorResult -> Variant(mode, collectorResult = collectorResult) }
                }
                Scope.TIER_E_UNIQUE_EDGE_OBSERVER -> {
                    val observers = if (rotation % 2 == 0) UniqueEdgeObserver.entries else UniqueEdgeObserver.entries.reversed()
                    observers.map { observer -> Variant(mode, uniqueEdgeObserver = observer) }
                }
                Scope.TIER_E_MARKER_TRANSPORT, Scope.TIER_E_MARKER_TRANSPORT_COMPARABLE, Scope.TIER_E_LIFECYCLE -> {
                    val transports = if (rotation % 2 == 0) {
                        listOf(MarkerTransport.UPROBE, MarkerTransport.USDT)
                    } else {
                        listOf(MarkerTransport.USDT, MarkerTransport.UPROBE)
                    }
                    transports.map { markerTransport -> Variant(mode, markerTransport = markerTransport) }
                }
                Scope.TIER_E_PRODUCTION_SELECTION -> {
                    val transports = if (rotation % 2 == 0) {
                        listOf(MarkerTransport.AUTO, MarkerTransport.UPROBE, MarkerTransport.USDT)
                    } else {
                        listOf(MarkerTransport.USDT, MarkerTransport.UPROBE, MarkerTransport.AUTO)
                    }
                    transports.map { markerTransport -> Variant(mode, markerTransport = markerTransport) }
                }
                Scope.TIER_E_CALIBRATION -> listOf(Variant(mode))
                else -> listOf(Variant(mode))
            }
        }
    }

    private data class Config(
        val agent: Path,
        val iterations: Int,
        val warmups: Int,
        val batches: Int,
        val forks: Int,
        val workloadCpu: String,
        val collectorCpu: String,
        val artifact: Path,
        val scope: Scope,
        val workload: Workload,
        val lifecycleScenario: String,
        val dumpPerfMapAtExit: Boolean,
        val childTimeoutSeconds: Long,
        val childTerminationAfterMillis: Long,
        val userNotifWorkerTimeoutSeconds: Long,
        val userNotifChildTimeoutSeconds: Long,
        val calibrationEnabled: Boolean,
        val runLevel: String,
        val sustainedLoad: TierESustainedCollectorDecision.Load,
        val sustainedRatePerSecond: Long,
    )

    private data class CalibrationOutcome(
        val selection: TierEBenchmarkCalibration.Selection,
        val artifact: String,
    )

    private data class Batch(
        val index: Int,
        val operations: Long,
        val checksum: Long,
        val workloadNanos: Long,
    )

    private data class Evidence(
        val observations: Long,
        val resolved: Long,
        val workloadResolved: Long,
        val workloadUnlinksResolved: Long,
        val unknownNoActive: Long,
        val ambientInactive: Long,
        val unknownOther: Long,
        val wrong: Long,
        val lost: Long,
        val attributionFailures: Long,
        val dropped: Long,
        val reconciliationAdmittedInvocations: Long,
        val reconciliationExactInvocations: Long,
        val reconciliationFailedInvocations: Long,
        val reconciliationKernelActiveObservations: Long,
        val workloadFrames: Boolean,
        val evidenceComplete: Boolean,
        val producerClosed: Boolean,
        val selectedMarkerTransport: String?,
        val controlledWorkloadVerified: Boolean,
        val detachedInactiveScenario: Boolean,
        val terminalWhileActiveScenario: Boolean,
        val forcedCaptureFailureScenario: Boolean,
        val execTransitionScenario: Boolean,
        val callbackSuppressionScenario: Boolean,
        val callbackProbeResolved: Long,
        val observerFreeUniqueEdgeMeasurement: Boolean,
        val profileSemanticStackComplete: Boolean,
        val profileSemanticStackDigest: String?,
        val operationOracle: TierEBenchmarkContract.OperationOracle,
        val phases: PhaseMetrics,
        val captureYield: CaptureYieldMetrics,
        val userNotifPhases: UserNotifPhaseMetrics,
    ) {
        fun contractEvidence() =
            TierEBenchmarkContract.Evidence(
            observations = observations,
            workloadResolved = workloadResolved,
            workloadUnlinksResolved = workloadUnlinksResolved,
            wrong = wrong,
            lost = lost,
            unknownOther = unknownOther,
            ambientInactive = ambientInactive,
            dropped = dropped,
            workloadFrames = workloadFrames,
            evidenceComplete = evidenceComplete,
            controlledWorkloadVerified = controlledWorkloadVerified,
            detachedInactiveScenario = detachedInactiveScenario,
            terminalWhileActiveScenario = terminalWhileActiveScenario,
            forcedCaptureFailureScenario = forcedCaptureFailureScenario,
            execTransitionScenario = execTransitionScenario,
            callbackSuppressionScenario = callbackSuppressionScenario,
            callbackProbeResolved = callbackProbeResolved,
            observerFreeUniqueEdgeMeasurement = observerFreeUniqueEdgeMeasurement,
            operationOracle = operationOracle,
        )
    }

    internal data class PhaseMetrics(
        val getStackTraceNanos: Long,
        val rawKeyNanos: Long,
        val rawHashNanos: Long,
        val rawEqualityNanos: Long,
        val generationValidationNanos: Long,
        val guardValidationNanos: Long,
        val canonicalCacheLockWaitNanos: Long,
        val logicalMetadataNanos: Long,
        val markerSetNanos: Long,
        val markerClearNanos: Long,
        val markerAdmissionClearNanos: Long,
        val markerExactSetNanos: Long,
        val markerExitClearNanos: Long,
        val markerAdmissionClearCount: Long,
        val markerExactSetCount: Long,
        val markerExitClearCount: Long,
        val ringDrainNanos: Long,
        val ringScanNanos: Long,
        val ringPayloadSliceNanos: Long,
        val ringDecodeNanos: Long,
        val ringAppendNanos: Long,
        val ringConsumerPublishNanos: Long,
        val ringScannedRecords: Long,
        val ringReadyRecords: Long,
        val ringReaderInitializationNanos: Long,
        val collectorJoinNanos: Long,
        val allocationMeasurementAvailable: Long,
        val ringDrainAllocatedBytes: Long,
        val collectorJoinAllocatedBytes: Long,
        val observationConversionAllocatedBytes: Long,
        val collectorResolutionAllocatedBytes: Long,
        val resolvedEvidenceAllocatedBytes: Long,
        val joinerEnqueueAllocatedBytes: Long,
        val joinerTakeReadyAllocatedBytes: Long,
        val uniqueEdgeGateAllocatedBytes: Long,
        val uniqueEdgeSuppressionDiagnosticAllocatedBytes: Long,
        val uniqueEdgeCandidateOwnershipAllocatedBytes: Long,
        val uniqueEdgeCandidateDiagnosticAllocatedBytes: Long,
        val batchFreezeAllocatedBytes: Long,
        val collectorPolls: Long,
        val collectorLoopActivePolls: Long,
        val collectorLoopIdlePolls: Long,
        val collectorLoopParks: Long,
        val collectorLoopParkedNanos: Long,
        val collectorLoopMaxConsecutiveIdlePolls: Long,
        val collectorReadinessWaits: Long,
        val collectorReadinessWakeups: Long,
        val collectorReadinessTimeouts: Long,
        val collectorReadinessSpuriousWakeups: Long,
        val collectorReadinessWaitedNanos: Long,
        val collectorReceiptSamples: Long,
        val collectorReceiptMinNanos: Long,
        val collectorReceiptMeanNanos: Long,
        val collectorReceiptMaxNanos: Long,
        val collectorReceiptP50UpperNanos: Long,
        val collectorReceiptP95UpperNanos: Long,
        val collectorReceiptP99UpperNanos: Long,
        val ringRecordsCopied: Long,
        val ringBytesCopied: Long,
        val ringBusyRecords: Long,
        val ringDiscardedRecords: Long,
        val ringScanPasses: Long,
        val ringBatches: Long,
        val ringMaxBatchRecords: Long,
        val serializationNanos: Long,
        val publicationBytes: Long,
        val publicationLatencyNanos: Long,
        val lockWaitNanos: Long,
        val peakRetainedBytes: Long,
        val nativeRetainedBytes: Long,
        val nativeRetentionExhaustions: Long,
        val publicationCount: Long,
        val coalescedPublications: Long,
        val failedPublications: Long,
        val cacheHits: Long,
        val cacheMisses: Long,
    )

    private data class UserNotifPhaseMetrics(
        val timedOperations: Long,
        val operationNanos: Long,
        val blockedBeforeListenerNanos: Long,
        val listenerToAckNanos: Long,
        val stackCaptureNanos: Long,
        val queueNanos: Long,
        val ackToReturnNanos: Long,
    )

    private data class CaptureYieldMetrics(
        val records: Long,
        val captured: Long,
        val captureFailed: Long,
        val admissionFailed: Long,
        val excluded: Long,
        val exactJoined: Long,
        val missingDefinition: Long,
        val outcomeMismatch: Long,
        val unrecordedDefinitions: Long,
        val bindings: Map<Int, CaptureYieldBindingMetrics>,
    )

    internal data class CaptureYieldBindingMetrics(
        val records: Long,
        val exactJoined: Long,
        val missingDefinition: Long,
        val outcomeMismatch: Long,
        val resolvedInvocations: Long,
        val resolvedSyscalls: Long,
        val capturedWithoutResolvedSyscall: Long,
    )

    internal data class CaptureEfficiencyMetrics(
        val executedOperations: Long,
        val exactCaptures: Long,
        val resolvedSyscalls: Long,
        val productiveCaptures: Long,
        val getStackTraceNanos: Long,
        val valid: Boolean,
    )

    private data class ForkResult(
        val scope: Scope,
        val lifecycleScenario: String,
        val sustainedLoad: TierESustainedCollectorDecision.Load,
        val sustainedRatePerSecond: Long,
        val variant: Variant,
        val workloadProfile: TierESharedOpenatOracleWorkload.Profile,
        val fork: Int,
        val batches: List<Batch>,
        val evidence: Evidence,
        val captureEfficiency: CaptureEfficiencyMetrics,
        val verdict: TierEBenchmarkContract.Verdict,
        val elapsedNanos: Long,
        val targetPid: Long,
        val cpuNanos: Long?,
        val peakRssBytes: Long,
    )

    private data class ProcessTelemetry(
        val completed: Boolean,
        val targetPid: Long,
        val cpuNanos: Long?,
        val peakRssBytes: Long,
    )

    private val BATCH_PATTERN =
        Regex("(?:tier-e-common-workload|tier-e-write-oracle|tier-e-shared-openat-oracle|user-notif-oracle)-batch: phase=(\\w+) index=(\\d+) operations=(\\d+) checksum=(-?\\d+) .*workloadNs=(\\d+)")

    private val CAPTURE_YIELD_BINDINGS_PATTERN = Regex("(?:^|\\s)capture_yield_bindings=([^\\s]*)")
}
