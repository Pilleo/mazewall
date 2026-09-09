package io.mazewall.portal.worker

import io.mazewall.ProcessPolicies
import io.mazewall.RuntimeProfile
import io.mazewall.core.RealSocketManager
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.portal.PortalChannel
import io.mazewall.portal.PortalIncompleteFrameException
import io.mazewall.portal.PortalMethod
import io.mazewall.portal.PortalReadTimeoutException
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
        // Generated service dispatchers must be registered before the first request
        // can arrive; entries come from -Dio.mazewall.portal.worker.dispatchers.
        val dispatchers = publishDispatcherTable(
            System.getProperty("io.mazewall.portal.worker.dispatchers"),
        )
        val dispatcherTable = dispatchers.table
        if (dispatchers.registeredEntries > 0) {
            println("[DBG-W] registered=${dispatchers.registeredEntries} generated dispatcher(s)")
        }
        val channel = PortalChannel(connected, sockets)
        // Idle workers must not exit on quiet periods: timeouts are an idle tick (continue),
        // while genuine socket death (ECONNRESET/POLLHUP from a dead broker) still breaks the
        // loop and lets the worker exit cleanly. The deadline is injectable so tests can prove
        // idle-tick survival in milliseconds instead of minutes
        // (issue-20260824-011654).
        val idleTimeoutMs = System
            .getProperty("io.mazewall.portal.worker.idleTimeoutMs")
            ?.toLongOrNull() ?: 30_000L
        val transport = ChannelWorkerTransport(channel, idleTimeoutMs)
        val dispatcher = PortalWorkerDispatcher { method, payload, fds ->
            when (method) {
                is PortalMethod.Generated -> dispatcherTable.dispatchOrNull(method, payload, fds) ?: throw UnknownPortalMethod(method)
                PortalMethod.Echo,
                PortalMethod.Checksum,
                PortalMethod.Sleep,
                PortalMethod.TryOpenHostPasswd,
                -> PortalBuiltinDispatch.handle(method, payload, fds)
            }
        }
        val driver = PortalWorkerDriver(transport, dispatcher)
        // Readiness is the publication boundary: containment, dispatcher validation,
        // guest construction, and channel initialization all succeeded above.
        println(READY)
        System.out.flush()
        println("[DBG-W] idleTimeoutMs=$idleTimeoutMs")
        driver.run()
    }

    internal fun publishDispatcherTable(raw: String?): PortalDispatcherPublication = PortalDispatcherRegistry.publishStrict(raw)
}

private class ChannelWorkerTransport(
    private val channel: PortalChannel,
    private val idleTimeoutMs: Long,
) : PortalWorkerTransport {
    override fun receive(): PortalWorkerReceive =
        try {
        val (frame, fds) = channel.receive(idleTimeoutMs)
        PortalWorkerReceive.Frame(frame, fds)
    } catch (_: PortalReadTimeoutException) {
        PortalWorkerReceive.Idle
    } catch (partial: PortalIncompleteFrameException) {
        PortalWorkerReceive.Failure(partial)
    } catch (_: java.io.IOException) {
        PortalWorkerReceive.PeerClosed
    }

    override fun send(frame: io.mazewall.portal.PortalFrame) {
        channel.send(frame)
    }

    override fun close() {
        channel.close()
    }
}
