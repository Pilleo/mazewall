package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.api.Profiler
import io.mazewall.profiler.api.ProfilerExecutionLimits
import io.mazewall.profiler.internal.BenchmarkRawSyscallDiagnostics

/** Controlled slow-oracle check for the equivalent file workload. */
internal object UserNotifOracleSmoke {
    @JvmStatic
    public fun main(args: Array<String>) {
        val benchmark = args.firstOrNull() == "benchmark"
        val warmups = if (benchmark) args.getOrNull(1)?.toInt() ?: error("missing warmups") else 0
        val batches = if (benchmark) args.getOrNull(2)?.toInt() ?: error("missing batches") else 1
        val iterations = if (benchmark) args.getOrNull(3)?.toInt() ?: error("missing iterations") else args.singleOrNull()?.toInt() ?: 1
        val captureStackTraces = !benchmark || args.getOrNull(4) != "no-stack"
        val writeOracle = benchmark && args.getOrNull(5) == "write-oracle"
        val sharedOpenatOracle = benchmark && args.getOrNull(5) == "shared-openat-oracle"
        val oracleEnabled = writeOracle || sharedOpenatOracle
        val expectedOperations = if (oracleEnabled) (warmups + batches).toLong() * iterations else (warmups + batches).toLong()
        val expectedOperationIds = LinkedHashSet<Int>()
        val oracleObservations = mutableListOf<TierEOperationOracle.Observation>()
        val oracleDiagnostics = mutableListOf<String>()
        val diagnostics = if (sharedOpenatOracle) BenchmarkRawSyscallDiagnostics.begin { it == "OPENAT" } else null
        var rawDiagnostics = emptyList<BenchmarkRawSyscallDiagnostics.Record>()
        val profileStarted = System.nanoTime()
        val workerTimeoutSeconds = if (benchmark) {
            args.getOrNull(6)?.toLong() ?: DEFAULT_DIAGNOSTIC_WORKER_TIMEOUT_SECONDS
        } else {
            DEFAULT_DIAGNOSTIC_WORKER_TIMEOUT_SECONDS
        }
        val result = try {
            Profiler.profileForDiagnostics(
                processWide = false,
                captureStackTraces = captureStackTraces,
                limits = diagnosticLimits(workerTimeoutSeconds),
            ) {
                runWorkloadBatches(
                    writeOracle,
                    sharedOpenatOracle,
                    warmups,
                    batches,
                    iterations,
                    onSharedOpenatPrepared = { directoryFds -> expectedOperationIds.addAll(directoryFds) },
                    onSharedOpenatOperationsComplete = { rawDiagnostics = diagnostics?.finish().orEmpty() },
                    onSharedOpenatOperationStarted = { directoryFd -> diagnostics?.operationStarted(directoryFd.toLong()) },
                    onSharedOpenatOperationReturned = { directoryFd -> diagnostics?.operationReturned(directoryFd.toLong()) },
                )
            }
        } finally {
            if (rawDiagnostics.isEmpty()) rawDiagnostics = diagnostics?.finish().orEmpty()
        }
        val profileSessionNanos = System.nanoTime() - profileStarted
        val diagnosticDrops = diagnostics?.droppedCount ?: 0L
        rawDiagnostics.forEach { raw ->
            if (oracleDiagnostics.size < 12) oracleDiagnostics += "${raw.syscallName}:${raw.arguments.joinToString(",")}"
            val operationId = sharedOpenatOperationId(raw.arguments)
            if (operationId != null && operationId in expectedOperationIds) {
                oracleObservations += TierEOperationOracle.Observation(operationId, true, true, raw.tid > 0, raw.tid > 0)
            }
        }
        if (writeOracle) oracleDiagnostics += "write_not_selected_by_user_notif"
        val measuredBatches = result.value
        val workloadNanos = measuredBatches.sumOf { it.result.workloadNanos }
        val observations = result.observations.size
        val stackCount = result.stackProfile.values.sumOf(List<Array<StackTraceElement>>::size)
        val droppedEvents = result.coverage.droppedEvents
        val workloadFrames = result.stackProfile.values
            .flatten()
            .any { frames ->
                frames.any {
                    it.className ==
                        when {
                            writeOracle -> TierEWriteOracleWorkload::class.java.name
                            sharedOpenatOracle -> TierESharedOpenatOracleWorkload::class.java.name
                            else -> TierECommonFileWorkload::class.java.name
                        }
                }
            }
        val evidenceComplete = result.coverage.complete && (!captureStackTraces || workloadFrames)
        measuredBatches.forEach { batch ->
            println(
                "user-notif-oracle-batch: phase=${batch.phase.name.lowercase()} index=${batch.index} operations=${batch.result.completedOperations} checksum=${batch.result.checksum} workloadNs=${batch.result.workloadNanos}",
            )
        }
        val oracle = if (sharedOpenatOracle) {
            TierEOperationOracle.evaluate(expectedOperationIds, oracleObservations)
        } else {
            TierEOperationOracle.Report(0, 0, 0, 0, 0)
        }
        val operationOracleAvailable = sharedOpenatOracle
        val operationTimings = diagnostics
            ?.let { capture ->
            rawDiagnostics
                .filter { raw -> sharedOpenatOperationId(raw.arguments) in expectedOperationIds }
                .map(capture::timing)
        }.orEmpty()
        val timingSummary = UserNotifTimingSummary.from(operationTimings)
        println(
            "user-notif-oracle: observations=$observations stacks=$stackCount " +
                "dropped=$droppedEvents complete=$evidenceComplete " +
                "diagnostic_dropped=$diagnosticDrops " +
                "workloadFrames=$workloadFrames evidenceComplete=$evidenceComplete " +
                "expected_operations=${if (oracleEnabled) oracle.expected else expectedOperations} matched_operations=${oracle.matched} " +
                "duplicate_operations=${oracle.duplicates} wrong_operations=${oracle.wrong} " +
                "unclassified_operations=${oracle.unclassified} operation_oracle_available=$operationOracleAvailable " +
                "operation_oracle_reason=${if (operationOracleAvailable) "shared_openat_raw_diagnostic" else "write_not_selected_by_user_notif"} " +
                "operation_oracle_diagnostics=${oracleDiagnostics.take(8).joinToString("|")} " +
                "profile_session_ns=$profileSessionNanos workload_ns=$workloadNanos " +
                "profile_overhead_ns=${(profileSessionNanos - workloadNanos).coerceAtLeast(0L)} " +
                timingSummary.render(),
        )
        check(observations > 0) { "USER_NOTIF oracle observed no syscalls" }
        if (captureStackTraces) check(stackCount > 0) { "USER_NOTIF oracle captured no stacks" }
        // A benchmark must retain a valid workload verdict even when the bounded profiler
        // reports incomplete whole-session evidence. The rendered `complete=false` remains
        // machine-readable; ordinary diagnostics continue to fail closed below.
        if (!benchmark) check(droppedEvents == 0) { "USER_NOTIF oracle dropped events" }
        if (benchmark) check(diagnosticDrops == 0L) { "USER_NOTIF benchmark diagnostics dropped $diagnosticDrops records" }
        if (captureStackTraces) check(workloadFrames) { "USER_NOTIF oracle did not capture the shared workload stack" }
        if (operationOracleAvailable) {
            check(oracle.expected == oracle.matched && oracle.duplicates == 0L && oracle.wrong == 0L && oracle.unclassified == 0L) {
                "USER_NOTIF write oracle mismatch: $oracle"
            }
        }
    }

