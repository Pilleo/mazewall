package io.mazewall.profiler.tierE.daemon

import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.profiler.attribution.TierEOptions
import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import java.io.DataInputStream
import java.io.DataOutputStream
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
private const val CLIENT_SOCKET_TIMEOUT_MS = 10_000

public fun main() {
    if (!runningAsRoot()) {
        System.err.println("[tier-e] refusing: requires root")
        exitProcess(1)
    }

    val socketPath = System.getenv("TIER_E_SOCKET") ?: "/tmp/tier_e.sock"
    Files.deleteIfExists(Path.of(socketPath))

    val stopRequested = AtomicBoolean(false)
    val daemon = TierEDaemonService(stopRequested)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { daemon.shutdown() }
            runCatching { Files.deleteIfExists(Path.of(socketPath)) }
        },
    )

    val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    // Bind abstract unix socket via sun.nio? For now use TCP loopback for prototype simplicity.
    // TODO replace with AF_UNIX once available.

    System.out.println("[tier-e] listening port=${server.localPort}")

    while (!stopRequested.get()) {
        val client = runCatching { server.accept() }.getOrNull() ?: break
        thread(name = "tier-e-client") {
            try {
                client.soTimeout = CLIENT_SOCKET_TIMEOUT_MS
                DataInputStream(client.getInputStream()).use { input ->
                    DataOutputStream(client.getOutputStream()).use { out ->
                        while (!stopRequested.get()) {
                            val line = input.readUTF()
                            val reply = daemon.executeCommand(line)
                            out.writeUTF(reply)
                            out.flush()
                        }
                    }
                }
            } catch (expectedClientDisconnect: Exception) {
                // peer disconnected or timed out — session over
            } finally {
                runCatching { client.close() }
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
                    "engine=active queued=${it.drainEvents().size} lost=${it.readLossCount()}"
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
                    engine.attachMarker(cmd.pid, Path.of(cmd.markerPath), cmd.markerOffset)
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
    (Files.getAttribute(Path.of("/proc/self"), "uid") as? Int) == 0 ||
        System.getProperty("user.name") == "root"
