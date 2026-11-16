@file:JvmName("TierEKotlinDaemonMain")

package io.mazewall.profiler.tierE.daemon

import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.profiler.internal.attribution.AgentDefinitionFile
import io.mazewall.profiler.internal.attribution.CoverageRecoveryMode
import io.mazewall.profiler.internal.attribution.EvidenceIntegrity
import io.mazewall.profiler.internal.attribution.MarkerVersion
import io.mazewall.profiler.internal.attribution.SessionEpoch
import io.mazewall.profiler.internal.attribution.TierECollectorRuntime
import io.mazewall.profiler.internal.attribution.TierECollectorRuntimeResult
import io.mazewall.profiler.internal.attribution.TierECollectorServiceMetrics
import io.mazewall.profiler.internal.attribution.TierELinuxMonotonicClock
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierEProducerClosure
import io.mazewall.profiler.internal.attribution.TierEProfilerSession
import io.mazewall.profiler.internal.attribution.TierESysEnterAttachmentBackend
import io.mazewall.profiler.tierE.engine.BoundaryDiagnosticConfig
import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import io.mazewall.profiler.tierE.engine.publishBoundaryControlReceipt
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Pure-Kotlin Tier E daemon.
 * Listens on a unix domain socket (abstract namespace), accepts ATTACH/DETACH/SET_CONTEXT
 * commands from privileged clients, and drives [TierEbpfEngine] directly.
 *
 * The native JVMTI agent supplies the marker uprobe and logical stack dictionary.
 */
