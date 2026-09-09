package io.mazewall.portal

import io.mazewall.core.ChildStdoutPump
import io.mazewall.core.Deadline
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.JavaAgentSelection
import io.mazewall.core.JvmChildProcess
import io.mazewall.core.JvmChildSpec
import io.mazewall.core.PrivateUnixEndpoint
import io.mazewall.core.ProcessLauncher
import io.mazewall.core.RealProcessLauncher
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Owns a pool of portal worker JVMs and Unix RPC sockets.
 * Spawn workers before the broker installs process-wide seccomp.
 */
public class ProcessBroker(
    private val poolSize: Int = 1,
    private val callTimeoutMs: Long = 30_000L,
    private val sockets: SocketManager = RealSocketManager,
    private val launcher: ProcessLauncher = RealProcessLauncher,
    private val workerClasspath: String = "",
    private val startupTimeoutMs: Long = 30_000L,
    /** Extra -D args for spawned worker JVMs (e.g. injectable idle deadline in tests). */
    private val workerExtraJvmArgs: List<String> = emptyList(),
) : AutoCloseable {
    init {
        require(poolSize >= 1) { "poolSize must be >= 1" }
        require(callTimeoutMs > 0) { "callTimeoutMs must be positive" }
        require(startupTimeoutMs > 0) { "startupTimeoutMs must be positive" }
    }

    public companion object {
        public const val WORKER_CLASSPATH_PROPERTY: String = "io.mazewall.portal.worker.classpath"
    }

    private val logger: Logger = Logger.getLogger(ProcessBroker::class.java.name)

    private val nextId = AtomicInteger(1)
    private val idle = ArrayBlockingQueue<WorkerSlot>(poolSize)
    private val spawned = AtomicInteger(0)
    private val maintenance = Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("portal-broker-maintenance", 0).factory(),
    )

    /**
     * Every live slot (idle AND checked-out), so [close] can destroy workers that are
     * mid-call instead of orphaning them (issue-20260824-011652).
     */
    private val trackedSlots: MutableSet<WorkerSlot> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    private val lifecycleGate = Any()

    @Volatile private var lifecycle: ProcessBrokerLifecycle = ProcessBrokerLifecycle.New

    @Suppress("TooGenericExceptionCaught") // Transaction rollback must run for every acquisition failure.
    public fun start() {
        synchronized(lifecycleGate) {
            val transition = ProcessBrokerLifecycleMachine.evaluate(lifecycle, ProcessBrokerLifecycleEvent.StartRequested)
            check(transition is ProcessBrokerLifecycleTransition.Advanced) { (transition as ProcessBrokerLifecycleTransition.ProtocolViolation).reason }
            lifecycle = transition.state
        }
        val deadline = Deadline.afterMillis(startupTimeoutMs)
        val acquired = ArrayList<WorkerSlot>(poolSize)
        try {
            repeat(poolSize) { acquired += spawnWorker(deadline) }
            synchronized(lifecycleGate) {
                check(lifecycle == ProcessBrokerLifecycle.Starting) { "broker closed during startup" }
                acquired.forEach { trackedSlots.add(it) }
                acquired.forEach { idle.add(it) }
                lifecycle = ProcessBrokerLifecycleMachine
                    .evaluate(lifecycle, ProcessBrokerLifecycleEvent.StartupSucceeded)
                    .state
            }
        } catch (failure: Throwable) {
            acquired.forEach { destroySlot(it) }
            synchronized(lifecycleGate) {
                if (lifecycle == ProcessBrokerLifecycle.Starting) {
                    lifecycle = ProcessBrokerLifecycleMachine
                        .evaluate(lifecycle, ProcessBrokerLifecycleEvent.StartupFailed(failure.message ?: "worker startup failed"))
                        .state
                }
            }
            throw failure
        }
    }

    /** Test/diagnostics view of live (tracked) worker slots. */
    internal fun trackedWorkers(): Int = trackedSlots.size

    internal fun idleSize(): Int = idle.size

    internal fun lifecycleState(): ProcessBrokerLifecycle = lifecycle

    public fun echo(text: String): String {
        val payload = call(PortalMethod.Echo, text.toByteArray(StandardCharsets.UTF_8), emptyList())
        return payload.toString(StandardCharsets.UTF_8)
    }

    public fun invoke(
        method: PortalMethod,
        payload: ByteArray,
        vararg granted: Capability.ReadFd,
    ): ByteArray = call(method, payload, granted.toList())

    public fun checksum(fd: Capability.ReadFd): Int {
        val payload = call(PortalMethod.Checksum, ByteArray(0), listOf(fd))
        require(payload.size == 4) { "checksum must be 4 bytes" }
        return ((payload[0].toInt() and 0xff) shl 24) or
            ((payload[1].toInt() and 0xff) shl 16) or
            ((payload[2].toInt() and 0xff) shl 8) or
            (payload[3].toInt() and 0xff)
    }

    public fun openReadOnly(
        rootDir: java.nio.file.Path,
        relative: String,
    ): Capability.ReadFd = openGrantedRead(rootDir, relative)

    internal fun sleep(millis: Int) {
        val buf = ByteArray(4)
        buf[0] = (millis ushr 24).toByte()
        buf[1] = (millis ushr 16).toByte()
        buf[2] = (millis ushr 8).toByte()
        buf[3] = millis.toByte()
        call(PortalMethod.Sleep, buf, emptyList())
    }

    internal fun tryOpenHostPasswd() {
        call(PortalMethod.TryOpenHostPasswd, ByteArray(0), emptyList())
    }

    internal fun spawnedWorkers(): Int = spawned.get()

    internal fun crashIdleWorkerProcess() {
        val slot =
            idle.poll(callTimeoutMs, TimeUnit.MILLISECONDS)
                ?: throw PortalCallException("no idle worker to crash")
        slot.process.destroyForcibly()
        idle.put(slot)
    }

    internal fun call(
        method: PortalMethod,
        payload: ByteArray,
        fds: List<Capability.ReadFd>,
    ): ByteArray {
        check(lifecycle == ProcessBrokerLifecycle.Running) { "broker not running: $lifecycle" }
        val deadline = Deadline.afterMillis(callTimeoutMs)
        val slot =
            idle.poll(deadline.remainingMillis().toLong(), TimeUnit.MILLISECONDS)
                ?: throw PortalCallException("timed out waiting for an idle portal worker")
        var returnedToPool = false
        var callState: PortalBrokerCallState = PortalBrokerCallState.Idle
        return try {
            val id = nextId.getAndIncrement()
            val request = PortalFrame(PortalKind.Request, id, method, PortalPayload(payload), fds.size)
            callState = PortalBrokerCallMachine
                .evaluate(PortalBrokerCallState.Idle, PortalBrokerCallEvent.RequestSent(request))
                .state
            slot.channel.sendCapabilities(request, fds, deadline)
            val (reply, extra) = slot.channel.receive(deadline)
            Capability.closeAll(extra)
            val transition = PortalBrokerCallMachine.evaluate(callState, PortalBrokerCallEvent.ReplyReceived(reply))
            callState = transition.state
            when (val effect = transition.effect) {
                is PortalBrokerCallEffect.ReturnPayload -> {
                    returnToPoolOrDestroy(slot)
                    returnedToPool = true
                    effect.payload.copyToByteArray()
                }
                is PortalBrokerCallEffect.RemoteError -> {
                    returnToPoolOrDestroy(slot)
                    returnedToPool = true
                    throw PortalCallException(effect.message)
                }
                is PortalBrokerCallEffect.RecycleWorker -> throw PortalCallException(effect.reason)
                is PortalBrokerCallEffect.ProtocolViolation -> throw PortalCallException(effect.reason)
                PortalBrokerCallEffect.None -> throw PortalCallException("portal RPC produced no terminal result")
            }
        } catch (e: PortalCallException) {
            if (!returnedToPool) {
                scheduleRecycle(slot)
            }
            throw e
        } catch (expectedRpcFailure: Exception) {
            val transportFailure = PortalBrokerCallMachine.evaluate(callState, PortalBrokerCallEvent.TransportFailed)
            check(transportFailure.state == PortalBrokerCallState.Failed)
            if (!returnedToPool) {
                scheduleRecycle(slot)
            }
            throw PortalCallException("portal RPC failed", expectedRpcFailure)
        }
    }

    override fun close() {
        synchronized(lifecycleGate) {
            if (lifecycle == ProcessBrokerLifecycle.Closed || lifecycle == ProcessBrokerLifecycle.Closing) return
            val transition = ProcessBrokerLifecycleMachine.evaluate(lifecycle, ProcessBrokerLifecycleEvent.CloseRequested)
            check(transition is ProcessBrokerLifecycleTransition.Advanced) { (transition as ProcessBrokerLifecycleTransition.ProtocolViolation).reason }
            lifecycle = transition.state
        }
        maintenance.shutdownNow()
        try {
            if (!maintenance.awaitTermination(3, TimeUnit.SECONDS)) {
                logger.warning("portal maintenance did not terminate before broker cleanup")
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.log(Level.WARNING, "interrupted while stopping portal maintenance", interrupted)
        }
        val leftover = mutableListOf<WorkerSlot>()
        idle.drainTo(leftover)
        // Destroy checked-out slots too: a worker mid-call belongs to a broker that is gone.
        synchronized(trackedSlots) { leftover.addAll(trackedSlots) }
        val cleanupComplete = leftover.distinct().map { destroySlot(it) }.all { it }
        synchronized(lifecycleGate) {
            lifecycle = ProcessBrokerLifecycleMachine
                .evaluate(
                    lifecycle,
                    if (cleanupComplete) ProcessBrokerLifecycleEvent.CleanupSucceeded else ProcessBrokerLifecycleEvent.CleanupFailed("broker cleanup failed"),
                ).state
        }
    }

    /** Pool the slot unless the broker closed meanwhile; destroy orphans either way. */
    private fun returnToPoolOrDestroy(slot: WorkerSlot) {
        synchronized(lifecycleGate) {
            if (lifecycle != ProcessBrokerLifecycle.Running) {
                destroySlot(slot)
            } else {
                idle.put(slot)
            }
        }
    }

    private fun resolveWorkerClasspath(): String {
        val cp = workerClasspath.ifBlank { System.getProperty(WORKER_CLASSPATH_PROPERTY).orEmpty() }
        require(cp.isNotBlank()) {
            "portal worker classpath is required; set $WORKER_CLASSPATH_PROPERTY or pass workerClasspath"
        }
        return cp
    }

    @Suppress("TooGenericExceptionCaught") // Provisional ownership must roll back on every startup failure.
    private fun spawnWorker(deadline: Deadline = Deadline.afterMillis(startupTimeoutMs)): WorkerSlot {
        val ep = PrivateUnixEndpoint.create(launcher, "mazewall-portal-", "portal.sock")
        var listen: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
        var proc: Process? = null
        var peer: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
        var channel: PortalChannel? = null
        var pump: ChildStdoutPump? = null
        try {
            listen = sockets.createUnixServer(ep.path)
            val spec =
            JvmChildSpec(
                mainClass = "io.mazewall.portal.worker.PortalWorkerMain",
                mainArgs = listOf(ep.path),
                maxHeap = "64m",
                javaAgents = JavaAgentSelection.None,
                classpath = resolveWorkerClasspath(),
                extraJvmArgs = workerExtraJvmArgs,
            )
            proc = JvmChildProcess.start(launcher, spec)
            pump =
            JvmChildProcess.startStdoutPump(
                proc,
                "MAZEWALL_PORTAL_WORKER_READY",
                { line ->
                    System.err.println("[PORTAL-WORKER] $line")
                    runCatching {
                        java.io.File("/tmp/portalworker_err.log").appendText(line + "\n")
                    }
                },
                "portal-worker-stdout",
                { runCatching { System.err.println("[PORTAL-WORKER-EXIT] code=${proc.exitValue()}") } },
            )
            peer = acceptWithin(listen, proc, ep.path, deadline)
            check(proc.isAlive) { "portal worker exited during handshake" }
            channel = PortalChannel(peer, sockets)
            peer = null
            val remaining = deadline.remainingMillis()
            check(remaining > 0 && pump.ready.await(remaining.toLong(), TimeUnit.MILLISECONDS) && JvmChildProcess.awaitReady(pump, 0)) {
                "portal worker failed to become ready before startup deadline"
            }
            check(proc.isAlive) { "portal worker exited during readiness handshake" }
            spawned.incrementAndGet()
            val readyChannel = checkNotNull(channel)
            channel = null
            return WorkerSlot(proc, readyChannel, ep, listen)
        } catch (failure: Throwable) {
            channel?.let { runCatching { it.close() }.exceptionOrNull()?.let(failure::addSuppressed) }
            peer?.let { runCatching { sockets.close(it) }.exceptionOrNull()?.let(failure::addSuppressed) }
            proc?.let {
                runCatching { it.destroyForcibly() }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { it.waitFor(2, TimeUnit.SECONDS) }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            pump?.thread?.let { thread ->
                runCatching {
                    thread.interrupt()
                    thread.join(1_000)
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            listen?.let { runCatching { sockets.close(it) }.exceptionOrNull()?.let(failure::addSuppressed) }
            runCatching { ep.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun acceptWithin(
        listener: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        process: Process,
        socketPath: String,
        deadline: Deadline,
    ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> =
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val accepted = executor.submit<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>> { sockets.accept(listener) }
            var peer: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
            try {
                while (peer == null) {
                    check(process.isAlive) { "portal worker exited before connecting" }
                    val remaining = deadline.remainingMillis()
                    check(remaining > 0) { "portal worker connection timed out" }
                    try {
                        peer = accepted.get(minOf(remaining, 50).toLong(), TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        continue
                    } catch (failed: ExecutionException) {
                        throw (failed.cause as? Exception ?: failed)
                    }
                }
            } finally {
                if (!accepted.isDone) {
                    process.destroyForcibly()
                    runCatching {
                        java.nio.channels.SocketChannel
                            .open(java.net.UnixDomainSocketAddress.of(socketPath))
                            .close()
                    }
                    accepted.cancel(true)
                }
            }
            checkNotNull(peer)
        }

    private fun scheduleRecycle(dead: WorkerSlot) {
        try {
            maintenance.execute {
                runCatching {
                    check(destroySlot(dead)) { "failed to retire dead portal worker" }
                    if (lifecycle != ProcessBrokerLifecycle.Running) return@runCatching
                    val fresh = spawnWorker()
                    val published = synchronized(lifecycleGate) {
                        if (lifecycle == ProcessBrokerLifecycle.Running) {
                            trackedSlots.add(fresh)
                            idle.add(fresh)
                            true
                        } else {
                            false
                        }
                    }
                    if (!published) destroySlot(fresh)
                }.exceptionOrNull()?.let { failure ->
                    logger.log(Level.WARNING, "failed to replenish portal worker", failure)
                }
            }
        } catch (closed: java.util.concurrent.RejectedExecutionException) {
            logger.log(Level.WARNING, "portal worker retirement rejected during shutdown", closed)
            runCatching { destroySlot(dead) }.exceptionOrNull()?.let { failure ->
                logger.log(Level.WARNING, "failed to retire portal worker after maintenance shutdown", failure)
            }
        }
    }

    private fun destroySlot(slot: WorkerSlot): Boolean {
        synchronized(slot) {
            if (slot.fullyRetired) return true
            val cleanup = CleanupCollector()
            if (!slot.channelRetired) {
                cleanup.attempt("worker RPC channel") {
                    try {
                        slot.channel.close()
                        slot.channelRetired = true
                    } catch (terminal: io.mazewall.core.NativeCloseFailure) {
                        slot.channelRetired = true
                        throw terminal
                    }
                }
            }
            if (!slot.serverRetired) {
                cleanup.attempt("worker listener socket") {
                    try {
                        sockets.close(slot.server)
                        slot.serverRetired = true
                    } catch (terminal: io.mazewall.core.NativeCloseFailure) {
                        slot.serverRetired = true
                        throw terminal
                    }
                }
            }
            if (!slot.processDestroyed) {
                cleanup.attempt("worker process") {
                    slot.process.destroyForcibly()
                    slot.processDestroyed = true
                }
            }
            if (!slot.processReaped) {
                cleanup.attempt("worker process reap") {
                    check(slot.process.waitFor(2, TimeUnit.SECONDS)) { "portal worker did not exit after forced termination" }
                    slot.processReaped = true
                }
            }
            if (!slot.endpointRetired) {
                cleanup.attempt("worker endpoint") {
                    slot.endpoint.close()
                    slot.endpointRetired = true
                }
            }
            val outcome = cleanup.outcome()
            outcome.failures.forEach { failure ->
                logger.log(Level.WARNING, "failed to release ${failure.resource}", failure.cause)
            }
            if (outcome.complete) trackedSlots.remove(slot)
            return outcome.complete
        }
    }

    private class WorkerSlot(
        val process: Process,
        val channel: PortalChannel,
        val endpoint: PrivateUnixEndpoint,
        val server: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ) {
        var channelRetired: Boolean = false
        var serverRetired: Boolean = false
        var processDestroyed: Boolean = false
        var processReaped: Boolean = false
        var endpointRetired: Boolean = false
        val fullyRetired: Boolean
            get() = channelRetired && serverRetired && processDestroyed && processReaped && endpointRetired
    }
}
