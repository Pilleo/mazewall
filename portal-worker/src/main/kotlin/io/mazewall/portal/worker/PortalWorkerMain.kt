package io.mazewall.portal.worker

import io.mazewall.ProcessPolicies
import io.mazewall.RuntimeProfile
import io.mazewall.core.RealSocketManager
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.portal.PortalChannel
import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalKind
import io.mazewall.portal.PortalPayload
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
        println("[DBG-W-START] args=" + args.joinToString())
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
        // Worker is a dedicated process. Landlock `restrict_self` is process-wide for
        // this dispatch thread and threads it later creates; PolicyScope.ThreadLocalOnly
        // only records that classpath allowlists are not TSYNC'd onto pre-existing JVM
        // helpers. This is not the in-process supervisor's thread-local seccomp path.
        ContainedExecutors.installOnCurrentThread(
            ProcessPolicies.workerFilesystem(RuntimeProfile.HOTSPOT_JIT),
        )
        println(READY)
        System.out.flush()
        // Generated service dispatchers must be registered before the first request
        // can arrive; entries come from -Dio.mazewall.portal.worker.dispatchers.
        val registered = PortalDispatcherRegistry.bootstrapFromProperty(
            System.getProperty("io.mazewall.portal.worker.dispatchers"),
        )
        if (registered > 0) println("[DBG-W] registered=$registered generated dispatcher(s)")
        val channel = PortalChannel(connected, sockets)
        // Idle workers must not exit on quiet periods: timeouts are an idle tick (continue),
        // while genuine socket death (ECONNRESET/POLLHUP from a dead broker) still breaks the
        // loop and lets the worker exit cleanly. The deadline is injectable so tests can prove
        // idle-tick survival in milliseconds instead of minutes
        // (issue-20260824-011654).
        val idleTimeoutMs = System
            .getProperty("io.mazewall.portal.worker.idleTimeoutMs")
            ?.toLongOrNull() ?: 30_000L
        println("[DBG-W] idleTimeoutMs=$idleTimeoutMs")
        var state: PortalWorkerState = PortalWorkerState.AwaitingRequest
        try {
            while (true) {
                val (frame, fds) =
                    try {
                        channel.receive(idleTimeoutMs)
                    } catch (_: io.mazewall.portal.PortalReadTimeoutException) {
                        state = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.IdleTick).state
                        continue
                    } catch (_: Exception) {
                        state = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.PeerClosed).state
                        break
                    }
                val received = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.FrameReceived(frame))
                state = received.state
                val dispatch = when (received) {
                    is PortalWorkerTransition.Dispatch -> received
                    is PortalWorkerTransition.Await,
                    is PortalWorkerTransition.Close,
                    is PortalWorkerTransition.Drop,
                    is PortalWorkerTransition.Send,
                    -> {
                        fds.forEach { sockets.close(it) }
                        continue
                    }
                }
                try {
                    val result = PortalBuiltinDispatch.handle(dispatch.request.method, dispatch.request.payload.copyToByteArray(), fds)
                    val replied = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.DispatchSucceeded(PortalPayload(result)))
                    state = replied.state
                    channel.send(sendFrame(replied))
                } catch (e: UnknownPortalMethod) {
                    val generated = PortalDispatcherRegistry.dispatchOrNull(
                        e.method,
                        dispatch.request.payload.copyToByteArray(),
                        fds,
                    )
                    if (generated != null) {
                        val replied = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.DispatchSucceeded(PortalPayload(generated)))
                        state = replied.state
                        channel.send(sendFrame(replied))
                    } else {
                        val msg = (e.message ?: e::class.java.simpleName).toByteArray(StandardCharsets.UTF_8)
                        val replied = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.DispatchFailed(PortalPayload(msg)))
                        state = replied.state
                        channel.send(sendFrame(replied))
                    }
                } catch (e: Exception) {
                    val msg = (e.message ?: e::class.java.simpleName).toByteArray(StandardCharsets.UTF_8)
                    val replied = PortalWorkerMachine.evaluate(state, PortalWorkerEvent.DispatchFailed(PortalPayload(msg)))
                    state = replied.state
                    channel.send(sendFrame(replied))
                } finally {
                    fds.forEach { sockets.close(it) }
                }
            }
        } finally {
            channel.close()
        }
    }

    private fun sendFrame(transition: PortalWorkerTransition): PortalFrame =
        when (transition) {
            is PortalWorkerTransition.Send -> transition.frame
            is PortalWorkerTransition.Await,
            is PortalWorkerTransition.Close,
            is PortalWorkerTransition.Dispatch,
            is PortalWorkerTransition.Drop,
            -> error("dispatch completion must send a reply")
        }
}
