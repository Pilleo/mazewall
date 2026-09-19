@file:JvmName("TierEKotlinDaemonMain")

package io.mazewall.profiler.tierE.daemon

import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.profiler.internal.attribution.TierEOptions
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
) {
    private var sessionState: ControlSessionState = ControlSessionState.Idle
    private var activeEngine: TierEbpfEngine? = null
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
                val statusText = activeEngine?.let {
                    val metrics = it
                        .readContextMetrics()
                        .entries
                        .sortedBy { entry -> entry.key }
                        .joinToString(",") { entry -> "${entry.key}:${entry.value}" }
                    "engine=active marker_transport=$activeMarkerTransport " +
                        "marker_fallback=${activeMarkerFallbackReason ?: "none"} " +
                        "queued=${it.drainEvents().size} lost=${it.readLossCount()} metrics=$metrics"
                } ?: "engine=idle"
                ControlReply.ok(statusText).render().trimEnd()
            }

            is ControlSessionEffect.PerformAttach -> {
                activeEngine?.close()
                activeEngine = null
                val cmd = effect.command
                    val engine = engineFactory()
                    try {
                        engine.install(cmd.pid, cmd.sessionTag, TierEOptions(cmd.emissionMode))
                        val markerPath = Path.of(cmd.markerPath)
                        val selection = selectMarkerTransport(cmd.mode) {
                            UsdtProbeNotes.findStackMarkerV1(markerPath)
                        }
                        when (selection.selected) {
                            AttachMode.UPROBE -> {
                                engine.attachMarker(cmd.pid, markerPath, cmd.markerOffset)
                                activeMarkerTransport = "uprobe"
                                activeMarkerFallbackReason = selection.fallbackReason
                            }

                            AttachMode.USDT -> {
                                engine.attachUsdtMarker(cmd.pid, markerPath, requireNotNull(selection.usdtNote))
                                activeMarkerTransport = "usdt"
                                activeMarkerFallbackReason = null
                            }

                            AttachMode.AUTO -> error("marker transport selection must be concrete")
                        }
                    activeEngine = engine
                    val postTransition = ControlSessionMachine.evaluate(
                        sessionState,
                        ControlSessionEvent.AttachSucceeded(AttachedSessionInfo(cmd.pid, cmd.sessionTag)),
                    )
                    sessionState = postTransition.state
                    (postTransition.effect as ControlSessionEffect.SendReply).reply.render().trimEnd()
                } catch (expectedAttachFailure: Exception) {
                    runCatching { engine.close() }
                    val postTransition = ControlSessionMachine.evaluate(
                        sessionState,
                        ControlSessionEvent.AttachFailed(expectedAttachFailure.message ?: "attach failed"),
                    )
                    sessionState = postTransition.state
                    (postTransition.effect as ControlSessionEffect.SendReply).reply.render().trimEnd()
                }
            }

            is ControlSessionEffect.CloseSessionAndReply -> {
                activeEngine?.close()
                activeEngine = null
                effect.reply.render().trimEnd()
            }

            is ControlSessionEffect.CloseAndShutdown -> {
                activeEngine?.close()
                activeEngine = null
                stopRequested.set(true)
                ControlReply.ok("shutting down").render().trimEnd()
            }
        }

    @Synchronized
    fun shutdown() {
        activeEngine?.close()
        activeEngine = null
        stopRequested.set(true)
    }
}

private fun runningAsRoot(): Boolean =
    (Files.getAttribute(Path.of("/proc/self"), "unix:uid") as? Int) == 0 ||
        System.getProperty("user.name") == "root"
