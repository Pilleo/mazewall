package io.mazewall.profiler.tierE.daemon

import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.profiler.attribution.TierEEmissionMode
import io.mazewall.profiler.attribution.TierEOptions
import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
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
public fun main() {
    if (!runningAsRoot()) {
        System.err.println("[tier-e] refusing: requires root")
        exitProcess(1)
    }

    val socketPath = System.getenv("TIER_E_SOCKET") ?: "/tmp/tier_e.sock"
    Files.deleteIfExists(Path.of(socketPath))

    val stopRequested = AtomicBoolean(false)
    var engine: TierEbpfEngine? = null

    Runtime.getRuntime().addShutdownHook(
        Thread {
        runCatching { engine?.close() }
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
                client.soTimeout = 10_000
                DataInputStream(client.getInputStream()).use { input ->
                    DataOutputStream(client.getOutputStream()).use { out ->
                        while (true) {
                            val line = input.readUTF()
                            val reply = handleCommand(line, { engine }, { engine = it })
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

private fun handleCommand(
    line: String,
    current: () -> TierEbpfEngine?,
    setCurrent: (TierEbpfEngine?) -> Unit,
): String {
    val parts = line.trim().split(" ")
    return when (parts.firstOrNull()?.uppercase()) {
        "ATTACH" -> {
            val pid = parts.getOrNull(1)?.toIntOrNull()
                ?: return "ERR bad pid"
            if (parts.getOrNull(2)?.lowercase() != "uprobe") return "ERR only uprobe mode is implemented"
            val agent = parts.getOrNull(3)?.let(Path::of) ?: return "ERR missing agent path"
            val offset = parts.getOrNull(4)?.removePrefix("0x")?.toLongOrNull(16)
                ?: return "ERR bad marker offset"
            val sessionTag = parts.getOrNull(5)?.removePrefix("0x")?.toIntOrNull(16)
                ?: return "ERR bad session tag"
            val emissionMode = when (parts.getOrNull(6) ?: "full-stream") {
                "full-stream" -> TierEEmissionMode.FULL_STREAM
                "unique-stack-syscall" -> TierEEmissionMode.UNIQUE_STACK_SYSCALL
                else -> return "ERR bad emission mode"
            }
            current()?.close()
            val eng = TierEbpfEngine(RealNativeEngine)
            try {
                eng.install(pid, sessionTag, TierEOptions(emissionMode))
                eng.attachMarker(pid, agent, offset)
                setCurrent(eng)
                "OK attached pid=$pid"
            } catch (expectedAttachFailure: Exception) {
                runCatching { eng.close() }
                "ERR ${expectedAttachFailure.message}"
            }
        }
        "DETACH" -> {
            current()?.close()
            setCurrent(null)
            "OK detached"
        }
        "STATUS" -> current()?.let { "OK engine=active queued=${it.drainEvents().size} lost=${it.readLossCount()}" }
            ?: "OK engine=idle"
        else -> "ERR unknown command"
    }
}

private fun runningAsRoot(): Boolean =
    (Files.getAttribute(Path.of("/proc/self"), "uid") as? Int) == 0 ||
        System.getProperty("user.name") == "root"
