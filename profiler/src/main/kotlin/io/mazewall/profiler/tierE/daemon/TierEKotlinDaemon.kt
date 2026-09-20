@file:JvmName("TierEKotlinDaemonMain")

package io.mazewall.profiler.tierE.daemon

import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.profiler.internal.attribution.SessionEpoch
import io.mazewall.profiler.internal.attribution.TierECollectorRuntime
import io.mazewall.profiler.internal.attribution.TierECollectorRuntimeResult
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierEProducerClosure
import io.mazewall.profiler.internal.attribution.TierEProfilerSession
import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import java.net.InetAddress
import java.net.ServerSocket
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

    val stopRequested = AtomicBoolean(false)
    val daemon = TierEDaemonService(stopRequested)
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
        Runtime.getRuntime().addShutdownHook(
            Thread {
                stopRequested.set(true)
                runCatching { server.close() }
                runCatching { daemon.shutdown() }
                runCatching { Files.deleteIfExists(Path.of(socketPath)) }
            },
        )

        // Bind abstract unix socket via sun.nio? For now use TCP loopback for prototype simplicity.
        // TODO replace with AF_UNIX once available.

        System.out.println("[tier-e] listening port=${server.localPort}")

        while (!stopRequested.get()) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            thread(name = "tier-e-client") {
                try {
                    TierEClientSession.run(client, stopRequested, daemon::executeCommand)
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
) {
    private var sessionState: ControlSessionState = ControlSessionState.Idle
    private var activeSession: ActiveSession? = null
    private var lastTerminalResult: TierECollectorRuntimeResult? = null
    private var activeMarkerTransport = "idle"
    private var activeMarkerFallbackReason: String? = null

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
                finishActiveSession(TierEProducerClosure.UNPROVEN)
                val cmd = effect.command
                val engine = engineFactory()
                var runtime: TierECollectorRuntime? = null
                try {
                    val session = TierEProfilerSession(
                        engine = engine,
                        processEpoch = cmd.sessionTag.toLong(),
                        options = TierEOptions(cmd.emissionMode),
                        configuredSessionEpoch = SessionEpoch(cmd.sessionTag.toLong()),
                    )
                    runtime = TierECollectorRuntime(session)
                    val markerPath = Path.of(cmd.markerPath)
                    val selection = selectMarkerTransport(cmd.mode) {
                        UsdtProbeNotes.findStackMarkerV1(markerPath)
                    }
                    session.installAndAttach(
                        cmd.pid,
                        cmd.sessionTag,
                        markerPath,
                        cmd.markerOffset,
                        selection.usdtNote,
                    )
                    runtime.start()
                    val attached = AttachedSessionInfo(cmd.pid, cmd.sessionTag, cmd.sessionTag.toLong())
                    activeSession = ActiveSession(runtime, artifactResolver?.resolve(attached))
                    activeMarkerTransport = selection.selected.name.lowercase()
                    activeMarkerFallbackReason = selection.fallbackReason
                    val postTransition = ControlSessionMachine.evaluate(
                        sessionState,
                        ControlSessionEvent.AttachSucceeded(attached),
                    )
                    sessionState = postTransition.state
                    (postTransition.effect as ControlSessionEffect.SendReply).reply.render().trimEnd()
                } catch (expectedAttachFailure: Exception) {
                    runCatching { runtime?.close() ?: engine.close() }
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
                finishActiveSession(TierEProducerClosure.UNPROVEN)
                reply
            }

            is ControlSessionEffect.CloseAndShutdown -> {
                val reply = ControlReply.ok("shutting down").render().trimEnd()
                finishActiveSession(TierEProducerClosure.UNPROVEN)
                stopRequested.set(true)
                reply
            }
        }

    @Synchronized
    fun shutdown() {
        finishActiveSession(TierEProducerClosure.UNPROVEN)
        stopRequested.set(true)
    }

    private fun finishActiveSession(producerClosure: TierEProducerClosure) {
        val active = activeSession ?: return
        activeSession = null
        val artifact = active.artifact
        if (artifact == null) {
            active.runtime.close()
            return
        }
        val closure = runCatching { artifactResolver?.readTerminal(artifact) }
            .fold(
                onSuccess = { TierEProducerClosure.PROVEN },
                onFailure = { TierEProducerClosure.UNPROVEN },
            )
        val result = runCatching { active.runtime.finish(artifact, if (closure == TierEProducerClosure.PROVEN) producerClosure else closure) }
            .getOrNull()
        if (result != null) lastTerminalResult = deliverResult(result)
    }

    private fun deliverResult(result: TierECollectorRuntimeResult): TierECollectorRuntimeResult =
        runCatching { resultSink.accept(result) }
            .fold(onSuccess = { result }, onFailure = { result.withTerminalDeliveryFailure() })

    private data class ActiveSession(
        val runtime: TierECollectorRuntime,
        val artifact: io.mazewall.profiler.internal.attribution.TierEDefinitionArtifact?,
    )
}

private fun runningAsRoot(): Boolean =
    (Files.getAttribute(Path.of("/proc/self"), "unix:uid") as? Number)?.toLong() == 0L ||
        System.getProperty("user.name") == "root"
