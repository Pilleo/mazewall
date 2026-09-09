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
import java.util.concurrent.CountDownLatch
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
    private val provisionalSlots: MutableSet<WorkerSlot> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private val provisionalAttempts: MutableSet<StartupAttempt> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private val acceptedHandoffs: MutableSet<AcceptedSocketHandoff> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    private val lifecycleGate = Any()

    @Volatile private var startupCancelled = false

    @Volatile private var startupCompletion: CountDownLatch? = null

    @Volatile private var closeCompletion: CountDownLatch? = null

    @Volatile private var lifecycle: ProcessBrokerLifecycle = ProcessBrokerLifecycle.New

    @Suppress("TooGenericExceptionCaught") // Transaction rollback must run for every acquisition failure.
    public fun start() {
        synchronized(lifecycleGate) {
            val transition = ProcessBrokerLifecycleMachine.evaluate(lifecycle, ProcessBrokerLifecycleEvent.StartRequested)
            check(transition is ProcessBrokerLifecycleTransition.Advanced) { (transition as ProcessBrokerLifecycleTransition.ProtocolViolation).reason }
            lifecycle = transition.state
            startupCancelled = false
            startupCompletion = CountDownLatch(1)
        }
        val deadline = Deadline.afterMillis(startupTimeoutMs)
        val acquired = ArrayList<WorkerSlot>(poolSize)
        try {
            repeat(poolSize) {
                checkStartupActive()
                val slot = spawnWorker(deadline, ::startupIsActive)
                provisionalSlots.add(slot)
                acquired += slot
                checkStartupActive()
            }
            synchronized(lifecycleGate) {
                check(lifecycle == ProcessBrokerLifecycle.Starting) { "broker closed during startup" }
                acquired.forEach {
                    provisionalSlots.remove(it)
                    trackedSlots.add(it)
                }
                acquired.forEach { idle.add(it) }
                lifecycle = ProcessBrokerLifecycleMachine
                    .evaluate(lifecycle, ProcessBrokerLifecycleEvent.StartupSucceeded)
                    .state
            }
        } catch (failure: Throwable) {
            val cleanup = CleanupCollector()
            acquired.forEach { slot ->
                cleanup.attempt("startup worker rollback") { destroySlot(slot) }
            }
            synchronized(lifecycleGate) {
                if (lifecycle == ProcessBrokerLifecycle.Starting) {
                    lifecycle = ProcessBrokerLifecycleMachine
                        .evaluate(lifecycle, ProcessBrokerLifecycleEvent.StartupFailed(failure.message ?: "worker startup failed"))
                        .state
                }
            }
            val cleanupFailure = cleanup.outcome().failureOrNull()
            throwWithCleanupFailure(failure, cleanupFailure)
        } finally {
            startupCompletion?.countDown()
        }
    }

    /** Test/diagnostics view of live (tracked) worker slots. */
    internal fun trackedWorkers(): Int = trackedSlots.size

    internal fun idleSize(): Int = idle.size

    internal fun lifecycleState(): ProcessBrokerLifecycle = lifecycle

    internal fun provisionalWorkers(): Int = provisionalSlots.size + provisionalAttempts.size

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
        var waitForOtherClose: CountDownLatch? = null
        var startupToJoin: CountDownLatch? = null
        var completionToSignal: CountDownLatch? = null
        synchronized(lifecycleGate) {
            if (lifecycle == ProcessBrokerLifecycle.Closed) return
            if (lifecycle == ProcessBrokerLifecycle.Closing) {
                waitForOtherClose = closeCompletion
            } else {
            val transition = ProcessBrokerLifecycleMachine.evaluate(lifecycle, ProcessBrokerLifecycleEvent.CloseRequested)
            check(transition is ProcessBrokerLifecycleTransition.Advanced) { (transition as ProcessBrokerLifecycleTransition.ProtocolViolation).reason }
            lifecycle = transition.state
                startupCancelled = true
                startupToJoin = startupCompletion
                completionToSignal = CountDownLatch(1)
                closeCompletion = completionToSignal
            }
        }
        waitForOtherClose?.let {
            it.await()
            return
        }
        val cleanup = CleanupCollector()
        var maintenanceStopped = false
        var startupStopped = false
        var allRetired = false
        try {
            cleanup.attempt("maintenance shutdown") { maintenance.shutdownNow() }
            maintenanceStopped = awaitMaintenance()
            acceptedHandoffs.toList().forEach { handoff ->
                cleanup.attempt("accepted socket handoff") { retireHandoff(handoff) }
            }
            provisionalSlots.toList().forEach { slot ->
                cleanup.attempt("provisional worker") { destroySlot(slot) }
            }
            startupStopped = awaitStartup(startupToJoin)
            val leftover = mutableListOf<WorkerSlot>()
            idle.drainTo(leftover)
            // Destroy checked-out slots too: a worker mid-call belongs to a broker that is gone.
            synchronized(trackedSlots) { leftover.addAll(trackedSlots) }
            leftover.addAll(provisionalSlots)
            leftover.distinct().forEach { slot ->
                cleanup.attempt("worker") { destroySlot(slot) }
            }
            provisionalAttempts.toList().forEach { attempt ->
                cleanup.attempt("startup acquisition") { retireStartupAttempt(attempt) }
            }
            acceptedHandoffs.toList().forEach { handoff ->
                cleanup.attempt("accepted socket handoff") { retireHandoff(handoff) }
            }
            allRetired =
                maintenanceStopped &&
                    startupStopped &&
                    provisionalSlots.isEmpty() &&
                    provisionalAttempts.isEmpty() &&
                    acceptedHandoffs.isEmpty() &&
                    cleanup.outcome().complete
        } finally {
            synchronized(lifecycleGate) {
                try {
                    lifecycle = ProcessBrokerLifecycleMachine
                        .evaluate(
                            lifecycle,
                            if (allRetired) {
                                ProcessBrokerLifecycleEvent.CleanupSucceeded
                            } else {
                                ProcessBrokerLifecycleEvent.CleanupFailed("broker cleanup failed")
                            },
                        ).state
                } finally {
                    completionToSignal?.countDown()
                }
            }
        }
        cleanup.outcome().failureOrNull()?.let { throw it }
    }

    private fun awaitStartup(completion: CountDownLatch?): Boolean {
        if (completion == null || completion.count == 0L) return true
        val result = runCatching { completion.await(startupTimeoutMs, TimeUnit.MILLISECONDS) }
        if (result.exceptionOrNull() is InterruptedException) Thread.currentThread().interrupt()
        return result.getOrDefault(false)
    }

    private fun awaitMaintenance(): Boolean {
        val result = runCatching { maintenance.awaitTermination(startupTimeoutMs, TimeUnit.MILLISECONDS) }
        if (result.exceptionOrNull() is InterruptedException) Thread.currentThread().interrupt()
        val stopped = result.getOrDefault(false)
        if (!stopped) logger.warning("portal maintenance did not terminate before broker cleanup")
        return stopped
    }

    private fun startupIsActive(): Boolean = lifecycle == ProcessBrokerLifecycle.Starting && !startupCancelled

    private fun checkStartupActive() {
        check(startupIsActive()) { "broker startup cancelled" }
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
    private fun spawnWorker(
        deadline: Deadline = Deadline.afterMillis(startupTimeoutMs),
        active: () -> Boolean = { lifecycle == ProcessBrokerLifecycle.Running },
    ): WorkerSlot {
        val ep = PrivateUnixEndpoint.create(launcher, "mazewall-portal-", "portal.sock")
        val attempt = StartupAttempt(ep)
        provisionalAttempts.add(attempt)
        try {
            check(active()) { "portal worker acquisition cancelled" }
            val server = sockets.createUnixServer(ep.path)
            synchronized(attempt) { attempt.server = server }
            check(active()) { "portal worker acquisition cancelled" }
            val spec =
            JvmChildSpec(
                mainClass = "io.mazewall.portal.worker.PortalWorkerMain",
                mainArgs = listOf(ep.path),
                maxHeap = "64m",
                javaAgents = JavaAgentSelection.None,
                classpath = resolveWorkerClasspath(),
                extraJvmArgs = workerExtraJvmArgs,
            )
            val process = JvmChildProcess.start(launcher, spec)
            synchronized(attempt) { attempt.process = process }
            check(active()) { "portal worker acquisition cancelled" }
            val pump =
            JvmChildProcess.startStdoutPump(
                process,
                "MAZEWALL_PORTAL_WORKER_READY",
                { line ->
                    System.err.println("[PORTAL-WORKER] $line")
                    runCatching {
                        java.io.File("/tmp/portalworker_err.log").appendText(line + "\n")
                    }
                },
                "portal-worker-stdout",
                { runCatching { System.err.println("[PORTAL-WORKER-EXIT] code=${process.exitValue()}") } },
            )
            synchronized(attempt) { attempt.pump = pump }
            val peer = acceptWithin(server, process, ep.path, deadline, active)
            synchronized(attempt) { attempt.peer = peer }
            check(process.isAlive) { "portal worker exited during handshake" }
            check(active()) { "portal worker acquisition cancelled" }
            val channel = PortalChannel(peer, sockets)
            synchronized(attempt) {
                attempt.channel = channel
                attempt.peer = null
            }
            while (pump.ready.count > 0L) {
                check(active()) { "portal worker acquisition cancelled" }
                val remaining = deadline.remainingMillis()
                check(remaining > 0) { "portal worker failed to become ready before startup deadline" }
                pump.ready.await(minOf(remaining, 50).toLong(), TimeUnit.MILLISECONDS)
            }
            check(JvmChildProcess.awaitReady(pump, 0)) { "portal worker failed readiness validation" }
            check(process.isAlive) { "portal worker exited during readiness handshake" }
            check(active()) { "portal worker acquisition cancelled" }
            val slot = WorkerSlot(
                process,
                channel,
                attempt.endpoint,
                server,
                pump,
            )
            synchronized(attempt) {
                check(active() && !attempt.retirementRequested) { "portal worker acquisition cancelled before publication" }
                attempt.acquisitionFinished = true
                attempt.transferred = true
            }
            spawned.incrementAndGet()
            provisionalAttempts.remove(attempt)
            return slot
        } catch (failure: Throwable) {
            synchronized(attempt) { attempt.acquisitionFinished = true }
            val cleanupFailure = runCatching { retireStartupAttempt(attempt) }.exceptionOrNull()
            throwWithCleanupFailure(failure, cleanupFailure)
        }
    }

    private fun retireStartupAttempt(attempt: StartupAttempt): Boolean {
        synchronized(attempt) {
            if (attempt.transferred) return true
            attempt.retirementRequested = true
            val cleanup = CleanupCollector()
            retireStartupChannel(attempt, cleanup)
            retireStartupProcess(attempt, cleanup)
            retireStartupPump(attempt, cleanup)
            retireStartupServer(attempt, cleanup)
            retireStartupEndpoint(attempt, cleanup)
            val outcome = cleanup.outcome()
            outcome.failures.forEach { logger.log(Level.WARNING, "failed to release ${it.resource}", it.cause) }
            if (attempt.fullyRetired) provisionalAttempts.remove(attempt)
            outcome.failureOrNull()?.let { throw it }
            return attempt.fullyRetired
        }
    }

    private fun retireStartupChannel(
        attempt: StartupAttempt,
        cleanup: CleanupCollector,
    ) {
        if (attempt.channelRetired) return
        val action: (() -> Unit)? = when {
            attempt.channel != null -> ({ attempt.channel?.close() })
            attempt.peer != null -> ({ attempt.peer?.let(sockets::close) })
            else -> null
        }
        if (action == null && attempt.acquisitionFinished) {
            attempt.channelRetired = true
            return
        }
        if (action == null) return
        cleanup.attempt("provisional worker channel") {
            terminalClose({ action() }) { attempt.channelRetired = true }
        }
    }

    private fun retireStartupProcess(
        attempt: StartupAttempt,
        cleanup: CleanupCollector,
    ) {
        val process = attempt.process
        if (!attempt.processDestroyed) {
            if (process == null && attempt.acquisitionFinished) {
                attempt.processDestroyed = true
                attempt.processReaped = true
            } else if (process != null) {
                cleanup.attempt("provisional worker process") {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                    attempt.processDestroyed = true
                }
            }
        }
        if (!attempt.processReaped && process != null) {
            cleanup.attempt("provisional worker process reap") {
                check(process.waitFor(2, TimeUnit.SECONDS)) { "provisional portal worker did not exit" }
                attempt.processReaped = true
            }
        }
    }

    private fun retireStartupPump(
        attempt: StartupAttempt,
        cleanup: CleanupCollector,
    ) {
        if (attempt.pumpRetired) return
        val pump = attempt.pump
        if (pump == null && attempt.acquisitionFinished) {
            attempt.pumpRetired = true
            return
        }
        if (pump == null) return
        attempt.process?.let { process ->
            cleanup.attempt("provisional worker output stream") { process.inputStream.close() }
        }
        cleanup.attempt("provisional worker output pump") {
            pump.thread.interrupt()
            pump.thread.join(1_000)
            check(!pump.thread.isAlive) { "provisional portal worker output pump did not exit" }
            attempt.pumpRetired = true
        }
    }

    private fun retireStartupServer(
        attempt: StartupAttempt,
        cleanup: CleanupCollector,
    ) {
        if (attempt.serverRetired) return
        val server = attempt.server
        if (server == null && attempt.acquisitionFinished) {
            attempt.serverRetired = true
            return
        }
        if (server == null) return
        cleanup.attempt("provisional worker listener") {
            terminalClose({ sockets.close(server) }) { attempt.serverRetired = true }
        }
    }

    private fun retireStartupEndpoint(
        attempt: StartupAttempt,
        cleanup: CleanupCollector,
    ) {
        if (attempt.endpointRetired) return
        cleanup.attempt("provisional worker endpoint") {
            attempt.endpoint.close()
            attempt.endpointRetired = true
        }
    }

    private inline fun terminalClose(
        action: () -> Unit,
        retired: () -> Unit,
    ) {
        try {
            action()
            retired()
        } catch (terminal: io.mazewall.core.NativeCloseFailure) {
            retired()
            throw terminal
        }
    }

    private fun acceptWithin(
        listener: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        process: Process,
        socketPath: String,
        deadline: Deadline,
        active: () -> Boolean,
    ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val handoff = AcceptedSocketHandoff(sockets::close)
        acceptedHandoffs.add(handoff)
        val accepted = executor.submit {
            try {
                handoff.publish(sockets.accept(listener))
            } finally {
                handoff.completeProducer()
            }
        }
        var claimed: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
        try {
            while (claimed == null) {
                check(active()) { "portal worker connection cancelled" }
                claimed = handoff.claim()
                if (claimed == null) {
                    check(process.isAlive) { "portal worker exited before connecting" }
                    val remaining = deadline.remainingMillis()
                    check(remaining > 0) { "portal worker connection timed out" }
                    try {
                        accepted.get(minOf(remaining, 50).toLong(), TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        continue
                    } catch (failed: ExecutionException) {
                        throw (failed.cause ?: failed)
                    }
                }
            }
            acceptedHandoffs.remove(handoff)
            return checkNotNull(claimed)
        } finally {
            if (claimed == null) {
                runCatching { handoff.cancel() }
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                runCatching {
                    java.nio.channels.SocketChannel
                        .open(java.net.UnixDomainSocketAddress.of(socketPath))
                        .use { }
                }
                accepted.cancel(true)
                executor.shutdownNow()
                runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
                retireHandoff(handoff)
            } else {
                executor.shutdown()
            }
        }
    }

    private fun retireHandoff(handoff: AcceptedSocketHandoff) {
        val failure = runCatching { handoff.cancel() }.exceptionOrNull()
        failure?.let {
            acceptedHandoffs.add(handoff)
            logger.log(Level.WARNING, "failed to retire accepted portal socket", failure)
        }
        if (handoff.fullyRetired) acceptedHandoffs.remove(handoff)
        failure?.let { throw it }
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
                    slot.process.descendants().forEach { it.destroyForcibly() }
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
            if (!slot.pumpRetired) {
                cleanup.attempt("worker output stream") { slot.process.inputStream.close() }
                cleanup.attempt("worker output pump") {
                    slot.pump.thread.interrupt()
                    slot.pump.thread.join(1_000)
                    check(!slot.pump.thread.isAlive) { "portal worker output pump did not exit" }
                    slot.pumpRetired = true
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
            if (slot.fullyRetired) {
                trackedSlots.remove(slot)
                provisionalSlots.remove(slot)
            }
            outcome.failureOrNull()?.let { throw it }
            return slot.fullyRetired
        }
    }

    private class WorkerSlot(
        val process: Process,
        val channel: PortalChannel,
        val endpoint: PrivateUnixEndpoint,
        val server: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        val pump: ChildStdoutPump,
    ) {
        var channelRetired: Boolean = false
        var serverRetired: Boolean = false
        var processDestroyed: Boolean = false
        var processReaped: Boolean = false
        var pumpRetired: Boolean = false
        var endpointRetired: Boolean = false
        val fullyRetired: Boolean
            get() = channelRetired && serverRetired && processDestroyed && processReaped && pumpRetired && endpointRetired
    }

    private class StartupAttempt(
        val endpoint: PrivateUnixEndpoint,
    ) {
        var server: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
        var process: Process? = null
        var pump: ChildStdoutPump? = null
        var peer: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
        var channel: PortalChannel? = null
        var acquisitionFinished = false
        var retirementRequested = false
        var transferred = false
        var channelRetired = false
        var serverRetired = false
        var processDestroyed = false
        var processReaped = false
        var pumpRetired = false
        var endpointRetired = false
        val fullyRetired: Boolean
            get() =
                acquisitionFinished &&
                    channelRetired &&
                    serverRetired &&
                    processDestroyed &&
                    processReaped &&
                    pumpRetired &&
                    endpointRetired
    }
}

@Suppress("InstanceOfCheckForException") // Cleanup aggregation deliberately promotes fatal failures.
private fun throwWithCleanupFailure(
    primary: Throwable,
    cleanup: Throwable?,
): Nothing {
    if (cleanup is Error && primary !is Error) {
        cleanup.addSuppressed(primary)
        throw cleanup
    }
    cleanup?.takeIf { it !== primary }?.let(primary::addSuppressed)
    throw primary
}