    internal fun diagnosticLimits(workerTimeoutSeconds: Long): ProfilerExecutionLimits {
        require(workerTimeoutSeconds > 0) { "worker timeout must be positive" }
        return ProfilerExecutionLimits(
            workerTimeoutMillis = Math.multiplyExact(workerTimeoutSeconds, 1_000L),
            workerGraceMillis = DEFAULT_WORKER_GRACE_MILLIS,
        )
    }

    private fun runWorkloadBatches(
        writeOracle: Boolean,
        sharedOpenatOracle: Boolean,
        warmups: Int,
        batches: Int,
        iterations: Int,
        onSharedOpenatPrepared: (List<Int>) -> Unit,
        onSharedOpenatOperationsComplete: () -> Unit,
        onSharedOpenatOperationStarted: (Int) -> Unit,
        onSharedOpenatOperationReturned: (Int) -> Unit,
    ): List<TierECommonFileWorkload.Batch> =
        if (writeOracle) {
            TierEWriteOracleWorkload.runBatches(warmups, batches, iterations).map { batch ->
                TierECommonFileWorkload.Batch(
                    TierECommonFileWorkload.BatchPhase.valueOf(batch.phase.name),
                    batch.index,
                    TierECommonFileWorkload.Result(
                        batch.result.completedOperations,
                        batch.result.checksum,
                        batch.result.workloadNanos,
                    ),
                )
            }
        } else if (sharedOpenatOracle) {
            TierESharedOpenatOracleWorkload
                .runBatches(
                warmups,
                batches,
                iterations,
                onSharedOpenatPrepared,
                onSharedOpenatOperationsComplete,
                onSharedOpenatOperationStarted,
                onSharedOpenatOperationReturned,
            ).map { batch ->
                TierECommonFileWorkload.Batch(
                    TierECommonFileWorkload.BatchPhase.valueOf(batch.phase.name),
                    batch.index,
                    TierECommonFileWorkload.Result(
                        batch.result.completedOperations,
                        batch.result.checksum,
                        batch.result.workloadNanos,
                    ),
                )
            }
        } else {
            TierECommonFileWorkload.runBatches(warmups, batches, iterations)
        }

