package io.mazewall.profiler.tierE.stress

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
        val config = Config(
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
            childTimeoutSeconds = System.getProperty("mazewall.tier-e.child-timeout-seconds", "180").toLong(),
            userNotifWorkerTimeoutSeconds = System.getProperty("mazewall.tier-e.user-notif-worker-timeout-seconds", "480").toLong(),
            userNotifChildTimeoutSeconds = System.getProperty("mazewall.tier-e.user-notif-child-timeout-seconds", "540").toLong(),
        )
        require(Files.isRegularFile(config.agent)) { "missing agent: ${config.agent}" }
        require(
            config.iterations > 0 &&
                config.warmups >= 0 &&
                config.batches > 0 &&
                config.forks > 0 &&
                config.childTimeoutSeconds > 0 &&
                config.userNotifWorkerTimeoutSeconds > 0 &&
                config.userNotifChildTimeoutSeconds > config.userNotifWorkerTimeoutSeconds,
        ) {
            "benchmark dimensions and deadlines must be positive; USER_NOTIF child timeout must exceed its worker timeout"
        }
        config.artifact.parent?.createDirectories()
        Files.deleteIfExists(config.artifact)
        append(config.artifact, metadata(config))
        try {
            val forks = buildList {
                repeat(config.forks) { fork ->
                    variants(config.scope, fork).forEach { variant ->
                        add(run(variant, fork + 1, config))
                    }
                }
            }
            verify(forks, config)
            append(config.artifact, summary(forks, config))
            append(config.artifact, terminal(success = true, message = "completed"))
            val profiledForks = forks.filterNot { it.variant.mode == Mode.BASELINE }
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
        val definitions = Files.createTempFile("tier-e-matched-${mode.jsonName}-", ".mzsd")
        val captureYield = if (mode.tierE) Files.createTempFile("tier-e-matched-${mode.jsonName}-", ".mzcy") else null
        Files.deleteIfExists(definitions)
        captureYield?.let(Files::deleteIfExists)
        val command = when (mode) {
            Mode.BASELINE -> listOf(
                java,
                "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
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
            Mode.FULL_STREAM, Mode.UNIQUE_EDGE, Mode.INLINE_FULL_STREAM, Mode.INLINE_UNIQUE_EDGE -> listOf(
                java,
                "-Dmazewall.tier-e.canonical-cache=${variant.cacheMode ?: canonicalCache}",
                "-Dmazewall.tier-e.capture-profile=${captureProfile.wireName}",
                "-Dmazewall.tier-e.live-publication=${variant.publication == Publication.LIVE}",
                "-Dmazewall.tier-e.capture-yield=$captureYield",
                "-Dmazewall.tier-e.ring-capacity-bytes=8388608",
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
            Mode.USER_NOTIF, Mode.USER_NOTIF_NO_STACK -> listOf(
                java,
                "--enable-native-access=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
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
        val outputFile = Files.createTempFile("tier-e-matched-output-", ".log")
        val started = System.nanoTime()
        val process = ProcessBuilder(listOf("taskset", "-c", cpu) + command)
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile())
            .start()
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
        Files.deleteIfExists(outputFile)
        val elapsedNanos = System.nanoTime() - started
        if (!completed || process.exitValue() != 0) {
            append(config.artifact, "{\"kind\":\"failure\",\"variant\":\"${variant.jsonName(config.scope)}\",\"fork\":$fork,\"timed_out\":${!completed},\"output\":\"${escape(output)}\"}")
            error("${variant.jsonName(config.scope)} fork $fork ${if (completed) "failed" else "timed out"}:\n$output")
        }
        val batches = BATCH_PATTERN
            .findAll(output)
            .filter { it.groupValues[1] == "measured" }
            .map { match ->
            Batch(match.groupValues[2].toInt(), match.groupValues[3].toLong(), match.groupValues[4].toLong(), match.groupValues[5].toLong())
        }.toList()
        check(batches.size == config.batches) { "${mode.jsonName} fork $fork emitted ${batches.size} measured batches" }
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
            mode == Mode.BASELINE && batches.all { it.operations == config.iterations.toLong() },
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
        val verdict = TierEBenchmarkContract.evaluate(
            mode.tierE,
            mode.userNotif,
            mode.requiresWorkloadFrames,
            evidence.contractEvidence(),
            requiresOperationOracle = mode.userNotif && config.workload == Workload.ORACLE,
        )
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
            variant,
            fork,
            batches,
            evidence,
            captureEfficiency,
            verdict,
            elapsedNanos,
            telemetry.cpuNanos,
            telemetry.peakRssBytes,
        )
        append(config.artifact, forkJson(result, output))
        batches.forEach { batch -> append(config.artifact, sampleJson(variant, config.scope, fork, batch)) }
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
            check(result.batches.size == config.batches) { "${result.variant.mode.jsonName} has incomplete batches" }
            result.batches.forEach { batch ->
                check(batch.operations == config.iterations.toLong()) { "${result.variant.mode.jsonName} operation mismatch" }
            }
            check(result.verdict.workloadCorrectness != TierEBenchmarkContract.CorrectnessState.FAILED) {
                "${result.variant.jsonName(config.scope)} workload failure: ${result.verdict.failures}"
            }
        }
    }

    private fun metadata(config: Config): String {
        val cacheMode = System.getProperty("mazewall.tier-e.canonical-cache", "enabled")
        val captureProfile = escape(configuredCaptureProfile().wireName)
        val captureProfileStrategy = if (config.scope == Scope.TIER_E_CAPTURE_PROFILE) "per_variant" else "configured"
        val sourceRevision = escape(System.getenv("MAZEWALL_BENCHMARK_SOURCE_REVISION") ?: "unknown")
        val sourceDirty = escape(System.getenv("MAZEWALL_BENCHMARK_SOURCE_DIRTY") ?: "unknown")
        val javaVersion = escape(System.getProperty("java.version"))
        val operatingSystem = escape(System.getProperty("os.name") + " " + System.getProperty("os.version"))
        return "{\"kind\":\"metadata\",\"timestamp\":\"${Instant.now()}\",\"iterations\":${config.iterations}," +
            "\"warmups\":${config.warmups},\"batches\":${config.batches},\"forks\":${config.forks}," +
            "\"workload_cpu\":\"${config.workloadCpu}\",\"collector_cpu\":\"${config.collectorCpu}\"," +
            "\"canonical_cache\":\"$cacheMode\",\"capture_profile\":\"$captureProfile\",\"capture_profile_strategy\":\"$captureProfileStrategy\"," +
            "\"scope\":\"${config.scope.jsonName}\"," +
            "\"child_timeout_seconds\":${config.childTimeoutSeconds}," +
            "\"user_notif_worker_timeout_seconds\":${config.userNotifWorkerTimeoutSeconds}," +
            "\"user_notif_child_timeout_seconds\":${config.userNotifChildTimeoutSeconds}," +
            "\"source_revision\":\"$sourceRevision\",\"source_dirty\":\"$sourceDirty\",\"java_version\":\"$javaVersion\",\"os\":\"$operatingSystem\"}"
    }

    private fun summary(
        forks: List<ForkResult>,
        config: Config,
    ): String {
        val variants = forks.groupBy { it.variant.jsonName(config.scope) }.entries.joinToString(",") { (name, values) ->
            val batches = values.flatMap { it.batches }.map { it.workloadNanos }
            val complete = values.count { it.verdict.wholeSessionEvidence == TierEBenchmarkContract.EvidenceState.COMPLETE }
            val performanceEvidence = performanceEvidence(
                values.first().verdict.workloadCorrectness,
                values.first().verdict.wholeSessionEvidence,
            )
            "\"$name\":{\"median_ns\":${median(batches)},\"p95_ns\":${percentile(batches, 95.0)}," +
                "\"p99_ns\":${percentile(batches, 99.0)},\"whole_session_complete_forks\":$complete," +
                "\"forks\":${values.size},\"performance_evidence\":\"$performanceEvidence\"}"
        }
        val profiledForks = forks.filterNot { it.variant.mode == Mode.BASELINE }
        val correctness = when {
            profiledForks.any { it.verdict.workloadCorrectness == TierEBenchmarkContract.CorrectnessState.FAILED } -> "FAILED"
            profiledForks.isNotEmpty() && profiledForks.all { it.verdict.workloadCorrectness == TierEBenchmarkContract.CorrectnessState.PASS } -> "PASS"
            else -> "UNVERIFIED"
        }
        return "{\"kind\":\"summary\",\"timestamp\":\"${Instant.now()}\",\"workload_correctness\":\"$correctness\",\"policy_eligibility\":false," +
            "$variants,${pairedSummary(forks, config.scope)}\"sample_count\":${forks.sumOf { it.batches.size }}," +
            "\"expected_per_variant\":${config.forks * config.batches}}"
    }

    private fun sampleJson(
        variant: Variant,
        scope: Scope,
        fork: Int,
        batch: Batch,
    ): String =
        "{\"kind\":\"sample\",\"variant\":\"${variant.jsonName(scope)}\",\"mode\":\"${variant.mode.jsonName}\"," +
            "\"transport\":\"${variant.mode.stackTransport}\",\"cache\":\"${variant.cacheMode ?: "not_applicable"}\"," +
            "\"publication\":\"${variant.publication.jsonName}\",\"capture_profile\":\"${captureProfileFor(variant).wireName}\",\"fork\":$fork,\"batch\":${batch.index}," +
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
        val fields = listOf(
            "\"kind\":\"fork-output\"",
            "\"variant\":\"${result.variant.jsonName(result.scope)}\"",
            "\"mode\":\"${result.variant.mode.jsonName}\"",
            "\"capture_profile\":\"${captureProfileFor(result.variant).wireName}\"",
            "\"capture_profile_domain\":\"${captureProfileFor(result.variant).domain}\"",
            "\"fork\":${result.fork}",
            "\"elapsed_ns\":${result.elapsedNanos}",
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

    private fun phaseJson(phases: PhaseMetrics): String =
        listOf(
            "\"get_stack_trace_ns\":${phases.getStackTraceNanos}",
            "\"raw_key_ns\":${phases.rawKeyNanos}",
            "\"logical_metadata_ns\":${phases.logicalMetadataNanos}",
            "\"marker_set_ns\":${phases.markerSetNanos}",
            "\"marker_clear_ns\":${phases.markerClearNanos}",
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
            metricOrZero(output, "logical_metadata_ns"),
            metricOrZero(output, "marker_set_ns"),
            metricOrZero(output, "marker_clear_ns"),
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
        narrowedWorkloadCorrect: Boolean,
        narrowedEvidenceComplete: Boolean,
        narrowedCaptureEfficiencyValid: Boolean,
    ): List<String> =
        buildList {
        if (!fullWorkloadCorrect) add("full_workload_correctness")
        if (!fullEvidenceComplete) add("full_evidence_integrity")
        if (!fullCaptureEfficiencyValid) add("full_capture_efficiency")
        if (!narrowedWorkloadCorrect) add("narrowed_workload_correctness")
        if (!narrowedEvidenceComplete) add("narrowed_evidence_integrity")
        if (!narrowedCaptureEfficiencyValid) add("narrowed_capture_efficiency")
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
        return ProcessTelemetry(!process.isAlive, cpuNanos, peakRssBytes)
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
        scope: Scope,
    ): String {
        if (scope == Scope.TIER_E_CAPTURE_PROFILE) {
            val values = forks.filter { it.variant.mode == Mode.FULL_STREAM }
            val full = values.filter { captureProfileFor(it.variant) == CaptureProfile.FULL }.associateBy { it.fork }
            val narrowed = values.filter { captureProfileFor(it.variant) == CaptureProfile.JDK25_UNIX_OPENAT0 }.associateBy { it.fork }
            val pairs = full.keys.intersect(narrowed.keys).sorted().map { fork ->
                fork to (full.getValue(fork) to narrowed.getValue(fork))
            }
            val exclusions = pairs
                .mapNotNull { (fork, results) ->
                val reasons = captureProfilePairExclusionReasons(
                    fullWorkloadCorrect = results.first.verdict.workloadCorrectness == TierEBenchmarkContract.CorrectnessState.PASS,
                    fullEvidenceComplete = results.first.verdict.wholeSessionEvidence == TierEBenchmarkContract.EvidenceState.COMPLETE,
                    fullCaptureEfficiencyValid = results.first.captureEfficiency.valid,
                    narrowedWorkloadCorrect = results.second.verdict.workloadCorrectness == TierEBenchmarkContract.CorrectnessState.PASS,
                    narrowedEvidenceComplete = results.second.verdict.wholeSessionEvidence == TierEBenchmarkContract.EvidenceState.COMPLETE,
                    narrowedCaptureEfficiencyValid = results.second.captureEfficiency.valid,
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
    ) = Files.writeString(artifact, line + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)

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
        TIER_E_CACHE_AB("tier-e-cache-ab"),
        TIER_E_CACHE_PAIRED("tier-e-cache-paired"),
        TIER_E_PUBLICATION("tier-e-publication"),
        TIER_E_CAPTURE_PROFILE("tier-e-capture-profile"),
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

    private enum class CaptureProfile(
        val wireName: String,
        val domain: String,
    ) {
        FULL("full", "all admitted native bindings"),
        JDK25_UNIX_OPENAT0("jdk25-unix-openat0", "JDK 25 UnixNativeDispatcher.openat0"),
        ;

        companion object {
            fun fromProperty(value: String): CaptureProfile =
                entries.singleOrNull { it.wireName == value.ifBlank { FULL.wireName } }
                ?: error("unknown Tier E capture profile: $value")
        }
    }

    private enum class Workload(
        val jsonName: String,
        val kernelArgument: String,
        val userNotifArgument: String,
        val className: String,
    ) {
        APPLICATION("application", COMMON_WORKLOAD, "common-file", TierECommonFileWorkload::class.java.name),
        ORACLE("oracle", "shared-openat-oracle-workload", "shared-openat-oracle", TierESharedOpenatOracleWorkload::class.java.name),
        ;

        companion object {
            fun fromProperty(value: String): Workload = entries.singleOrNull { it.jsonName == value } ?: error("unknown Tier E benchmark workload: $value")
        }
    }

    private data class Variant(
        val mode: Mode,
        val cacheMode: String? = null,
        val publication: Publication = Publication.TERMINAL,
        val captureProfile: CaptureProfile? = null,
    ) {
        fun jsonName(scope: Scope): String =
            when (scope) {
            Scope.FULL, Scope.TIER_E_CACHE_AB -> mode.jsonName
            Scope.TIER_E_CACHE_PAIRED -> if (mode.tierE) "${mode.jsonName}_cache_$cacheMode" else mode.jsonName
            Scope.TIER_E_PUBLICATION -> if (mode.tierE) "${mode.jsonName}_publication_${publication.jsonName}" else mode.jsonName
            Scope.TIER_E_CAPTURE_PROFILE ->
                if (mode.tierE) {
                    "${mode.jsonName}_profile_${requireNotNull(captureProfile).wireName.replace('-', '_')}"
                } else {
                    mode.jsonName
                }
        }
    }

    private fun configuredCaptureProfile(): CaptureProfile = CaptureProfile.fromProperty(System.getProperty("mazewall.tier-e.capture-profile", "full"))

    private fun captureProfileFor(variant: Variant): CaptureProfile = variant.captureProfile ?: configuredCaptureProfile()

    internal fun fullVariantNames(rotation: Int): List<String> = variants(Scope.FULL, rotation).map { it.jsonName(Scope.FULL) }

    internal fun captureProfileVariantNames(rotation: Int): List<String> = variants(Scope.TIER_E_CAPTURE_PROFILE, rotation).map { it.jsonName(Scope.TIER_E_CAPTURE_PROFILE) }

    private fun variants(
        scope: Scope,
        rotation: Int,
    ): List<Variant> {
        val selected = when (scope) {
            Scope.FULL -> allModes
            Scope.TIER_E_CACHE_AB, Scope.TIER_E_CACHE_PAIRED, Scope.TIER_E_PUBLICATION -> allModes.filter { it == Mode.BASELINE || it.tierE }
            Scope.TIER_E_CAPTURE_PROFILE -> listOf(Mode.BASELINE, Mode.FULL_STREAM)
        }.let { it.drop(rotation % it.size) + it.take(rotation % it.size) }
        return selected.flatMap { mode ->
            if (!mode.tierE) return@flatMap listOf(Variant(mode))
            when (scope) {
                Scope.TIER_E_CACHE_PAIRED -> {
                    val caches = if (rotation % 2 == 0) listOf("disabled", "enabled") else listOf("enabled", "disabled")
                    caches.map { cache -> Variant(mode, cache) }
                }
                Scope.TIER_E_PUBLICATION -> listOf(Variant(mode, publication = Publication.TERMINAL), Variant(mode, publication = Publication.LIVE))
                Scope.TIER_E_CAPTURE_PROFILE -> {
                    val profiles = if (rotation % 2 == 0) {
                        listOf(CaptureProfile.FULL, CaptureProfile.JDK25_UNIX_OPENAT0)
                    } else {
                        listOf(CaptureProfile.JDK25_UNIX_OPENAT0, CaptureProfile.FULL)
                    }
                    profiles.map { captureProfile -> Variant(mode, captureProfile = captureProfile) }
                }
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
        val childTimeoutSeconds: Long,
        val userNotifWorkerTimeoutSeconds: Long,
        val userNotifChildTimeoutSeconds: Long,
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
        val controlledWorkloadVerified: Boolean,
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
            dropped = dropped,
            workloadFrames = workloadFrames,
            evidenceComplete = evidenceComplete,
            controlledWorkloadVerified = controlledWorkloadVerified,
            operationOracle = operationOracle,
        )
    }

    internal data class PhaseMetrics(
        val getStackTraceNanos: Long,
        val rawKeyNanos: Long,
        val logicalMetadataNanos: Long,
        val markerSetNanos: Long,
        val markerClearNanos: Long,
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
        val variant: Variant,
        val fork: Int,
        val batches: List<Batch>,
        val evidence: Evidence,
        val captureEfficiency: CaptureEfficiencyMetrics,
        val verdict: TierEBenchmarkContract.Verdict,
        val elapsedNanos: Long,
        val cpuNanos: Long?,
        val peakRssBytes: Long,
    )

    private data class ProcessTelemetry(
        val completed: Boolean,
        val cpuNanos: Long?,
        val peakRssBytes: Long,
    )

    private val BATCH_PATTERN =
        Regex("(?:tier-e-common-workload|tier-e-write-oracle|tier-e-shared-openat-oracle|user-notif-oracle)-batch: phase=(\\w+) index=(\\d+) operations=(\\d+) checksum=(-?\\d+) .*workloadNs=(\\d+)")

    private val CAPTURE_YIELD_BINDINGS_PATTERN = Regex("(?:^|\\s)capture_yield_bindings=([^\\s]*)")
}
