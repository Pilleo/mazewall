package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.internal.attribution.ManagedFrame
import io.mazewall.profiler.internal.attribution.ManagedFrameKind
import io.mazewall.profiler.internal.attribution.StackCaptureQuality
import io.mazewall.profiler.internal.attribution.StackDefinition
import io.mazewall.profiler.internal.attribution.StackTraceId
import io.mazewall.profiler.internal.attribution.TierECaptureProfile
import io.mazewall.profiler.internal.attribution.TierECaptureProfilePreset
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierEReadyTransferMode
import io.mazewall.profiler.internal.attribution.TierERingDecodeMode
import io.mazewall.profiler.internal.attribution.TierERingSubmissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TierEMatchedBenchmarkJsonTest {
    @Test
    fun `targeted selection determines terminal expected fork count`() {
        val selected = TierEMatchedBenchmark.selectVariantNames(
            available = listOf("baseline", "dictionary_full_stream", "dictionary_unique_edge"),
            selector = "dictionary_unique_edge",
        )

        assertEquals(1, selected.size)
        assertEquals(5, TierEMatchedBenchmark.expectedSelectedForkCount(selected.size, forks = 5))
        assertFailsWith<IllegalArgumentException> {
            TierEMatchedBenchmark.expectedSelectedForkCount(selectedVariantCount = 0, forks = 1)
        }
    }

    @Test
    fun `comparable long-run scope excludes only bounded inline transports`() {
        val expected = setOf(
            "baseline",
            "dictionary_full_stream",
            "dictionary_unique_edge",
            "user_notif_no_stack",
            "user_notif",
        )

        repeat(expected.size) { rotation ->
            assertEquals(expected, TierEMatchedBenchmark.comparableVariantNames(rotation).toSet())
        }
    }

    @Test
    fun `bounded inline snapshot publication has a machine-readable failure class`() {
        assertEquals(
            "inline_snapshot_exceeds_retained_limit",
            TierEMatchedBenchmark.failureClass(
                "mazewall-stack-agent: definition_publication_failure=encoded_snapshot_exceeds_retained_limit",
            ),
        )
        assertEquals(null, TierEMatchedBenchmark.failureClass("unrelated child failure"))
    }

    @Test
    fun `injected child termination takes precedence over child output classification`() {
        assertEquals(
            "injected_child_termination",
            TierEMatchedBenchmark.classifiedChildFailure(
                "definition_publication_failure=encoded_snapshot_exceeds_retained_limit",
                injectedTermination = true,
            ),
        )
    }

    @Test
    fun `marker transport scope rotates all paired tier e arms with unchanged controls`() {
        val expected = setOf(
            "baseline",
            "dictionary_full_stream_marker_uprobe",
            "dictionary_full_stream_marker_usdt",
            "dictionary_unique_edge_marker_uprobe",
            "dictionary_unique_edge_marker_usdt",
            "inline_full_stream_marker_uprobe",
            "inline_full_stream_marker_usdt",
            "inline_unique_edge_marker_uprobe",
            "inline_unique_edge_marker_usdt",
            "user_notif_no_stack",
            "user_notif",
        )

        repeat(expected.size) { rotation ->
            val scheduled = TierEMatchedBenchmark.markerTransportVariantNames(rotation)
            assertEquals(expected, scheduled.toSet())
            assertEquals(expected.size, scheduled.size)
        }
    }

    @Test
    fun `comparable marker transport scope excludes bounded inline transport`() {
        val expected = setOf(
            "baseline",
            "dictionary_full_stream_marker_uprobe",
            "dictionary_full_stream_marker_usdt",
            "dictionary_unique_edge_marker_uprobe",
            "dictionary_unique_edge_marker_usdt",
            "user_notif_no_stack",
            "user_notif",
        )

        repeat(expected.size) { rotation ->
            assertEquals(expected, TierEMatchedBenchmark.comparableMarkerTransportVariantNames(rotation).toSet())
        }
    }

    @Test
    fun `production selection scope compares auto with both concrete dictionary transports`() {
        val expected = setOf(
            "baseline",
            "dictionary_full_stream_marker_auto",
            "dictionary_full_stream_marker_uprobe",
            "dictionary_full_stream_marker_usdt",
        )

        repeat(expected.size) { rotation ->
            assertEquals(expected, TierEMatchedBenchmark.productionSelectionVariantNames(rotation).toSet())
        }
    }

    @Test
    fun `full benchmark schedules every profiler comparator exactly once per rotated fork`() {
        val expected = setOf(
            "baseline",
            "dictionary_full_stream",
            "dictionary_unique_edge",
            "inline_full_stream",
            "inline_unique_edge",
            "user_notif_no_stack",
            "user_notif",
        )

        repeat(expected.size) { rotation ->
            val scheduled = TierEMatchedBenchmark.fullVariantNames(rotation)
            assertEquals(expected, scheduled.toSet())
            assertEquals(expected.size, scheduled.size)
        }
    }

    @Test
    fun `calibration scope schedules only the unprofiled baseline`() {
        assertEquals(listOf("baseline"), TierEMatchedBenchmark.calibrationVariantNames(0))
        assertEquals(listOf("baseline"), TierEMatchedBenchmark.calibrationVariantNames(3))
    }

    @Test
    fun `shared openat workloads require user notif operation proof`() {
        assertEquals(true, TierEMatchedBenchmark.userNotifOperationOracleRequired("calibration_cpu"))
        assertEquals(true, TierEMatchedBenchmark.userNotifOperationOracleRequired("oracle"))
        assertEquals(false, TierEMatchedBenchmark.userNotifOperationOracleRequired("application"))
        assertEquals(false, TierEMatchedBenchmark.userNotifOperationOracleRequired("filesystem_metadata"))
        assertEquals(false, TierEMatchedBenchmark.userNotifOperationOracleRequired("buffered_file_io"))
        assertEquals(false, TierEMatchedBenchmark.userNotifOperationOracleRequired("loopback_socket"))
    }

    @Test
    fun `application workloads require checked terminal frames for Tier E semantic verification`() {
        assertEquals(true, TierEMatchedBenchmark.applicationWorkloadRequiresSemanticFrames("application"))
        assertEquals(true, TierEMatchedBenchmark.applicationWorkloadRequiresSemanticFrames("filesystem_metadata"))
        assertEquals(true, TierEMatchedBenchmark.applicationWorkloadRequiresSemanticFrames("buffered_file_io"))
        assertEquals(true, TierEMatchedBenchmark.applicationWorkloadRequiresSemanticFrames("loopback_socket"))
        assertEquals(false, TierEMatchedBenchmark.applicationWorkloadRequiresSemanticFrames("syscall_dense"))
    }

    @Test
    fun `child termination fixture targets only a Tier E child`() {
        assertEquals(true, TierEMatchedBenchmark.shouldTerminateChildAfterStart(afterMillis = 25, tierE = true))
        assertEquals(false, TierEMatchedBenchmark.shouldTerminateChildAfterStart(afterMillis = 0, tierE = true))
        assertEquals(false, TierEMatchedBenchmark.shouldTerminateChildAfterStart(afterMillis = 25, tierE = false))
    }

    @Test
    fun `capture profile scope pairs full and narrowed dictionary captures`() {
        assertEquals(
            setOf(
                "baseline",
                "dictionary_full_stream_profile_full",
                "dictionary_full_stream_profile_jdk25_unix_openat0",
            ),
            TierEMatchedBenchmark.captureProfileVariantNames(0).toSet(),
        )
    }

    @Test
    fun `reviewed narrowed capture profile matches the application workload family`() {
        assertEquals(
            "unix-open0+unix-openat0+unix-close0+unix-unlink0+file-read0+file-write0+file-close-int-fd",
            TierEMatchedBenchmark.reviewedCaptureProfileForWorkload("buffered_file_io").wireName,
        )
        assertEquals(
            TierECaptureProfilePreset.NETWORK_IO.profile,
            TierEMatchedBenchmark.reviewedCaptureProfileForWorkload("loopback_socket"),
        )
        assertEquals(
            "jdk25-unix-openat0",
            TierEMatchedBenchmark.reviewedCaptureProfileForWorkload("oracle").wireName,
        )
    }

    @Test
    fun `capture profile boundary metrics aggregate native admitted and excluded bindings`() {
        assertEquals(
            TierEMatchedBenchmark.CaptureProfileBoundaryMetrics(admitted = 7, rejected = 11),
            TierEMatchedBenchmark.captureProfileBoundaryMetrics(
                "mazewall-stack-agent: capture-profile-binding=unix-openat0 admitted=3 excluded=5\\n" +
                    "mazewall-stack-agent: capture-profile-binding=unix-close0 admitted=4 excluded=6\\n",
            ),
        )
    }

    @Test
    fun `ring decode scope pairs direct and legacy Tier E arms without unrelated user notify controls`() {
        val expected = setOf(
            "baseline",
            "dictionary_full_stream_ring_decode_direct",
            "dictionary_full_stream_ring_decode_legacy",
            "dictionary_unique_edge_ring_decode_direct",
            "dictionary_unique_edge_ring_decode_legacy",
            "inline_full_stream_ring_decode_direct",
            "inline_full_stream_ring_decode_legacy",
            "inline_unique_edge_ring_decode_direct",
            "inline_unique_edge_ring_decode_legacy",
        )

        repeat(expected.size) { rotation ->
            val scheduled = TierEMatchedBenchmark.ringDecodeVariantNames(rotation)
            assertEquals(expected, scheduled.toSet())
            assertEquals(expected.size, scheduled.size)
        }
    }

    @Test
    fun `sustained collector scope measures only continuous production policy`() {
        val expected = setOf(
            "dictionary_full_stream_collector_continuous",
            "dictionary_unique_edge_collector_continuous",
        )

        assertEquals(expected, TierEMatchedBenchmark.sustainedCollectorVariantNames(0).toSet())
        assertEquals(expected, TierEMatchedBenchmark.sustainedCollectorVariantNames(1).toSet())
    }

    @Test
    fun `readiness scope contains only paired dictionary collector policies and baseline`() {
        val expected = setOf(
            "baseline",
            "dictionary_full_stream_collector_none",
            "dictionary_full_stream_collector_readiness_10ms",
            "dictionary_unique_edge_collector_none",
            "dictionary_unique_edge_collector_readiness_10ms",
        )

        repeat(expected.size) { rotation ->
            assertEquals(expected, TierEMatchedBenchmark.readinessVariantNames(rotation).toSet())
        }
    }

    @Test
    fun `capture timing scope rotates an instrumented production mode against its disabled control`() {
        val expected = setOf(
            "baseline",
            "dictionary_full_stream_native_timing_enabled",
            "dictionary_full_stream_native_timing_disabled",
        )

        repeat(expected.size) { rotation ->
            assertEquals(expected, TierEMatchedBenchmark.captureTimingVariantNames(rotation).toSet())
        }
    }

    @Test
    fun `ring argument list scope rotates paired direct decoder constructions`() {
        val expected = setOf(
            "baseline",
            "dictionary_full_stream_ring_arguments_immutable_factory",
            "dictionary_full_stream_ring_arguments_lambda_copy",
        )

        repeat(expected.size) { rotation ->
            val scheduled = TierEMatchedBenchmark.ringArgumentListVariantNames(rotation)
            assertEquals(expected, scheduled.toSet())
            assertEquals(expected.size, scheduled.size)
        }
    }

    @Test
    fun `ring argument view scope rotates paired lazy and eager controls`() {
        val expected = setOf(
            "baseline",
            "dictionary_full_stream_ring_argument_view_lazy",
            "dictionary_full_stream_ring_argument_view_eager_control",
        )

        repeat(expected.size) { rotation ->
            val scheduled = TierEMatchedBenchmark.ringArgumentViewVariantNames(rotation)
            assertEquals(expected, scheduled.toSet())
            assertEquals(expected.size, scheduled.size)
        }
    }

    @Test
    fun `task identity scope rotates paired per-event and consecutive reuse controls`() {
        val expected = setOf(
            "baseline",
            "dictionary_full_stream_task_identity_per_event",
            "dictionary_full_stream_task_identity_reuse_consecutive",
        )

        repeat(expected.size) { rotation ->
            val scheduled = TierEMatchedBenchmark.taskIdentityVariantNames(rotation)
            assertEquals(expected, scheduled.toSet())
            assertEquals(expected.size, scheduled.size)
        }
    }

    @Test
    fun `owned argument scope rotates primitive and boxed controls for both dictionary modes`() {
        val expected = setOf(
            "dictionary_full_stream_owned_arguments_owned",
            "dictionary_full_stream_owned_arguments_boxed_control",
            "dictionary_unique_edge_owned_arguments_owned",
            "dictionary_unique_edge_owned_arguments_boxed_control",
        )

        repeat(expected.size) { rotation ->
            val scheduled = TierEMatchedBenchmark.ownedArgumentsVariantNames(rotation)
            assertEquals(expected, scheduled.toSet())
            assertEquals(expected.size, scheduled.size)
        }
        assertEquals(true, TierEOptions().ownedArgumentStorage)
    }

    @Test
    fun `ready transfer scope rotates copy and read only controls for both dictionary modes`() {
        val expected = setOf(
            "dictionary_full_stream_ready_transfer_read_only",
            "dictionary_full_stream_ready_transfer_copy_control",
            "dictionary_unique_edge_ready_transfer_read_only",
            "dictionary_unique_edge_ready_transfer_copy_control",
        )

        repeat(expected.size) { rotation ->
            val scheduled = TierEMatchedBenchmark.readyTransferVariantNames(rotation)
            assertEquals(expected, scheduled.toSet())
            assertEquals(expected.size, scheduled.size)
        }
        assertEquals(TierEReadyTransferMode.READ_ONLY, TierEOptions().readyTransferMode)
    }

    @Test
    fun `ring submission control variants cover both modes and retain safe defaults`() {
        assertEquals(
            listOf(
                "dictionary_full_stream_ring_submission_enabled",
                "dictionary_full_stream_ring_submission_benchmark_disabled",
                "dictionary_unique_edge_ring_submission_enabled",
                "dictionary_unique_edge_ring_submission_benchmark_disabled",
            ),
            TierEMatchedBenchmark.ringSubmissionControlVariantNames(rotation = 0),
        )
        assertEquals(
            listOf(
                "dictionary_unique_edge_ring_submission_benchmark_disabled",
                "dictionary_unique_edge_ring_submission_enabled",
                "dictionary_full_stream_ring_submission_benchmark_disabled",
                "dictionary_full_stream_ring_submission_enabled",
            ),
            TierEMatchedBenchmark.ringSubmissionControlVariantNames(rotation = 1),
        )
        assertEquals(TierERingSubmissionMode.ENABLED, TierEOptions().ringSubmissionMode)
    }

    @Test
    fun `retention exhaustion scope is isolated to one explicit full stream fixture`() {
        assertEquals(
            listOf("dictionary_full_stream_retention_exhaustion"),
            TierEMatchedBenchmark.retentionExhaustionVariantNames(rotation = 0),
        )
        assertEquals(
            listOf("dictionary_full_stream_retention_exhaustion"),
            TierEMatchedBenchmark.retentionExhaustionVariantNames(rotation = 4),
        )
    }

    @Test
    fun `poll backoff scope rotates bounded dictionary collector pacing arms`() {
        val expected = setOf(
            "baseline",
            "dictionary_full_stream_poll_backoff_none",
            "dictionary_full_stream_poll_backoff_50us",
            "dictionary_full_stream_poll_backoff_250us",
            "dictionary_full_stream_poll_backoff_adaptive_1us_to_50us",
            "dictionary_unique_edge_poll_backoff_none",
            "dictionary_unique_edge_poll_backoff_50us",
            "dictionary_unique_edge_poll_backoff_250us",
            "dictionary_unique_edge_poll_backoff_adaptive_1us_to_50us",
        )
        repeat(expected.size) { rotation ->
            assertEquals(expected, TierEMatchedBenchmark.pollBackoffVariantNames(rotation).toSet())
        }
    }

    @Test
    fun `publication scope rotates terminal and live arms`() {
        val expected = setOf(
            "baseline",
            "dictionary_full_stream_publication_terminal",
            "dictionary_full_stream_publication_live",
            "dictionary_unique_edge_publication_terminal",
            "dictionary_unique_edge_publication_live",
            "inline_full_stream_publication_terminal",
            "inline_full_stream_publication_live",
            "inline_unique_edge_publication_terminal",
            "inline_unique_edge_publication_live",
        )

        repeat(4) { rotation ->
            val scheduled = TierEMatchedBenchmark.publicationVariantNames(rotation)
            assertEquals(expected, scheduled.toSet())
            assertEquals(expected.size, scheduled.size)
            assertEquals(
                rotation % 2 == 0,
                scheduled.indexOf("dictionary_full_stream_publication_terminal") <
                    scheduled.indexOf("dictionary_full_stream_publication_live"),
            )
        }
    }

    @Test
    fun `dictionary publication scope retains only baseline and paired dictionary arms`() {
        val expected = setOf(
            "baseline",
            "dictionary_full_stream_publication_terminal",
            "dictionary_full_stream_publication_live",
            "dictionary_unique_edge_publication_terminal",
            "dictionary_unique_edge_publication_live",
        )

        repeat(4) { rotation ->
            val scheduled = TierEMatchedBenchmark.dictionaryPublicationVariantNames(rotation)
            assertEquals(expected, scheduled.toSet())
            assertEquals(expected.size, scheduled.size)
        }
    }

    @Test
    fun `progress line identifies the completed variant and evidence state`() {
        assertEquals(
            "tier-e-matched-benchmark: progress=8/45 fork=3 " +
                "variant=dictionary_full_stream_publication_terminal " +
                "workload_correctness=PASS whole_session_evidence=COMPLETE",
            TierEMatchedBenchmark.progressLine(
                completed = 8,
                total = 45,
                fork = 3,
                variant = "dictionary_full_stream_publication_terminal",
                workloadCorrectness = TierEBenchmarkContract.CorrectnessState.PASS,
                wholeSessionEvidence = TierEBenchmarkContract.EvidenceState.COMPLETE,
            ),
        )
    }

    @Test
    fun `targeted benchmark selector keeps only named generated variants and rejects typos`() {
        val available = listOf("baseline", "dictionary_full_stream_collector_continuous", "dictionary_unique_edge_collector_continuous")

        assertEquals(
            listOf("dictionary_unique_edge_collector_continuous"),
            TierEMatchedBenchmark.selectVariantNames(available, "dictionary_unique_edge_collector_continuous"),
        )
        assertEquals(available, TierEMatchedBenchmark.selectVariantNames(available, "  "))
        assertFailsWith<IllegalArgumentException> {
            TierEMatchedBenchmark.selectVariantNames(available, "dictionary_unique_edge_typo")
        }
    }

    @Test
    fun `legacy byte decoding remains the session default outside the benchmark experiment`() {
        assertEquals(TierERingDecodeMode.LEGACY_BYTES, TierEOptions().ringDecodeMode)
    }

    @Test
    fun `default full capture profile remains an omitted native agent option`() {
        assertEquals("", TierEKernelSmoke.captureProfileOption(TierECaptureProfile.Full))
        assertEquals(
            ",capture-profile=jdk25-unix-openat0",
            TierEKernelSmoke.captureProfileOption(TierECaptureProfile.fromProperty("jdk25-unix-openat0")),
        )
    }

    @Test
    fun `native marker transition phases are retained in benchmark evidence`() {
        val phases = TierEMatchedBenchmark.phaseMetrics(
            "tier-e-benchmark: raw_hash_ns=19 raw_equality_ns=23 generation_validation_ns=31 " +
                "guard_validation_ns=37 canonical_cache_lock_wait_ns=41 marker_set_ns=17 marker_clear_ns=29 marker_admission_clear_ns=3 " +
                "marker_exact_set_ns=5 marker_exit_clear_ns=7 marker_admission_clear_count=11 " +
                "marker_exact_set_count=13 marker_exit_clear_count=17",
        )

        assertEquals(17, phases.markerSetNanos)
        assertEquals(29, phases.markerClearNanos)
        assertEquals(19, phases.rawHashNanos)
        assertEquals(23, phases.rawEqualityNanos)
        assertEquals(31, phases.generationValidationNanos)
        assertEquals(37, phases.guardValidationNanos)
        assertEquals(41, phases.canonicalCacheLockWaitNanos)
        assertEquals(3, phases.markerAdmissionClearNanos)
        assertEquals(5, phases.markerExactSetNanos)
        assertEquals(7, phases.markerExitClearNanos)
        assertEquals(11, phases.markerAdmissionClearCount)
        assertEquals(13, phases.markerExactSetCount)
        assertEquals(17, phases.markerExitClearCount)
    }

    @Test
    fun `owned ring drain counters are retained in benchmark evidence`() {
        val phases = TierEMatchedBenchmark.phaseMetrics(
            "tier-e-kernel-smoke: ring_records_copied=3 ring_bytes_copied=264 " +
                "ring_busy_records=1 ring_discarded_records=2 ring_scan_passes=4 ring_batches=3 ring_max_batch_records=2 " +
                "kernel_ring_loss=5 retained_result_drops=7 pending_observation_budget_drops=11 pending_observation_queue_drops=13",
        )

        assertEquals(3, phases.ringRecordsCopied)
        assertEquals(264, phases.ringBytesCopied)
        assertEquals(1, phases.ringBusyRecords)
        assertEquals(2, phases.ringDiscardedRecords)
        assertEquals(4, phases.ringScanPasses)
        assertEquals(3, phases.ringBatches)
        assertEquals(2, phases.ringMaxBatchRecords)
        assertEquals(5, phases.kernelRingLosses)
        assertEquals(7, phases.retainedResultDrops)
        assertEquals(11, phases.pendingObservationBudgetDrops)
        assertEquals(13, phases.pendingObservationQueueDrops)
    }

    @Test
    fun `benchmark retained record limit must be positive`() {
        val property = "mazewall.tier-e.benchmark-max-retained-records"
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "250000")
            assertEquals(250000, TierEMatchedBenchmark.configuredBenchmarkMaxRetainedRecords())
            System.setProperty(property, "0")
            assertFailsWith<IllegalArgumentException> {
                TierEMatchedBenchmark.configuredBenchmarkMaxRetainedRecords()
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test
    fun `operation oracle omissions make otherwise complete evidence incomplete`() {
        assertFalse(
            TierEMatchedBenchmark.operationOracleSupportsCompleteEvidence(
                TierEBenchmarkContract.OperationOracle(
                    available = true,
                    expected = 10,
                    matched = 9,
                    missing = 1,
                ),
            ),
        )
        assertTrue(
            TierEMatchedBenchmark.operationOracleSupportsCompleteEvidence(
                TierEBenchmarkContract.OperationOracle(available = true, expected = 10, matched = 10),
            ),
        )
    }

    @Test
    fun `calibrated receipt age bounds are retained in benchmark evidence`() {
        val phases = TierEMatchedBenchmark.phaseMetrics(
            "tier-e-kernel-smoke: collector_receipt_samples=7 collector_receipt_min_ns=3 " +
                "collector_receipt_mean_ns=5 collector_receipt_max_ns=9 collector_receipt_p50_upper_ns=7 " +
                "collector_receipt_p95_upper_ns=15 collector_receipt_p99_upper_ns=15",
        )

        assertEquals(7, phases.collectorReceiptSamples)
        assertEquals(3, phases.collectorReceiptMinNanos)
        assertEquals(5, phases.collectorReceiptMeanNanos)
        assertEquals(9, phases.collectorReceiptMaxNanos)
        assertEquals(7, phases.collectorReceiptP50UpperNanos)
        assertEquals(15, phases.collectorReceiptP95UpperNanos)
        assertEquals(15, phases.collectorReceiptP99UpperNanos)
    }

    @Test
    fun `full stream exact joins produce valid capture efficiency evidence`() {
        val metrics = TierEMatchedBenchmark.captureEfficiency(
            executedOperations = 1_300,
            exactCaptures = 7_815,
            resolvedSyscalls = 1_300,
            productiveCaptures = 1_300,
            getStackTraceNanos = 25_000_000,
            fullStream = true,
            exactJoinComplete = true,
            evidenceComplete = true,
            observedLosses = 0,
            attributionFailures = 0,
        )

        assertEquals(1_300, metrics.executedOperations)
        assertEquals(7_815, metrics.exactCaptures)
        assertEquals(1_300, metrics.productiveCaptures)
        assertEquals(true, metrics.valid)
    }

    @Test
    fun `capture yield bindings retain exact usefulness counters`() {
        val bindings = TierEMatchedBenchmark.captureYieldBindings(
            "capture_yield_bindings=2:4:4:0:0:2:3:2",
        )

        val binding = bindings.getValue(2)
        assertEquals(4, binding.records)
        assertEquals(2, binding.resolvedInvocations)
        assertEquals(3, binding.resolvedSyscalls)
        assertEquals(2, binding.capturedWithoutResolvedSyscall)
    }

    @Test
    fun `profile exclusions do not invalidate joined admitted captures`() {
        assertEquals(
            true,
            TierEMatchedBenchmark.admittedCapturesJoined(
                captured = 400,
                captureFailed = 0,
                admissionFailed = 0,
                exactJoined = 400,
                outcomeMismatch = 0,
                unrecordedDefinitions = 0,
            ),
        )
        assertEquals(
            false,
            TierEMatchedBenchmark.admittedCapturesJoined(
                captured = 400,
                captureFailed = 0,
                admissionFailed = 0,
                exactJoined = 399,
                outcomeMismatch = 0,
                unrecordedDefinitions = 1,
            ),
        )
    }

    @Test
    fun `capture profile pair requires complete correct useful evidence on both arms`() {
        assertEquals(
            emptyList(),
            TierEMatchedBenchmark.captureProfilePairExclusionReasons(
                fullWorkloadCorrect = true,
                fullEvidenceComplete = true,
                fullCaptureEfficiencyValid = true,
                fullSemanticStackComplete = true,
                narrowedWorkloadCorrect = true,
                narrowedEvidenceComplete = true,
                narrowedCaptureEfficiencyValid = true,
                narrowedSemanticStackComplete = true,
                semanticStacksEquivalent = true,
            ),
        )
        assertEquals(
            listOf("narrowed_capture_efficiency"),
            TierEMatchedBenchmark.captureProfilePairExclusionReasons(
                fullWorkloadCorrect = true,
                fullEvidenceComplete = true,
                fullCaptureEfficiencyValid = true,
                fullSemanticStackComplete = true,
                narrowedWorkloadCorrect = true,
                narrowedEvidenceComplete = true,
                narrowedCaptureEfficiencyValid = false,
                narrowedSemanticStackComplete = true,
                semanticStacksEquivalent = true,
            ),
        )
        assertEquals(
            listOf("logical_stack_content_mismatch"),
            TierEMatchedBenchmark.captureProfilePairExclusionReasons(
                fullWorkloadCorrect = true,
                fullEvidenceComplete = true,
                fullCaptureEfficiencyValid = true,
                fullSemanticStackComplete = true,
                narrowedWorkloadCorrect = true,
                narrowedEvidenceComplete = true,
                narrowedCaptureEfficiencyValid = true,
                narrowedSemanticStackComplete = true,
                semanticStacksEquivalent = false,
            ),
        )
    }

    @Test
    fun `capture-profile semantic digest normalizes JVM-local loader and location details`() {
        fun definition(
            id: Long,
            loader: String,
            location: Long,
        ) = StackDefinition(
            StackTraceId(id),
            listOf(
                ManagedFrame(loader, "io.mazewall.profiler.tierE.stress.Workload", "run", "()V", location, ManagedFrameKind.JAVA),
                ManagedFrame("bootstrap", "java.nio.file.Files", "readAllBytes", "(Ljava/nio/file/Path;)[B", 88, ManagedFrameKind.JAVA),
            ),
            StackCaptureQuality.COMPLETE,
        )

        assertEquals(
            TierEKernelSmoke.semanticStackDefinitionDigest(listOf(definition(1, "loader-A", 1))),
            TierEKernelSmoke.semanticStackDefinitionDigest(listOf(definition(2, "loader-B", 999))),
        )
    }

    @Test
    fun `verified baseline receives an admissible performance label`() {
        assertEquals(
            "BASELINE_VERIFIED",
            TierEMatchedBenchmark.performanceEvidence(
                TierEBenchmarkContract.CorrectnessState.PASS,
                TierEBenchmarkContract.EvidenceState.NOT_APPLICABLE,
            ),
        )
    }

    @Test
    fun `every retained benchmark record is explicitly policy ineligible`() {
        assertEquals(
            "{\"kind\":\"fork-output\",\"policy_eligibility\":false}",
            TierEMatchedBenchmark.withPolicyIneligibility("{\"kind\":\"fork-output\"}"),
        )
    }

    @Test
    fun `paired full matrix intervals retain candidate minus baseline direction`() {
        val interval = TierEMatchedBenchmark.pairedMedianInterval(
            listOf(100L to 107L, 200L to 207L, 300L to 307L),
        )

        assertEquals(7L, interval.medianDeltaNanos)
        assertEquals(7L, interval.lowNanos)
        assertEquals(7L, interval.highNanos)
        assertEquals(
            emptyList(),
            TierEMatchedBenchmark.fullMatrixPairExclusionReasons(
                baselineWorkloadCorrect = true,
                candidateWorkloadCorrect = true,
                candidateEvidenceComplete = true,
            ),
        )
    }

    @Test
    fun `direct profiler contrasts retain their declared right minus left direction`() {
        assertEquals(
            listOf(
                "inline_minus_dictionary_full_stream",
                "inline_minus_dictionary_unique_edge",
                "unique_minus_full_dictionary",
                "unique_minus_full_inline",
                "user_notif_stack_minus_no_stack",
                "user_notif_no_stack_minus_dictionary_full_stream",
                "user_notif_stack_minus_dictionary_full_stream",
                "user_notif_no_stack_minus_dictionary_unique_edge",
                "user_notif_stack_minus_dictionary_unique_edge",
            ),
            TierEMatchedBenchmark.directComparisonNames(),
        )
        assertEquals(
            mapOf(
                "inline_minus_dictionary_full_stream" to ("FULL_STREAM" to "INLINE_FULL_STREAM"),
                "inline_minus_dictionary_unique_edge" to ("UNIQUE_EDGE" to "INLINE_UNIQUE_EDGE"),
                "unique_minus_full_dictionary" to ("FULL_STREAM" to "UNIQUE_EDGE"),
                "unique_minus_full_inline" to ("INLINE_FULL_STREAM" to "INLINE_UNIQUE_EDGE"),
                "user_notif_stack_minus_no_stack" to ("USER_NOTIF_NO_STACK" to "USER_NOTIF"),
                "user_notif_no_stack_minus_dictionary_full_stream" to ("FULL_STREAM" to "USER_NOTIF_NO_STACK"),
                "user_notif_stack_minus_dictionary_full_stream" to ("FULL_STREAM" to "USER_NOTIF"),
                "user_notif_no_stack_minus_dictionary_unique_edge" to ("UNIQUE_EDGE" to "USER_NOTIF_NO_STACK"),
                "user_notif_stack_minus_dictionary_unique_edge" to ("UNIQUE_EDGE" to "USER_NOTIF"),
            ),
            TierEMatchedBenchmark.directComparisonModePairs(),
        )
        val interval = TierEMatchedBenchmark.directPairedEffect(
            leftByFork = mapOf(1 to 100L, 2 to 200L, 3 to 300L),
            rightByFork = mapOf(1 to 107L, 2 to 207L, 3 to 307L),
        )

        assertEquals(7L, interval.medianDeltaNanos)
        assertEquals(7L, interval.lowNanos)
        assertEquals(7L, interval.highNanos)
    }

    @Test
    fun `fork timing separates measured workload from lifecycle overhead`() {
        val timing = TierEMatchedBenchmark.timingAccounting(
            elapsedNanos = 1_000L,
            workloadDurationsNanos = listOf(250L, 350L),
        )

        assertEquals(600L, timing.workloadNanos)
        assertEquals(400L, timing.lifecycleOverheadNanos)
    }

    @Test
    fun `rate accounting reports matched per-operation overhead without whole-app extrapolation`() {
        assertEquals(1_000.0, TierEMatchedBenchmark.operationsPerSecond(100, 100_000_000))
        assertEquals(25.0, TierEMatchedBenchmark.addedNanosPerProfiledOperation(12_500, 10_000, 100))
        assertEquals(0.025, TierEMatchedBenchmark.estimatedSingleCoreFraction(1_000_000.0, 25.0))
        assertEquals(-0.025, TierEMatchedBenchmark.estimatedSingleCoreFraction(1_000_000.0, -25.0))
    }

    @Test
    fun `artifact escaping keeps child control output valid JSON`() {
        assertEquals(
            "failure\\u0000\\t\\n\\r\\b\\f\\\"\\\\",
            TierEMatchedBenchmark.escape("failure\u0000\t\n\r\b\u000c\"\\"),
        )
    }
}