internal fun main() {
    if (!runningAsRoot()) {
        System.err.println("[tier-e] refusing: requires root")
        exitProcess(1)
    }

    val evidenceAccess = TierEEvidenceFileAccess.fromEnvironment()
    val socketPath = System.getenv("TIER_E_SOCKET") ?: "/tmp/tier_e.sock"
    Files.deleteIfExists(Path.of(socketPath))
    val socketAccess = TierEControlSocketAccess.fromEnvironment()

    val stopRequested = AtomicBoolean(false)
    val registeredTarget = System
        .getenv("TIER_E_TARGET_REGISTRATION")
        ?.let { TierETargetRegistrationFile.read(Path.of(it)) }
    val registeredTargetResolver = registeredTarget?.let { target -> TierETargetRegistrationResolver(mapOf(target.registrationId to target)) }
    val allTargetCapture = System.getenv("TIER_E_ALL_TARGET_CAPTURE")?.let { selected ->
        require(selected == "1") { "TIER_E_ALL_TARGET_CAPTURE accepts only 1 when explicitly enabled" }
        true
    } ?: false
    val boundaryDiagnostics = boundaryDiagnosticsFromEnvironment(System.getenv(), registeredTarget != null)
    val fileReportSink = System.getenv("TIER_E_EVIDENCE_ROOT")?.let {
        TierETerminalReportFileSink(Path.of(it), evidenceAccess)
    }
    val eventFileSink = if (allTargetCapture) {
        val root = requireNotNull(System.getenv("TIER_E_EVIDENCE_ROOT")) { "all-target capture requires an evidence root" }
        TierEEventJsonlFileSink(Path.of(root), access = evidenceAccess)
    } else {
        null
    }
    val coverageManifestSink = System
        .getenv("TIER_E_COVERAGE_MANIFEST")
        ?.takeIf { it == "1" }
        ?.let {
            val root = requireNotNull(System.getenv("TIER_E_EVIDENCE_ROOT")) {
                "TIER_E_COVERAGE_MANIFEST requires TIER_E_EVIDENCE_ROOT"
            }
            val oracle = requireNotNull(System.getenv("TIER_E_COVERAGE_ORACLE_SHA256")) {
                "TIER_E_COVERAGE_MANIFEST requires TIER_E_COVERAGE_ORACLE_SHA256"
            }
            val syscallNumber = requireNotNull(System.getenv("TIER_E_COVERAGE_SYSCALL_NUMBER")) {
                "TIER_E_COVERAGE_MANIFEST requires TIER_E_COVERAGE_SYSCALL_NUMBER"
            }.toIntOrNull()?.takeIf { it >= 0 } ?: error("TIER_E_COVERAGE_SYSCALL_NUMBER must be non-negative")
            TierECoverageManifestConfiguration(TierECoverageManifestFileSink(Path.of(root)), oracle, syscallNumber)
        }
    val operationManifestSink = if (System.getenv("TIER_E_SPLIT_BENCHMARK") == "1") {
        TierESplitOperationManifestFileSink(
            Path.of(
                requireNotNull(System.getenv("TIER_E_EVIDENCE_ROOT")) {
                "split benchmark requires TIER_E_EVIDENCE_ROOT"
            },
            ),
        )
    } else {
        null
    }
    val benchmarkRecordLimit = System
        .getenv("TIER_E_BENCHMARK_RECORD_LIMIT")
        ?.let { raw ->
            require(coverageManifestSink != null || System.getenv("TIER_E_SPLIT_BENCHMARK") == "1") {
                "TIER_E_BENCHMARK_RECORD_LIMIT requires a benchmark fixture"
            }
            raw.toIntOrNull()?.takeIf { it in TierEOptions.DEFAULT_RECORD_LIMIT..250_000 }
                ?: error("TIER_E_BENCHMARK_RECORD_LIMIT must be 100000..250000")
        }
    val artifactWaitMillis = System
        .getenv("TIER_E_TERMINAL_ARTIFACT_WAIT_MILLIS")
        ?.toLongOrNull()
        ?.also { require(it in 0..30_000L) { "TIER_E_TERMINAL_ARTIFACT_WAIT_MILLIS must be 0..30000" } }
        ?: 30_000L
    val daemon = TierEDaemonService(
        stopRequested,
        engineFactory = { TierEbpfEngine(RealNativeEngine, boundaryDiagnostics) },
        processIdentityVerifier = System.getenv("TIER_E_BENCHMARK_REUSE_MARKER")?.let { marker ->
            val path = Path.of(marker)
            TierEProcessIdentityVerifier { pid ->
                TierEProcessIdentityVerifier.observeLinuxIncarnation(pid)?.let { observed ->
                    if (Files.exists(path)) observed + 1 else observed
                }
            }
        } ?: TierEProcessIdentityVerifier.linux,
        registeredTargetResolver = registeredTargetResolver,
        resultSink = TierEDaemonResultSink { result ->
            if (eventFileSink != null) {
                eventFileSink.publish(requireNotNull(registeredTarget), result.profile, result.terminalReason)
            }
        },
        terminalReportSink = TierETerminalReportSink { report ->
            fileReportSink?.accept(report)
            System.out.println(
                "[tier-e] terminal evidence_integrity=${report.evidenceIntegrity} " +
                    "terminal_reason=${report.terminalReason} resolved_syscalls=${report.resolvedSyscalls} " +
                    "capture_domain_validity=${report.captureDomainValidity} " +
                    "transport_completeness=${report.transportCompleteness} " +
                    "attribution_coverage=${report.attributionCoverage} " +
                    "dictionary_integrity=${report.dictionaryIntegrity} " +
                    "lifecycle_validity=${report.lifecycleValidity} " +
                    "policy_eligibility=${report.policyEligibility}",
            )
        },
        terminalArtifactWaitMillis = artifactWaitMillis,
        coverageManifestSink = coverageManifestSink,
        operationManifestSink = operationManifestSink,
        benchmarkRecordLimit = benchmarkRecordLimit,
        benchmarkMode = System.getenv("TIER_E_SPLIT_BENCHMARK") == "1",
        allTargetCapture = allTargetCapture,
    )
    ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
        server.bind(UnixDomainSocketAddress.of(socketPath))
        socketAccess.apply(Path.of(socketPath))
        Runtime.getRuntime().addShutdownHook(
            Thread {
                stopRequested.set(true)
                runCatching { server.close() }
                runCatching { daemon.shutdown() }
                runCatching { Files.deleteIfExists(Path.of(socketPath)) }
            },
        )

        System.out.println("[tier-e] listening socket=$socketPath")
        val executeCommand: (String) -> String = { command ->
            daemon.executeCommand(command).also {
                // SHUTDOWN is acknowledged on the current client before the
                // accept loop is released.  Without closing this descriptor,
                // an idle accept can keep the collector alive after its
                // terminal barrier has completed.
                if (stopRequested.get()) runCatching { server.close() }
            }
        }

        while (!stopRequested.get()) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            thread(name = "tier-e-client") {
                try {
                    TierEClientSession.run(client, stopRequested, executeCommand)
                } catch (expectedClientDisconnect: Exception) {
                    // Hard socket errors still end the session; idle timeouts are
                    // handled inside TierEClientSession and continue polling.
                }
            }
        }
    }
}

internal fun boundaryDiagnosticsFromEnvironment(
    environment: Map<String, String>,
    registered: Boolean,
): BoundaryDiagnosticConfig? {
    val selected = environment["TIER_E_TEST_BOUNDARY_DIAGNOSTICS"]
    if (selected.isNullOrEmpty()) return null
    require(selected == "1") { "TIER_E_TEST_BOUNDARY_DIAGNOSTICS accepts only 1" }
    require(
        registered &&
        environment["TIER_E_ALL_TARGET_CAPTURE"] == "1" &&
        markerVersionFromEnvironment(environment) == MarkerVersion.V1 &&
        coverageRecoveryModeFromEnvironment(environment) == CoverageRecoveryMode.DISABLED &&
        environment["TIER_E_BENCHMARK_PHASES"] != "1" &&
        environment["TIER_E_SPLIT_BENCHMARK"] != "1",
    ) {
        "boundary diagnostics require a registered non-benchmark direct-raw v1 all-target session"
    }
    return BoundaryDiagnosticConfig(Path.of("/fixture/evidence/boundary.bpfdiag"))
}

