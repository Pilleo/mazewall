@file:JvmName("TierEKotlinDaemonMain")

package io.mazewall.profiler.tierE.daemon

import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.profiler.internal.attribution.AgentDefinitionFile
import io.mazewall.profiler.internal.attribution.SessionEpoch
import io.mazewall.profiler.internal.attribution.TierECollectorRuntime
import io.mazewall.profiler.internal.attribution.TierECollectorRuntimeResult
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
    val daemon = TierEDaemonService(
        stopRequested,
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
                        options = TierEOptions(cmd.emissionMode),
                        configuredSessionEpoch = SessionEpoch(cmd.sessionTag.toLong()),
                    )
                    runtime = TierECollectorRuntime(session)
                    val markerPath = attachment.markerPath
                    val selection = selectMarkerTransport(cmd.mode) {
                        UsdtProbeNotes.findStackMarkerV1(markerPath)
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
        if (result != null) lastTerminalResult = deliverResult(result, active.registeredTarget, terminalContents)
    }

    private fun readTerminalContents(
        artifact: io.mazewall.profiler.internal.attribution.TierEDefinitionArtifact,
        waitForTerminalArtifact: Boolean,
    ): AgentDefinitionFile.Contents? {
        val deadline = System.nanoTime() + if (waitForTerminalArtifact) 30_000_000_000L else 0L
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
        return if (runCatching {
                terminalReportSink.accept(TierETerminalReport.from(afterResultDelivery, target, contents))
            }.isSuccess
        ) {
            afterResultDelivery
        } else {
            afterResultDelivery.withTerminalDeliveryFailure()
        }
    }

    private data class ActiveSession(
        val runtime: TierECollectorRuntime,
        val artifact: io.mazewall.profiler.internal.attribution.TierEDefinitionArtifact?,
        val registeredTarget: TierERegisteredTarget? = null,
        var terminalWatch: AutoCloseable? = null,
    )
}

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