    /** `openat(dirfd, pathname, flags, mode)` is identified by its preallocated directory FD. */
    internal fun sharedOpenatOperationId(arguments: List<Long>): Int? = arguments.getOrNull(0)?.toInt()

    private data class UserNotifTimingSummary(
        val count: Int,
        val operationNanos: Long,
        val blockedBeforeListenerNanos: Long,
        val listenerToAckNanos: Long,
        val stackCaptureNanos: Long,
        val queueNanos: Long,
        val ackToReturnNanos: Long,
    ) {
        fun render(): String =
            "user_notif_timed_operations=$count user_notif_operation_ns=$operationNanos " +
                "user_notif_blocked_before_listener_ns=$blockedBeforeListenerNanos " +
                "user_notif_listener_to_ack_ns=$listenerToAckNanos " +
                "user_notif_stack_capture_ns=$stackCaptureNanos user_notif_queue_ns=$queueNanos " +
                "user_notif_ack_to_return_ns=$ackToReturnNanos"

        companion object {
            fun from(timings: List<BenchmarkRawSyscallDiagnostics.Timing>): UserNotifTimingSummary {
                fun median(values: List<Long>): Long = values.filter { it >= 0L }.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }
                return UserNotifTimingSummary(
                    timings.size,
                    median(timings.map { it.operationReturnedNanos - it.operationStartedNanos }),
                    median(timings.map { it.listenerReceivedNanos - it.operationStartedNanos }),
                    median(timings.map { it.acknowledgedNanos - it.listenerReceivedNanos }),
                    median(timings.map { it.stackCaptureNanos }),
                    median(timings.map { it.queueNanos }),
                    median(timings.map { it.operationReturnedNanos - it.acknowledgedNanos }),
                )
            }
        }
    }

    private const val DEFAULT_DIAGNOSTIC_WORKER_TIMEOUT_SECONDS = 60L
    private const val DEFAULT_WORKER_GRACE_MILLIS = 5_000L
}
