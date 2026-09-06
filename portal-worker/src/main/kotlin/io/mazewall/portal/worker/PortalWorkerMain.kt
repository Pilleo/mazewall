package io.mazewall.portal.worker

import io.mazewall.ProcessPolicies
import io.mazewall.RuntimeProfile
import io.mazewall.core.RealSocketManager
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.portal.PortalChannel
import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalKind
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
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
        // Landlock is ThreadLocalOnly in the type system (no TSYNC on helper threads).
        // It must precede Seccomp.  Dispatcher construction may execute guest
        // constructors, so it occurs only after process containment is installed.
        val workerConcurrency = System.getProperty("io.mazewall.portal.worker.concurrency")
            ?.toIntOrNull() ?: 4
        require(workerConcurrency >= 1) { "portal worker concurrency must be >= 1" }
        lateinit var requestExecutor: ExecutorService
        val registered = PortalWorkerStartup.prepare(
            installFilesystem = {
                ContainedExecutors.installOnCurrentThread(
                    ProcessPolicies.workerFilesystem(RuntimeProfile.HOTSPOT_JIT),
                )
            },
            createWorkerThreads = {
                // Threads inherit this startup thread's Landlock restriction. They
                // must exist before process Seccomp denies subsequent clone calls.
                requestExecutor = Executors.newFixedThreadPool(workerConcurrency)
                (requestExecutor as ThreadPoolExecutor).prestartAllCoreThreads()
            },
            installProcessContainment = {
                ContainedExecutors.installOnProcess(
                    ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT),
                    ProcessPolicies.denyNetwork(RuntimeProfile.HOTSPOT_JIT),
                )
            },
            bootstrapDispatchers = {
                PortalDispatcherRegistry.bootstrapFromProperty(
                    System.getProperty("io.mazewall.portal.worker.dispatchers"),
                )
            },
        )
        if (registered > 0) println("[DBG-W] registered=$registered generated dispatcher(s)")
        println(READY)
        System.out.flush()
        val channel = PortalChannel(connected, sockets)
        // Idle workers must not exit on quiet periods: timeouts are an idle tick (continue),
        // while genuine socket death (ECONNRESET/POLLHUP from a dead broker) still breaks the
        // loop and lets the worker exit cleanly. The deadline is injectable so tests can prove
        // idle-tick survival in milliseconds instead of minutes
        // (issue-20260824-011654).
        val idleTimeoutMs = System.getProperty("io.mazewall.portal.worker.idleTimeoutMs")
            ?.toLongOrNull() ?: 30_000L
        println("[DBG-W] idleTimeoutMs=$idleTimeoutMs")
        try {
            while (true) {
                val (frame, fds) =
                    try {
                        channel.receive(idleTimeoutMs)
                    } catch (_: io.mazewall.portal.PortalReadTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }
                if (frame.kind != PortalKind.REQUEST) {
                    fds.forEach { sockets.close(it) }
                    continue
                }
                requestExecutor.execute {
                    try {
                        val result = PortalBuiltinDispatch.handle(frame.methodId, frame.payload, fds)
                        synchronized(channel) {
                            channel.send(PortalFrame(PortalKind.RESPONSE, frame.requestId, frame.methodId, result, 0))
                        }
                    } catch (e: IllegalArgumentException) {
                        // Only the builtin "unknown method" signal falls through to the
                        // generated dispatchers; real builtin failures stay errors.
                        if (e.message?.startsWith("unknown method") != true) throw e
                        val generated = PortalDispatcherRegistry.dispatchOrNull(frame.methodId, frame.payload, fds)
                        synchronized(channel) {
                            if (generated != null) {
                                channel.send(PortalFrame(PortalKind.RESPONSE, frame.requestId, frame.methodId, generated, 0))
                            } else {
                                val msg = (e.message ?: e::class.java.simpleName).toByteArray(StandardCharsets.UTF_8)
                                channel.send(PortalFrame(PortalKind.ERROR, frame.requestId, frame.methodId, msg, 0))
                            }
                        }
                    } catch (e: Exception) {
                        val msg = (e.message ?: e::class.java.simpleName).toByteArray(StandardCharsets.UTF_8)
                        synchronized(channel) {
                            channel.send(PortalFrame(PortalKind.ERROR, frame.requestId, frame.methodId, msg, 0))
                        }
                    } finally {
                        fds.forEach { sockets.close(it) }
                    }
                }
            }
        } finally {
            requestExecutor.shutdownNow()
            channel.close()
        }
    }
}
