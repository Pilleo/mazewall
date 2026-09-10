package io.mazewall.portal.worker

import io.mazewall.ProcessPolicies
import io.mazewall.RuntimeProfile
import io.mazewall.core.RealSocketManager
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.portal.Capability
import io.mazewall.portal.PortalChannel
import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalMethod
import io.mazewall.portal.PortalPeerClosedException
import io.mazewall.portal.PortalReadTimeoutException
import kotlin.system.exitProcess

/**
 * Worker entry: connect, install process-wide policy, then serve RPC.
 * Guest impl is never loaded in the broker process.
 */
public object PortalWorkerMain {
    public const val READY: String = "MAZEWALL_PORTAL_WORKER_READY"

    @JvmStatic
    @Suppress("TooGenericExceptionCaught") // Socket acquisition owns cleanup if channel construction fails, including fatal errors.
    public fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: PortalWorkerMain <socket_path>")
            exitProcess(1)
        }
        // Idle workers must not exit on quiet periods: timeouts are an idle tick (continue),
        // while clean peer EOF stops the loop and I/O failures terminate with their cause.
        // The deadline is injectable so tests can prove
        // idle-tick survival in milliseconds instead of minutes
        // (issue-20260824-011654).
        val idleTimeoutMs = System
            .getProperty("io.mazewall.portal.worker.idleTimeoutMs")
            ?.toLongOrNull() ?: 30_000L
        require(idleTimeoutMs > 0) { "portal worker idle timeout must be positive" }
        val sockets = RealSocketManager
        val connected = sockets.connect(args[0])
        val channel = try {
            PortalChannel(connected, sockets)
        } catch (failure: Throwable) {
            runCatching { sockets.close(connected) }.exceptionOrNull()?.takeIf { it !== failure }?.let(failure::addSuppressed)
            throw failure
        }
        val transport = ChannelWorkerTransport(channel, idleTimeoutMs)
        runWorker(
            transport,
            installContainment = {
                ContainedExecutors.installOnDedicatedProcess(
                    ProcessPolicies.workerFilesystem(RuntimeProfile.HOTSPOT_JIT),
                    ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT),
                    ProcessPolicies.denyNetwork(RuntimeProfile.HOTSPOT_JIT),
                )
            },
            createDispatcher = {
                val dispatcherTable = publishDispatcherTable(System.getProperty("io.mazewall.portal.worker.dispatchers")).table
                PortalWorkerDispatcher { method, payload, fds ->
            when (method) {
                is PortalMethod.Generated -> dispatcherTable.dispatchOrNull(method, payload, fds) ?: throw UnknownPortalMethod(method)
                PortalMethod.Echo,
                PortalMethod.Checksum,
                PortalMethod.Sleep,
                PortalMethod.TryOpenHostPasswd,
                -> PortalBuiltinDispatch.handle(method, payload, fds)
            }
                }
            },
            publishReady = {
                println(READY)
                System.out.flush()
                check(!System.out.checkError()) { "portal worker readiness publication failed" }
            },
        )
    }

    /** Owns startup until readiness; the driver acquires transport ownership only after publication. */
    @Suppress("TooGenericExceptionCaught") // Startup failure must retire its transport even for fatal guest construction errors.
    internal fun runWorker(
        transport: PortalWorkerTransport,
        installContainment: () -> Unit,
        createDispatcher: () -> PortalWorkerDispatcher,
        publishReady: () -> Unit,
    ) {
        val driver = try {
            installContainment()
            val initialized = PortalWorkerDriver(transport, createDispatcher())
            publishReady()
            initialized
        } catch (failure: Throwable) {
            runCatching(transport::close).exceptionOrNull()?.takeIf { it !== failure }?.let(failure::addSuppressed)
            throw failure
        }
        driver.run()
    }

    internal fun publishDispatcherTable(raw: String?): PortalDispatcherPublication = PortalDispatcherRegistry.publishStrict(raw)
}

private class ChannelWorkerTransport(
    private val channel: PortalChannel,
    private val idleTimeoutMs: Long,
) : PortalWorkerTransport {
    override fun receive(): PortalWorkerReceive = receiveWorkerFrame { channel.receive(idleTimeoutMs) }

    override fun send(frame: io.mazewall.portal.PortalFrame) {
        channel.send(frame)
    }

    override fun close() {
        channel.close()
    }
}

internal fun receiveWorkerFrame(read: () -> Pair<PortalFrame, List<Capability.ReadFd>>): PortalWorkerReceive =
    try {
        val (frame, fds) = read()
        PortalWorkerReceive.Frame(frame, fds)
    } catch (_: PortalReadTimeoutException) {
        PortalWorkerReceive.Idle
    } catch (closed: PortalPeerClosedException) {
        if (closed.suppressed.isEmpty()) PortalWorkerReceive.PeerClosed else PortalWorkerReceive.Failure(closed)
    } catch (failure: java.io.IOException) {
        PortalWorkerReceive.Failure(failure)
    }