internal class TierEDaemonService(
    private val stopRequested: AtomicBoolean,
    private val engineFactory: () -> TierEbpfEngine = { TierEbpfEngine(RealNativeEngine) },
    private val artifactResolver: TierEDaemonArtifactResolver? =
        System.getenv("TIER_E_DEFINITION_ROOT")?.let { TierEDaemonArtifactResolver(Path.of(it)) },
    private val resultSink: TierEDaemonResultSink = TierEDaemonResultSink { },
    private val terminalReportSink: TierETerminalReportSink = TierETerminalReportSink { },
    private val registeredTargetResolver: TierETargetRegistrationResolver? = null,
    private val processIdentityVerifier: TierEProcessIdentityVerifier = TierEProcessIdentityVerifier.linux,
    private val targetExitCoordinator: TierETargetExitCoordinator = TierETargetExitCoordinator(processIdentityVerifier),
    private val terminalArtifactWaitMillis: Long = 30_000L,
    private val coverageManifestSink: TierECoverageManifestConfiguration? = null,
    private val operationManifestSink: TierESplitOperationManifestFileSink? = null,
    private val benchmarkRecordLimit: Int? = null,
    private val benchmarkMode: Boolean = false,
    private val markerVersion: MarkerVersion = markerVersionFromEnvironment(),
    private val benchmarkPhases: Boolean = System.getenv("TIER_E_BENCHMARK_PHASES") == "1",
    private val receiptClockMode: String = receiptClockModeFromEnvironment(),
    private val recoveryMode: CoverageRecoveryMode = coverageRecoveryModeFromEnvironment(),
    private val recoveryMapCapacity: Int = recoveryMapCapacityFromEnvironment(),
    private val nativeLiveSnapshotAdmission: Boolean = nativeLiveSnapshotAdmissionFromEnvironment(),
    private val benchmarkDropRingSyscallNumber: Int? = benchmarkDropRingSyscallFromEnvironment(),
    private val allTargetCapture: Boolean = false,
    private val boundaryPublisher: (String) -> Unit = { publishBoundaryControlReceipt(it) },
    private val boundaryReporterFactory: (TierEbpfEngine, Long, (String) -> Unit) -> (() -> Unit) = { engine, installation, publish ->
        engine.boundaryControlReporter(installation, publish)
    },
    private val sessionFactory: (TierEbpfEngine, Long, TierEOptions, SessionEpoch) -> TierEProfilerSession = { engine, incarnation, options, epoch ->
        TierEProfilerSession(engine, incarnation, options, configuredSessionEpoch = epoch)
    },
) {
    private var sessionState: ControlSessionState = ControlSessionState.Idle
    private var activeSession: ActiveSession? = null

    // Reentrant shutdown closes admission now, but cannot retire a resource still under construction/use.
    private var acquisitionInProgress = false
    private var lastTerminalResult: TierECollectorRuntimeResult? = null
    private var lastFinalizationFailure: Throwable? = null
    private var activeMarkerTransport = "idle"
    private var activeMarkerFallbackReason: String? = null
    private var boundaryInstallationSequence = 0L

    init {
        require(registeredTargetResolver == null || artifactResolver != null) {
            "registered target deployment requires TIER_E_DEFINITION_ROOT"
        }
        require(
            !allTargetCapture ||
            (
                registeredTargetResolver != null &&
                artifactResolver != null &&
                !benchmarkMode &&
            !benchmarkPhases &&
                markerVersion == MarkerVersion.V1
            ),
        ) {
            "all-target capture requires a registered v1 target and non-benchmark definition artifact"
        }
        require(terminalArtifactWaitMillis in 0..30_000L) { "terminal artifact wait must be 0..30000ms" }
        require(
            benchmarkRecordLimit == null ||
            ((coverageManifestSink != null || benchmarkMode) && benchmarkRecordLimit in TierEOptions.DEFAULT_RECORD_LIMIT..250_000),
        ) {
            "expanded record limit is permitted only for a bounded benchmark"
        }
        require(receiptClockMode in setOf("disabled", "monotonic")) { "TIER_E_COLLECTOR_RECEIPT_CLOCK must be disabled or monotonic" }
        require(benchmarkMode || coverageManifestSink != null || receiptClockMode == "disabled") {
            "collector receipt clock is benchmark-only"
        }
        require(benchmarkMode || coverageManifestSink != null || recoveryMode == CoverageRecoveryMode.DISABLED) {
            "coverage recovery is benchmark-only"
        }
        require(
            !nativeLiveSnapshotAdmission ||
            ((benchmarkMode || coverageManifestSink != null) && recoveryMode != CoverageRecoveryMode.DISABLED && markerVersion == MarkerVersion.V2),
        ) { "native live snapshot admission requires an experimental v2 hybrid session" }
        require(benchmarkMode || coverageManifestSink != null || markerVersion == MarkerVersion.V1) {
            "marker v2 is benchmark-only"
        }
        require(benchmarkMode || coverageManifestSink != null || !benchmarkPhases) {
            "phase timing is benchmark-only"
        }
    }

    @Synchronized
    fun executeCommand(line: String): String =
        when (val parsed = parseControlCommand(line)) {
            is Either.Right -> {
                val transition = ControlSessionMachine.evaluate(
                    sessionState,
                    ControlSessionEvent.SyntaxError(parsed.value),
                )
                sessionState = transition.state
                (transition.effect as ControlSessionEffect.SendReply).reply.render().trimEnd()
            }

            is Either.Left -> {
                val transition = ControlSessionMachine.evaluate(
                    sessionState,
                    ControlSessionEvent.CommandReceived(parsed.value),
                )
                sessionState = transition.state
                dispatchEffect(transition.effect)
            }
        }

    private fun dispatchEffect(effect: ControlSessionEffect): String =
        when (effect) {
            is ControlSessionEffect.SendReply -> effect.reply.render().trimEnd()
            is ControlSessionEffect.RespondStatus -> statusReply()
            is ControlSessionEffect.PerformAttach -> performAttach(effect)
            is ControlSessionEffect.CloseSessionAndReply -> closeSessionCommand(effect)
            is ControlSessionEffect.CloseAndShutdown -> shutdownCommand()
        }

    private fun statusReply(): String {
        activeSession?.let(::observeBoundaryReadiness)?.let { return it }
        val statusText = activeSession?.let {
            "engine=active marker_transport=$activeMarkerTransport " +
                "marker_fallback=${activeMarkerFallbackReason ?: "none"} " +
                (it.runtime?.metrics()?.let(::collectorStatus) ?: "collector_state=unavailable")
        } ?: lastTerminalResult?.let { result ->
            "engine=idle terminal_transport_complete=${result.profile.integrity.verdictDimensions.transportComplete}"
        } ?: "engine=idle"
        val finalization = lastFinalizationFailure?.let { " terminal_reason=FINALIZATION_FAILED failure=${it.message}" }.orEmpty()
        return ControlReply.ok(statusText + finalization).render().trimEnd()
    }

    @Suppress("TooGenericExceptionCaught") // Observation failure must latch without bypassing normal terminal collection.
    private fun observeBoundaryReadiness(active: ActiveSession): String? = when {
        active.boundaryReporter != null && active.outcome != FinalizationOutcome.Collecting ->
            lastFinalizationFailure?.let(::finalizationError) ?: ControlReply.err("STATE").render().trimEnd()
        active.boundaryReadinessFailure != null -> boundaryReadinessError(requireNotNull(active.boundaryReadinessFailure))
        else -> try {
            active.boundaryReporter?.invoke()
            null
        } catch (interrupted: InterruptedException) {
            active.boundaryReadinessFailure = interrupted
            Thread.currentThread().interrupt()
            boundaryReadinessError(interrupted)
        } catch (cause: Throwable) {
            active.boundaryReadinessFailure = cause
            boundaryReadinessError(cause)
        }
    }

    private fun boundaryReadinessError(failure: Throwable): String {
        if (failure is Error) throw failure
        return ControlReply.err("BOUNDARY_DIAGNOSTICS_FAILED").render().trimEnd()
    }

    private fun collectorStatus(metrics: TierECollectorServiceMetrics): String =
        "collector_state=${metrics.state} worker_alive=${metrics.workerAlive} " +
            "polls=${metrics.loop.polls} active_polls=${metrics.loop.activePolls} " +
            "idle_polls=${metrics.loop.idlePolls} failure=${metrics.failure ?: "none"}"

    @Suppress("TooGenericExceptionCaught") // Acquisition failures, including fatal ones, must retain cleanup ownership.
    private fun performAttach(effect: ControlSessionEffect.PerformAttach): String {
        if (activeSession?.outcome != null && activeSession?.outcome != FinalizationOutcome.Collecting) {
            sessionState = effect.previousSession?.let(ControlSessionState::Attached) ?: ControlSessionState.Idle
            return finalizationError(requireNotNull(lastFinalizationFailure))
        }
        val previousFailure = if (activeSession != null) finishActiveSession() else null
        if (previousFailure != null) {
            if (sessionState != ControlSessionState.Terminated) {
                sessionState = activeSession?.let { ControlSessionState.Attached(it.session) } ?: ControlSessionState.Idle
            }
            return finalizationError(previousFailure)
        }
        val cmd = effect.command
        var attachmentAcquired = false
        return try {
            val attachment = try {
                acquisitionInProgress = true
                check(sessionState != ControlSessionState.Terminated) { "STATE" }
                val attachment = validatedAttachment(cmd)
                check(sessionState != ControlSessionState.Terminated) { "STATE" }
                val engine = engineFactory()
                attachmentAcquired = true
                lastFinalizationFailure = null
                val active = ActiveSession(null, engine, attachment.session, attachment.artifact, attachment.registeredTarget)
                activeSession = active
                if (engine.boundaryDiagnosticsEnabled) {
                    boundaryInstallationSequence = Math.incrementExact(boundaryInstallationSequence)
                    active.boundaryReporter = boundaryReporterFactory(engine, boundaryInstallationSequence, boundaryPublisher)
                }
                check(sessionState != ControlSessionState.Terminated) { "STATE" }
                val session = sessionFactory(engine, attachment.session.processIncarnation, sessionOptions(cmd), SessionEpoch(cmd.sessionTag.toLong()))
                active.resource = session
                check(sessionState != ControlSessionState.Terminated) { "STATE" }
                val runtime = TierECollectorRuntime(session, receiptClock = if (receiptClockMode == "monotonic") TierELinuxMonotonicClock() else null)
                active.runtime = runtime
                active.resource = runtime
                startAttachment(cmd, attachment, session, active)
                check(sessionState != ControlSessionState.Terminated) { "STATE" }
                attachment
            } finally {
                acquisitionInProgress = false
            }
            val postTransition = ControlSessionMachine.evaluate(sessionState, ControlSessionEvent.AttachSucceeded(attachment.session, cmd))
            sessionState = postTransition.state
            (postTransition.effect as ControlSessionEffect.SendReply).reply.render().trimEnd()
        } catch (versionFailure: TierEMarkerVersionMismatch) {
            val target = requireNotNull(registeredTargetResolver?.resolve(cmd)) { "marker-version mismatch requires a registered target" }
            reportRejectedAttach(target, "MARKER_VERSION_MISMATCH")
            completeAttachFailure(cmd, versionFailure)
        } catch (failure: Throwable) {
            handleAttachFailure(cmd, failure, attachmentAcquired)
        }
    }

    private fun validatedAttachment(cmd: ControlCommand.Attach): TierEDaemonAttachment {
        val attachment = resolveDaemonAttachment(cmd, registeredTargetResolver, artifactResolver)
        attachment.registeredTarget?.let(processIdentityVerifier::requireCurrent)
        if (markerVersion == MarkerVersion.V2) {
            val target = requireNotNull(attachment.registeredTarget) { "experimental marker v2 requires registered target and selected-version proof" }
            try {
                requireNotNull(artifactResolver).readMarkerProof(target).requireSelectedV2(target)
            } catch (failure: IllegalArgumentException) {
                throw TierEMarkerVersionMismatch(failure)
            }
            processIdentityVerifier.requireCurrent(target)
        }
        return attachment
    }

    private fun sessionOptions(cmd: ControlCommand.Attach): TierEOptions =
        TierEOptions(
        cmd.emissionMode,
        maxPendingRecords = benchmarkRecordLimit ?: TierEOptions.DEFAULT_RECORD_LIMIT,
        maxRetainedRecords = benchmarkRecordLimit ?: TierEOptions.DEFAULT_RECORD_LIMIT,
        benchmarkPhases = benchmarkPhases,
        experimentalCoverage = markerVersion == MarkerVersion.V2,
        markerVersion = markerVersion,
        coverageRecoveryMode = recoveryMode,
        coverageRecoveryMapCapacity = recoveryMapCapacity,
        benchmarkDropRingSyscallNumber = benchmarkDropRingSyscallNumber,
        allTargetCapture = allTargetCapture,
        sysEnterAttachmentBackend = if (allTargetCapture) TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT else TierESysEnterAttachmentBackend.AUTO,
    )

    private fun startAttachment(
        cmd: ControlCommand.Attach,
        attachment: TierEDaemonAttachment,
        session: TierEProfilerSession,
        active: ActiveSession,
    ) {
        val selection = if (markerVersion == MarkerVersion.V2) {
            selectMarkerTransport(cmd.mode) { UsdtProbeNotes.findStackMarkerV2(attachment.markerPath) }
        } else {
            selectMarkerTransport(cmd.mode) { UsdtProbeNotes.findStackMarkerV1(attachment.markerPath) }
        }
        session.installAndAttach(attachment.session.pid, cmd.sessionTag, attachment.markerPath, attachment.markerOffset, selection.usdtNote)
        check(sessionState != ControlSessionState.Terminated) { "STATE" }
        if (nativeLiveSnapshotAdmission) {
            val artifact = requireNotNull(attachment.artifact) { "native live snapshot admission requires registered artifact" }
            val resolver = requireNotNull(artifactResolver) { "native live snapshot admission requires artifact root" }
            session.enableNativeLiveSnapshots { maxBytes -> resolver.readLive(artifact, maxBytes) }
        }
        requireNotNull(active.runtime).start()
        check(sessionState != ControlSessionState.Terminated) { "STATE" }
        if (allTargetCapture) session.enableCaptureAdmission()
        attachment.registeredTarget?.let { target ->
            active.terminalWatch = targetExitCoordinator.watch(target) { finalizeRegisteredTarget(target) }
        }
        activeMarkerTransport = selection.selected.name.lowercase()
        activeMarkerFallbackReason = selection.fallbackReason
    }

    @Suppress("TooGenericExceptionCaught") // Rejected-report failures must remain observable even without an acquired owner.
    private fun handleAttachFailure(
        cmd: ControlCommand.Attach,
        cause: Throwable,
        attachmentAcquired: Boolean,
    ): String {
        var failure = activeSession?.let { active ->
            recordFinalizationFailure(active, cause)
            finishActiveSession()
        } ?: cause
        if (sessionState == ControlSessionState.Terminated) stopRequested.set(activeSession == null)
        val target = runCatching { registeredTargetResolver?.resolve(cmd) }.getOrNull()
        if (!attachmentAcquired && target != null && processIdentityVerifier.checkAtTerminal(target) != TierEProcessIdentityCheck.MATCHED) {
            try {
                reportRejectedAttach(target, "STALE_PROCESS_IDENTITY")
            } catch (publicationFailure: Throwable) {
                failure = recordFinalizationFailure(null, publicationFailure, failure)
            }
        }
        if (failure is Error) throw failure
        return completeAttachFailure(cmd, failure)
    }

    private fun closeSessionCommand(effect: ControlSessionEffect.CloseSessionAndReply): String {
        val failure = finishActiveSession()
        if (failure == null) return effect.reply.render().trimEnd()
        if (sessionState != ControlSessionState.Terminated) sessionState = ControlSessionState.Attached(effect.session)
        return finalizationError(failure)
    }

    private fun shutdownCommand(): String {
        val failure = finishActiveSession()
        stopRequested.set(activeSession == null && !acquisitionInProgress)
        return if (failure == null) ControlReply.ok("shutting down").render().trimEnd() else finalizationError(failure)
    }

    private fun completeAttachFailure(
        cmd: ControlCommand.Attach,
        failure: Throwable,
    ): String {
        val transition = ControlSessionMachine.evaluate(
            sessionState,
            ControlSessionEvent.AttachFailed(failure.message ?: "attach failed", cmd),
        )
        sessionState = transition.state
        if (sessionState != ControlSessionState.Terminated) {
            activeSession?.let { sessionState = ControlSessionState.Attached(it.session) }
        }
        return (transition.effect as ControlSessionEffect.SendReply).reply.render().trimEnd()
    }

    private fun reportRejectedAttach(
        target: TierERegisteredTarget,
        reason: String,
    ) {
        terminalReportSink.accept(
            TierETerminalReport(
                evidenceIntegrity = EvidenceIntegrity.INCOMPLETE,
                terminalReason = reason,
                resolvedSyscalls = 0,
                captureDomainValidity = false,
                transportCompleteness = false,
                attributionCoverage = false,
                dictionaryIntegrity = false,
                lifecycleValidity = false,
                registrationId = target.registrationId,
                sessionEpoch = target.sessionEpoch.value,
                targetPid = target.pid,
                processIncarnation = target.processIncarnation,
            ),
        )
    }

    @Synchronized
    fun shutdown() {
        sessionState = ControlSessionMachine.evaluate(sessionState, ControlSessionEvent.CommandReceived(ControlCommand.Shutdown)).state
        val failure = finishActiveSession()
        stopRequested.set(activeSession == null && !acquisitionInProgress)
        failure?.let { throw it }
    }

    @Synchronized
    private fun finalizeRegisteredTarget(target: TierERegisteredTarget) {
        if (activeSession?.registeredTarget != target) return
        val failure = finishActiveSession(waitForTerminalArtifact = true)
        if (sessionState != ControlSessionState.Terminated) {
            sessionState = activeSession?.let { ControlSessionState.Attached(it.session) } ?: ControlSessionState.Idle
        }
        if (failure is Error) throw failure
    }

    @Suppress("TooGenericExceptionCaught") // Keep ownership through failed finalization and attempt independent retirement.
    private fun finishActiveSession(waitForTerminalArtifact: Boolean = false): Throwable? {
        if (acquisitionInProgress) return IllegalStateException("attachment acquisition still in progress; retirement pending")
        val active = activeSession ?: return lastFinalizationFailure
        var finalized: Pair<TierECollectorRuntimeResult?, AgentDefinitionFile.Contents?>? = null
        if (active.outcome == FinalizationOutcome.Collecting) {
            try {
                finalized = finalizeCollection(active, waitForTerminalArtifact)
                active.outcome = FinalizationOutcome.Completed
            } catch (cause: Throwable) {
                recordFinalizationFailure(active, cause, active.boundaryReadinessFailure)
            }
            if (active.outcome == FinalizationOutcome.Completed) {
                active.boundaryReadinessFailure?.let { recordFinalizationFailure(active, it) }
            }
        }
        var interrupted = Thread.interrupted()
        try {
            retireWatch(active)
            interrupted = Thread.interrupted() || interrupted
            retireResource(active)
            interrupted = Thread.interrupted() || interrupted
            publishTerminal(active, finalized)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        if (active.retired && active.terminalWatch == null) activeSession = null
        return (active.outcome as? FinalizationOutcome.Failed)?.cause
    }

    @Suppress("TooGenericExceptionCaught") // Watch retirement must not prevent independent resource retirement.
    private fun retireWatch(active: ActiveSession) {
        try {
            active.terminalWatch?.close()
            active.terminalWatch = null
        } catch (cause: Throwable) {
            recordFinalizationFailure(active, cause)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Keep unsuccessful physical retirement reachable for retry.
    private fun retireResource(active: ActiveSession) {
        if (active.retired) return
        try {
            active.resource.close()
            active.retired = true
        } catch (cause: Throwable) {
            recordFinalizationFailure(active, cause)
        }
    }

    @Suppress("TooGenericExceptionCaught") // A publication attempt is consumed even when its sink throws.
    private fun publishTerminal(active: ActiveSession, finalized: Pair<TierECollectorRuntimeResult?, AgentDefinitionFile.Contents?>?) {
        if (active.reportAttempted) return
        active.reportAttempted = true
        try {
            when (active.outcome) {
                FinalizationOutcome.Collecting -> error("collection has not terminated")
                FinalizationOutcome.Completed -> finalized?.first?.let {
                    lastTerminalResult = deliverResult(it, active, finalized.second)
                }
                is FinalizationOutcome.Failed -> active.registeredTarget?.let { reportRejectedAttach(it, "FINALIZATION_FAILED") }
            }
        } catch (cause: Throwable) {
            recordFinalizationFailure(active, cause)
        }
    }

    private fun finalizeCollection(
        active: ActiveSession,
        waitForTerminalArtifact: Boolean,
    ): Pair<TierECollectorRuntimeResult?, AgentDefinitionFile.Contents?> {
        val artifact = active.artifact
        if (artifact == null) {
            return null to null
        }
        val identityCheck = active.registeredTarget?.let(processIdentityVerifier::checkAtTerminal)
        val terminalContents = if (active.registeredTarget != null && identityCheck != TierEProcessIdentityCheck.REUSED) {
            readTerminalContents(artifact, waitForTerminalArtifact)
        } else {
            null
        }
        val producerClosure = if (terminalContents == null) TierEProducerClosure.UNPROVEN else TierEProducerClosure.PROVEN
        val runtime = requireNotNull(active.runtime)
        val result =
            if (active.registeredTarget != null) {
                runtime.finish(
                    terminalContents ?: AgentDefinitionFile.Contents(emptyList(), emptyList(), 0),
                    producerClosure,
                )
            } else {
                runtime.finish(artifact, producerClosure)
            }
            val admitted = when {
                identityCheck == TierEProcessIdentityCheck.REUSED -> result.withReusedProcessIdentity()
                active.registeredTarget != null && terminalContents == null -> result.withUnavailableArtifact()
                else -> result
            }
        return admitted to terminalContents
    }

    private fun recordFinalizationFailure(
        active: ActiveSession?,
        cause: Throwable,
        previous: Throwable? = (active?.outcome as? FinalizationOutcome.Failed)?.cause,
    ): Throwable {
        val failure = CleanupFailures.merge(previous, cause)
        if (active != null) active.outcome = FinalizationOutcome.Failed(failure)
        lastFinalizationFailure = failure
        if (cause is InterruptedException) Thread.currentThread().interrupt()
        System.err.println("tier-e-finalization-failure: ${cause.javaClass.simpleName}: ${cause.message}")
        return failure
    }

    private fun finalizationError(failure: Throwable): String {
        if (failure is Error) throw failure
        return ControlReply.err("FINALIZATION_FAILED: ${failure.message}").render().trimEnd()
    }

    private fun readTerminalContents(
        artifact: io.mazewall.profiler.internal.attribution.TierEDefinitionArtifact,
        waitForTerminalArtifact: Boolean,
    ): AgentDefinitionFile.Contents? {
        val deadline = System.nanoTime() + if (waitForTerminalArtifact) terminalArtifactWaitMillis * 1_000_000L else 0L
        do {
            runCatching { requireNotNull(artifactResolver).readTerminal(artifact) }.getOrNull()?.let { return it }
            if (!waitForTerminalArtifact || System.nanoTime() >= deadline) return null
            Thread.sleep(100L)
        } while (true)
    }

    @Suppress("TooGenericExceptionCaught") // Retain event failure identity while still attempting the required terminal report once.
    private fun deliverResult(
        result: TierECollectorRuntimeResult,
        active: ActiveSession,
        contents: AgentDefinitionFile.Contents?,
    ): TierECollectorRuntimeResult {
        val target = active.registeredTarget
        val afterResultDelivery = try {
            resultSink.accept(result)
            result
        } catch (cause: Throwable) {
            recordFinalizationFailure(active, cause)
            result.withTerminalDeliveryFailure()
        }
        val report = TierETerminalReport.from(afterResultDelivery, target, contents).let {
            if (active.outcome is FinalizationOutcome.Failed) it.copy(terminalReason = "FINALIZATION_FAILED") else it
        }
        val receipt = afterResultDelivery.metrics.loop.receiptAge
        System.out.println(
            "tier-e-collector-metrics: polls=${afterResultDelivery.metrics.loop.polls} " +
                "failure=${afterResultDelivery.metrics.failure ?: "none"} " +
                "active_polls=${afterResultDelivery.metrics.loop.activePolls} " +
                "idle_polls=${afterResultDelivery.metrics.loop.idlePolls} " +
                "receipt_samples=${receipt.samples} receipt_min_ns=${receipt.minNanos} " +
                "receipt_mean_ns=${receipt.meanNanos} receipt_max_ns=${receipt.maxNanos} " +
                "receipt_p50_upper_ns=${receipt.p50UpperBoundNanos} " +
                "receipt_p95_upper_ns=${receipt.p95UpperBoundNanos} " +
                "receipt_p99_upper_ns=${receipt.p99UpperBoundNanos} " +
                "receipt_p99_fine_upper_ns=${receipt.p99FineUpperBoundNanos} " +
                "marker_transitions=${afterResultDelivery.profile.integrity.reconciliation.markerTransitions} " +
                "kernel_active_observations=${afterResultDelivery.profile.integrity.reconciliation.kernelActiveObservations} " +
                "ambient_inactive=${afterResultDelivery.profile.integrity.ambientInactiveObservations} " +
                "ring_loss=${afterResultDelivery.profile.integrity.observedLosses}",
        )
        val coverageSink = coverageManifestSink
        val afterCoverageManifest = if (coverageSink == null ||
            runCatching {
                coverageSink.sink.accept(
                    TierECoverageManifest.from(
                        afterResultDelivery,
                        report,
                        coverageSink.expectedOracleSha256,
                        coverageSink.expectedSyscallNumber,
                    ),
                )
            }.isSuccess
        ) {
            afterResultDelivery
        } else {
            afterResultDelivery.withTerminalDeliveryFailure()
        }
        val operationSink = operationManifestSink
        val afterOperationManifest = if (operationSink == null ||
            (
                target != null &&
                runCatching {
                operationSink.accept(TierESplitOperationManifest.from(afterCoverageManifest, target))
            }.isSuccess
            )
        ) {
            afterCoverageManifest
        } else {
            afterCoverageManifest.withTerminalDeliveryFailure()
        }
        terminalReportSink.accept(TierETerminalReport.from(afterOperationManifest, target, contents).copy(terminalReason = report.terminalReason))
        return afterOperationManifest
    }

    private data class ActiveSession(
        var runtime: TierECollectorRuntime?,
        var resource: AutoCloseable,
        val session: AttachedSessionInfo,
        val artifact: io.mazewall.profiler.internal.attribution.TierEDefinitionArtifact?,
        val registeredTarget: TierERegisteredTarget? = null,
        var terminalWatch: AutoCloseable? = null,
        var outcome: FinalizationOutcome = FinalizationOutcome.Collecting,
        var retired: Boolean = false,
        var reportAttempted: Boolean = false,
        var boundaryReporter: (() -> Unit)? = null,
        var boundaryReadinessFailure: Throwable? = null,
    )

    private sealed interface FinalizationOutcome {
        data object Collecting : FinalizationOutcome

        data object Completed : FinalizationOutcome

        data class Failed(
            val cause: Throwable,
        ) : FinalizationOutcome
    }
}

internal data class TierECoverageManifestConfiguration(
    val sink: TierECoverageManifestFileSink,
    val expectedOracleSha256: String,
    val expectedSyscallNumber: Int,
)

/** Fully resolved attach inputs; registered deployments never derive trust from v1 control fields alone. */
internal data class TierEDaemonAttachment(
    val session: AttachedSessionInfo,
    val markerPath: Path,
    val markerOffset: Long,
    val artifact: io.mazewall.profiler.internal.attribution.TierEDefinitionArtifact?,
    val registeredTarget: TierERegisteredTarget?,
)

internal fun resolveDaemonAttachment(
    command: ControlCommand.Attach,
    registeredTargetResolver: TierETargetRegistrationResolver?,
    artifactResolver: TierEDaemonArtifactResolver?,
): TierEDaemonAttachment {
    val registered = registeredTargetResolver?.resolve(command)
    val session = AttachedSessionInfo(
        pid = registered?.pid ?: command.pid,
        sessionTag = command.sessionTag,
        processIncarnation = registered?.processIncarnation ?: command.sessionTag.toLong(),
    )
    return TierEDaemonAttachment(
        session = session,
        markerPath = registered?.markerPath ?: Path.of(command.markerPath),
        markerOffset = registered?.markerOffset ?: command.markerOffset,
        artifact = when {
            registered != null -> requireNotNull(artifactResolver).resolve(registered)
            artifactResolver != null -> artifactResolver.resolve(session)
            else -> null
        },
        registeredTarget = registered,
    )
}

/** A closed, session-bound MZSE artifact is the only daemon-side proof of producer closure. */
internal fun terminalClosure(
    resolver: TierEDaemonArtifactResolver?,
    artifact: io.mazewall.profiler.internal.attribution.TierEDefinitionArtifact,
): TierEProducerClosure =
    runCatching { requireNotNull(resolver).readTerminal(artifact) }
        .fold(
            onSuccess = { TierEProducerClosure.PROVEN },
            onFailure = { TierEProducerClosure.UNPROVEN },
        )

private fun runningAsRoot(): Boolean =
    (Files.getAttribute(Path.of("/proc/self"), "unix:uid") as? Number)?.toLong() == 0L ||
        System.getProperty("user.name") == "root"

private class TierEMarkerVersionMismatch(
    cause: IllegalArgumentException,
) : IllegalArgumentException("selected marker v2 proof was not admitted: ${cause.message}", cause)
