package io.mazewall.portal.worker

import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.ProcessPolicies
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.portal.Capability
import io.mazewall.portal.PortalChannel
import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalMethod
import io.mazewall.portal.PortalPeerClosedException
import io.mazewall.portal.PortalReadTimeoutException
import kotlin.system.exitProcess

/**
 * Placement worker: process-wide policy then RPC. Not USER_NOTIF.
 * Default install is the static fail-closed trio.
 * Pass `--complete-profile <profile-document.json>` to install
 * [io.mazewall.profiler.api.ProfilingResult.dedicatedWorkerPoliciesFromDocument] instead.
 * The file must contain versioned measured complete coverage; plain SBoB and legacy completion
 * markers are rejected.
 * Guest impl is never loaded in the broker process.
 */
public object PortalWorkerMain {
    public const val READY: String = "MAZEWALL_PORTAL_WORKER_READY"

    @JvmStatic
    @Suppress("TooGenericExceptionCaught") // Socket acquisition owns cleanup if channel construction fails, including fatal errors.
    public fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: PortalWorkerMain <socket_path> [--complete-profile <profile-document.json>]")
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
            installContainment = { installWorkerContainment(args) },
            createDispatcher = {
                val builder = PortalDispatcherTable.Builder()
                bootstrapWorkerDispatchers(
                    builder,
                    System.getProperty("io.mazewall.portal.worker.dispatchers"),
                    PortalWorkerMain::class.java.classLoader,
                )
                val dispatcherTable = builder.build()
                PortalWorkerDispatcher { method, payload, fds ->
            when (method) {
                is PortalMethod.Generated -> dispatcherTable.dispatchOrNull(method, payload, fds) ?: throw UnknownPortalMethod(method)
                PortalMethod.Echo,
                PortalMethod.Checksum,
                PortalMethod.Sleep,
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

    @Suppress("SpreadOperator") // The profile decoder deliberately owns the one array forwarded to the vararg installation API.
    internal fun installWorkerContainment(args: Array<String>) {
        val profile = completeProfilePath(args)
        if (profile == null) {
            ContainedExecutors.installOnDedicatedProcess(
                ProcessPolicies.workerFilesystem(RuntimeProfile.HOTSPOT_JIT),
                ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT),
                ProcessPolicies.denyNetwork(RuntimeProfile.HOTSPOT_JIT),
            )
            return
        }
        val json = java.nio.file.Files
            .readString(profile)
        ContainedExecutors.installOnDedicatedProcess(
            *io.mazewall.profiler.api.ProfilingResult
                .dedicatedWorkerPoliciesFromDocument(json),
        )
    }

    internal fun completeProfilePath(args: Array<String>): java.nio.file.Path? {
        val index = args.indexOf("--complete-profile")
        if (index < 0) return null
        require(index + 1 < args.size) { "Usage: PortalWorkerMain <socket_path> [--complete-profile <profile-document.json>]" }
        return java.nio.file.Path
            .of(args[index + 1])
    }
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
