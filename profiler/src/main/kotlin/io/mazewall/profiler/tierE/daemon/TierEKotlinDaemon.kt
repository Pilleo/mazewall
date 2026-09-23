@file:JvmName("TierEKotlinDaemonMain")

package io.mazewall.profiler.tierE.daemon

import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.profiler.internal.attribution.AgentDefinitionFile
import io.mazewall.profiler.internal.attribution.CoverageRecoveryMode
import io.mazewall.profiler.internal.attribution.EvidenceIntegrity
import io.mazewall.profiler.internal.attribution.MarkerVersion
import io.mazewall.profiler.internal.attribution.SessionEpoch
import io.mazewall.profiler.internal.attribution.TierECollectorRuntime
import io.mazewall.profiler.internal.attribution.TierECollectorRuntimeResult
import io.mazewall.profiler.internal.attribution.TierELinuxMonotonicClock
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierEProducerClosure
import io.mazewall.profiler.internal.attribution.TierEProfilerSession
import io.mazewall.profiler.tierE.engine.TierEbpfEngine
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

    val socketPath = System.getenv("TIER_E_SOCKET") ?: "/tmp/tier_e.sock"
    Files.deleteIfExists(Path.of(socketPath))
    val socketAccess = TierEControlSocketAccess.fromEnvironment()

    val stopRequested = AtomicBoolean(false)
    val registeredTargetResolver = System
        .getenv("TIER_E_TARGET_REGISTRATION")
        ?.let { TierETargetRegistrationFile.read(Path.of(it)) }
        ?.let { target -> TierETargetRegistrationResolver(mapOf(target.registrationId to target)) }
    val fileReportSink = System.getenv("TIER_E_EVIDENCE_ROOT")?.let {
        TierETerminalReportFileSink(Path.of(it))
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
        processIdentityVerifier = System.getenv("TIER_E_BENCHMARK_REUSE_MARKER")?.let { marker ->
            val path = Path.of(marker)
            TierEProcessIdentityVerifier { pid ->
                TierEProcessIdentityVerifier.observeLinuxIncarnation(pid)?.let { observed ->
                    if (Files.exists(path)) observed + 1 else observed
                }
            }
        } ?: TierEProcessIdentityVerifier.linux,
        registeredTargetResolver = registeredTargetResolver,
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
    private val benchmarkDropRingSyscallNumber: Int? = benchmarkDropRingSyscallFromEnvironment(),
) {
    private var sessionState: ControlSessionState = ControlSessionState.Idle
    private var activeSession: ActiveSession? = null
    private var lastTerminalResult: TierECollectorRuntimeResult? = null
    private var activeMarkerTransport = "idle"
    private var activeMarkerFallbackReason: String? = null

    init {
        require(registeredTargetResolver == null || artifactResolver != null) {
            "registered target deployment requires TIER_E_DEFINITION_ROOT"
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

            is ControlSessionEffect.RespondStatus -> {
                val statusText = activeSession?.let {
                    val metrics = it.runtime.metrics()
                    "engine=active marker_transport=$activeMarkerTransport " +
                        "marker_fallback=${activeMarkerFallbackReason ?: "none"} " +
                        "collector_state=${metrics.state} worker_alive=${metrics.workerAlive} " +
                        "polls=${metrics.loop.polls} active_polls=${metrics.loop.activePolls} " +
                        "idle_polls=${metrics.loop.idlePolls} failure=${metrics.failure ?: "none"}"
                } ?: lastTerminalResult?.let { result ->
                    "engine=idle terminal_transport_complete=${result.profile.integrity.verdictDimensions.transportComplete}"
                } ?: "engine=idle"
                ControlReply.ok(statusText).render().trimEnd()
            }

            is ControlSessionEffect.PerformAttach -> {
                finishActiveSession()
                val cmd = effect.command
                var engine: TierEbpfEngine? = null
                var runtime: TierECollectorRuntime? = null
                try {
                    val attachment = resolveDaemonAttachment(cmd, registeredTargetResolver, artifactResolver)
                    attachment.registeredTarget?.let(processIdentityVerifier::requireCurrent)
                    engine = engineFactory()
                    val session = TierEProfilerSession(
                        engine = requireNotNull(engine),
                        processEpoch = attachment.session.processIncarnation,
                        options = TierEOptions(
                            cmd.emissionMode,
                            maxPendingRecords = benchmarkRecordLimit ?: TierEOptions.DEFAULT_RECORD_LIMIT,
                            maxRetainedRecords = benchmarkRecordLimit ?: TierEOptions.DEFAULT_RECORD_LIMIT,
                            benchmarkPhases = benchmarkPhases,
                            experimentalCoverage = markerVersion == MarkerVersion.V2,
                            markerVersion = markerVersion,
                            coverageRecoveryMode = recoveryMode,
                            coverageRecoveryMapCapacity = recoveryMapCapacity,
                            benchmarkDropRingSyscallNumber = benchmarkDropRingSyscallNumber,
                        ),
                        configuredSessionEpoch = SessionEpoch(cmd.sessionTag.toLong()),
                    )
                    runtime = TierECollectorRuntime(
                        session,
                        receiptClock = if (receiptClockMode == "monotonic") TierELinuxMonotonicClock() else null,
                    )
                    val markerPath = attachment.markerPath
                    val selection = if (markerVersion == MarkerVersion.V2) {
                        selectMarkerTransport(cmd.mode) {
                            UsdtProbeNotes.findStackMarkerV2(markerPath)
                        }
                    } else {
                        selectMarkerTransport(cmd.mode) {
                            UsdtProbeNotes.findStackMarkerV1(markerPath)
                        }
                    }
                    session.installAndAttach(
                        attachment.session.pid,
                        cmd.sessionTag,
                        markerPath,
                        attachment.markerOffset,
                        selection.usdtNote,
                    )
                    runtime.start()
                    val active = ActiveSession(runtime, attachment.artifact, attachment.registeredTarget)
                    activeSession = active
                    attachment.registeredTarget?.let { target ->
                        active.terminalWatch = targetExitCoordinator.watch(target) {
                            finalizeRegisteredTarget(target)
                        }
                    }
                    activeMarkerTransport = selection.selected.name.lowercase()
                    activeMarkerFallbackReason = selection.fallbackReason
                    val postTransition = ControlSessionMachine.evaluate(
                        sessionState,
                        ControlSessionEvent.AttachSucceeded(attachment.session),
                    )
                    sessionState = postTransition.state
                    (postTransition.effect as ControlSessionEffect.SendReply).reply.render().trimEnd()
                } catch (expectedAttachFailure: Exception) {
                    runCatching { runtime?.close() ?: engine?.close() }
                    val rejectedTarget = runCatching { registeredTargetResolver?.resolve(cmd) }.getOrNull()
                    if (rejectedTarget != null &&
                        processIdentityVerifier.checkAtTerminal(rejectedTarget) != TierEProcessIdentityCheck.MATCHED
                    ) {
                        runCatching {
                            terminalReportSink.accept(
                                TierETerminalReport(
                                    evidenceIntegrity = EvidenceIntegrity.INCOMPLETE,
                                    terminalReason = "STALE_PROCESS_IDENTITY",
                                    resolvedSyscalls = 0,
                                    captureDomainValidity = false,
                                    transportCompleteness = false,
                                    attributionCoverage = false,
                                    dictionaryIntegrity = false,
                                    lifecycleValidity = false,
                                    registrationId = rejectedTarget.registrationId,
                                    sessionEpoch = rejectedTarget.sessionEpoch.value,
                                    targetPid = rejectedTarget.pid,
                                    processIncarnation = rejectedTarget.processIncarnation,
                                ),
                            )
                        }
                    }
                    val postTransition = ControlSessionMachine.evaluate(
                        sessionState,
                        ControlSessionEvent.AttachFailed(expectedAttachFailure.message ?: "attach failed"),
                    )
                    sessionState = postTransition.state
                    (postTransition.effect as ControlSessionEffect.SendReply).reply.render().trimEnd()
                }
            }

            is ControlSessionEffect.CloseSessionAndReply -> {
                val reply = effect.reply.render().trimEnd()
                finishActiveSession()
                reply
            }

            is ControlSessionEffect.CloseAndShutdown -> {
                val reply = ControlReply.ok("shutting down").render().trimEnd()
                finishActiveSession()
                stopRequested.set(true)
                reply
            }
        }

    @Synchronized
    fun shutdown() {
        finishActiveSession()
        stopRequested.set(true)
    }

    @Synchronized
    private fun finalizeRegisteredTarget(target: TierERegisteredTarget) {
        if (activeSession?.registeredTarget != target) return
        finishActiveSession(waitForTerminalArtifact = true)
        sessionState = ControlSessionState.Idle
    }

    private fun finishActiveSession(waitForTerminalArtifact: Boolean = false) {
        val active = activeSession ?: return
        activeSession = null
        active.terminalWatch?.close()
        val artifact = active.artifact
        if (artifact == null) {
            active.runtime.close()
            return
        }
        val identityCheck = active.registeredTarget?.let(processIdentityVerifier::checkAtTerminal)
        val terminalContents = if (active.registeredTarget != null && identityCheck != TierEProcessIdentityCheck.REUSED) {
            readTerminalContents(artifact, waitForTerminalArtifact)
        } else {
            null
        }
        val producerClosure = if (terminalContents == null) TierEProducerClosure.UNPROVEN else TierEProducerClosure.PROVEN
        val result = runCatching {
            if (active.registeredTarget != null) {
                active.runtime.finish(
                    terminalContents ?: AgentDefinitionFile.Contents(emptyList(), emptyList(), 0),
                    producerClosure,
                )
            } else {
                active.runtime.finish(artifact, producerClosure)
            }
        }.getOrNull()
        if (result != null) {
            val admitted = when {
                identityCheck == TierEProcessIdentityCheck.REUSED -> result.withReusedProcessIdentity()
                active.registeredTarget != null && terminalContents == null -> result.withUnavailableArtifact()
                else -> result
            }
            lastTerminalResult = deliverResult(admitted, active.registeredTarget, terminalContents)
        }
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

    private fun deliverResult(
        result: TierECollectorRuntimeResult,
        target: TierERegisteredTarget?,
        contents: AgentDefinitionFile.Contents?,
    ): TierECollectorRuntimeResult {
        // A failed optional in-process consumer must not suppress the durable,
        // operator-owned terminal record of the fail-closed outcome.
        val afterResultDelivery = if (runCatching { resultSink.accept(result) }.isSuccess) {
            result
        } else {
            result.withTerminalDeliveryFailure()
        }
        val report = TierETerminalReport.from(afterResultDelivery, target, contents)
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
        val afterCoverageManifest = if (coverageManifestSink == null ||
            runCatching {
                val configuration = requireNotNull(coverageManifestSink)
                configuration.sink.accept(
                    TierECoverageManifest.from(
                        afterResultDelivery,
                        report,
                        configuration.expectedOracleSha256,
                        configuration.expectedSyscallNumber,
                    ),
                )
            }.isSuccess
        ) {
            afterResultDelivery
        } else {
            afterResultDelivery.withTerminalDeliveryFailure()
        }
        val afterOperationManifest = if (operationManifestSink == null ||
            (
                target != null &&
                runCatching {
                operationManifestSink?.accept(TierESplitOperationManifest.from(afterCoverageManifest, target))
            }.isSuccess
            )
        ) {
            afterCoverageManifest
        } else {
            afterCoverageManifest.withTerminalDeliveryFailure()
        }
        return if (runCatching {
                terminalReportSink.accept(TierETerminalReport.from(afterOperationManifest, target, contents))
            }.isSuccess
        ) {
            afterOperationManifest
        } else {
            afterOperationManifest.withTerminalDeliveryFailure()
        }
    }

    private data class ActiveSession(
        val runtime: TierECollectorRuntime,
        val artifact: io.mazewall.profiler.internal.attribution.TierEDefinitionArtifact?,
        val registeredTarget: TierERegisteredTarget? = null,
        var terminalWatch: AutoCloseable? = null,
    )
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

internal fun markerVersionFromEnvironment(env: Map<String, String> = System.getenv()): MarkerVersion =
    when (env["TIER_E_MARKER_VERSION"]) {
        null, "", "v1" -> MarkerVersion.V1
        "v2" -> MarkerVersion.V2
        else -> error("TIER_E_MARKER_VERSION must be v1 or v2")
    }

internal fun receiptClockModeFromEnvironment(env: Map<String, String> = System.getenv()): String =
    when (env["TIER_E_COLLECTOR_RECEIPT_CLOCK"]) {
        null, "", "disabled" -> "disabled"
        "monotonic" -> "monotonic"
        else -> error("TIER_E_COLLECTOR_RECEIPT_CLOCK must be disabled or monotonic")
    }

internal fun coverageRecoveryModeFromEnvironment(env: Map<String, String> = System.getenv()): CoverageRecoveryMode =
    when (env["TIER_E_COVERAGE_RECOVERY_MODE"]) {
        null, "", "disabled" -> CoverageRecoveryMode.DISABLED
        "shadow" -> CoverageRecoveryMode.SHADOW
        "recover" -> CoverageRecoveryMode.RECOVER
        else -> error("TIER_E_COVERAGE_RECOVERY_MODE must be disabled, shadow, or recover")
    }

internal fun recoveryMapCapacityFromEnvironment(env: Map<String, String> = System.getenv()): Int =
    env["TIER_E_RECOVERY_MAP_CAPACITY"]?.toIntOrNull()?.takeIf { it in 1..CoverageRecoveryMode.DEFAULT_MAP_CAPACITY }
        ?: if (env.containsKey("TIER_E_RECOVERY_MAP_CAPACITY")) {
            error("TIER_E_RECOVERY_MAP_CAPACITY must be 1..4096")
        } else {
            CoverageRecoveryMode.DEFAULT_MAP_CAPACITY
        }

internal fun benchmarkDropRingSyscallFromEnvironment(env: Map<String, String> = System.getenv()): Int? =
    env["TIER_E_BENCHMARK_DROP_RING_SYSCALL"]?.toIntOrNull()?.takeIf { it >= 0 }
        ?: if (env.containsKey("TIER_E_BENCHMARK_DROP_RING_SYSCALL")) {
            error("TIER_E_BENCHMARK_DROP_RING_SYSCALL must be a nonnegative syscall number")
        } else {
            null
        }
