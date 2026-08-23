package io.mazewall.portal.worker

import io.mazewall.ProcessPolicies
import io.mazewall.RuntimeProfile
import io.mazewall.core.RealSocketManager
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.portal.PortalChannel
import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalKind
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

/**
 * Worker entry: connect, install process-wide policy, then serve RPC.
 * Guest impl is never loaded in the broker process.
 */
public object PortalWorkerMain {
    public const val READY: String = "MAZEWALL_PORTAL_WORKER_READY"

    @JvmStatic
    public fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: PortalWorkerMain <socket_path>")
            exitProcess(1)
        }
        val sockets = RealSocketManager
        val connected = sockets.connect(args[0])
        ContainedExecutors.installOnProcess(
            ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT),
            ProcessPolicies.denyNetwork(RuntimeProfile.HOTSPOT_JIT),
        )
        // Landlock is ThreadLocalOnly in the type system (no TSYNC on helper threads).
        // Apply it on the dispatch thread after connect; fail closed if unsupported.
        ContainedExecutors.installOnCurrentThread(
            ProcessPolicies.workerFilesystem(RuntimeProfile.HOTSPOT_JIT),
        )
        println(READY)
        System.out.flush()
        val channel = PortalChannel(connected, sockets)
        // Idle workers must not exit on quiet periods: timeouts are an idle tick (continue),
        // while genuine socket death (ECONNRESET/POLLHUP from a dead broker) still breaks the
        // loop and lets the worker exit cleanly.
        try {
            while (true) {
                val (frame, fds) =
                    try {
                        channel.receive()
                    } catch (_: io.mazewall.portal.PortalReadTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }
                if (frame.kind != PortalKind.REQUEST) {
                    fds.forEach { sockets.close(it) }
                    continue
                }
                try {
                    val result = PortalBuiltinDispatch.handle(frame.methodId, frame.payload, fds)
                    channel.send(PortalFrame(PortalKind.RESPONSE, frame.requestId, frame.methodId, result, 0))
                } catch (e: Exception) {
                    val msg = (e.message ?: e::class.java.simpleName).toByteArray(StandardCharsets.UTF_8)
                    channel.send(PortalFrame(PortalKind.ERROR, frame.requestId, frame.methodId, msg, 0))
                } finally {
                    fds.forEach { sockets.close(it) }
                }
            }
        } finally {
            channel.close()
        }
    }
}
