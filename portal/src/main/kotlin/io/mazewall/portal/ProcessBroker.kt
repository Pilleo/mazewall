package io.mazewall.portal

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
import java.util.concurrent.CompletableFuture
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
 *
 * This public facade is also the ownership aggregate for startup attempts, checked-out workers,
 * and maintenance retirement; its helpers intentionally keep those resources private.
 * Closing stops admission and drains those owners. Failed cleanup is reported and remains
 * retryable; a caller must retain the broker until close succeeds.
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
        private const val LIFECYCLE_POLL_SLICE_MS = 50
        private const val OUTPUT_PUMP_JOIN_TIMEOUT_MS = 1_000L
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
    private val retainedCapabilities: MutableSet<Capability.ReadFd> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private val startupRetirement = StartupRetirement(sockets)
    private val closeRetirement = CloseRetirement()

    private val lifecycleGate = Any()

    @Volatile private var startupCancelled = false

    @Volatile private var startupCompletion: CountDownLatch? = null

    @Volatile private var closeOperation: CloseOperation? = null

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

    @Suppress("TooGenericExceptionCaught") // Every fatal checkout failure must retire its process before preserving the original Error.
    internal fun call(
        method: PortalMethod,
        payload: ByteArray,
        fds: List<Capability.ReadFd>,
    ): ByteArray {
        check(lifecycle == ProcessBrokerLifecycle.Running) { "broker not running: $lifecycle" }
        val deadline = Deadline.afterMillis(callTimeoutMs)
        val slot = try {
            idle.poll(deadline.remainingMillis().toLong(), TimeUnit.MILLISECONDS)
                ?: throw PortalCallException("timed out waiting for an idle portal worker")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PortalCallException("interrupted while waiting for an idle portal worker", interrupted)
        }
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
            retireCapabilities(extra).failureOrNull()?.let(::raiseBrokerFailure)
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
                is PortalBrokerCallEffect.PostTerminalObservation ->
                    throw PortalCallException("portal RPC observed ${effect.event} after ${effect.state}")
                PortalBrokerCallEffect.AwaitReply -> throw PortalCallException("portal RPC produced no terminal result")
            }
        } catch (e: PortalCallException) {
            if (!returnedToPool) {
                scheduleRecycle(slot)
            }
            throw e
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            val failure = PortalCallException("portal RPC interrupted", interrupted)
            if (!returnedToPool) {
                runCatching { destroySlot(slot) }.exceptionOrNull()?.takeIf { it !== failure }?.let(failure::addSuppressed)
                runCatching { scheduleRecycle(slot) }.exceptionOrNull()?.takeIf { it !== failure }?.let(failure::addSuppressed)
            }
            Thread.currentThread().interrupt()
            throw failure
        } catch (expectedRpcFailure: Exception) {
            val transportFailure = PortalBrokerCallMachine.evaluate(callState, PortalBrokerCallEvent.TransportFailed)
            check(transportFailure.state == PortalBrokerCallState.Failed)
            if (!returnedToPool) {
                scheduleRecycle(slot)
            }
            throw PortalCallException("portal RPC failed", expectedRpcFailure)
        } catch (fatal: Error) {
            if (!returnedToPool) {
                val cleanup = runCatching { destroySlot(slot) }.exceptionOrNull()
                cleanup?.takeIf { it !== fatal }?.let(fatal::addSuppressed)
                runCatching { scheduleRecycle(slot) }.exceptionOrNull()?.takeIf { it !== fatal }?.let(fatal::addSuppressed)
            }
            throw fatal
        }
    }

    override fun close() {
        val preparation = prepareClose()
        preparation.waitForOtherClose?.let { operation ->
            awaitCloseOperation(operation).raiseIfFailed()
            return
        }
        if (!preparation.executeCleanup) return
        val startupToJoin = preparation.startupToJoin
        val operation = checkNotNull(preparation.operation)
        val cleanup = CleanupCollector()
        var allRetired = false
        var failure: Throwable? = null
        try {
            allRetired = retireOwnedResources(cleanup, startupToJoin, operation.deadline)
            val outcome = cleanup.outcome()
            failure = outcome.failureOrNull()
            if (failure == null && !allRetired) {
                failure = IncompleteBrokerCleanupException(
                    "ProcessBroker close did not retire every owned resource",
                    outcome.failures.map { BrokerCleanupFailure(it.resource, it.cause) },
                )
            }
        } catch (unexpected: Throwable) {
            failure = unexpected
        } finally {
            synchronized(lifecycleGate) {
                lifecycle = ProcessBrokerLifecycleMachine
                    .evaluate(
                        lifecycle,
                        if (allRetired && failure == null) {
                            ProcessBrokerLifecycleEvent.CleanupSucceeded
                        } else {
                            ProcessBrokerLifecycleEvent.CleanupFailed("broker cleanup failed")
                        },
                    ).state
                operation.result.complete(failure?.let(BrokerCloseResult::Failed) ?: BrokerCloseResult.Succeeded)
            }
        }
        failure?.let(::raiseBrokerFailure)
    }

    /** Retires every ownership registry after admission has been closed. */
    private fun retireOwnedResources(
        cleanup: CleanupCollector,
        startupToJoin: CountDownLatch?,
        deadline: Deadline,
    ): Boolean {
        cleanup.attempt("maintenance shutdown") { maintenance.shutdownNow() }
        val maintenanceStopped = awaitMaintenance(deadline)
        retireRetainedCapabilities(cleanup)
        closeRetirement.retireHandoffs(cleanup)
        provisionalSlots.toList().forEach { slot ->
            cleanup.attempt("provisional worker") { destroySlot(slot) }
        }
        val startupStopped = awaitStartup(startupToJoin, deadline)
        closeRetirement.retireAllSlots(cleanup)
        provisionalAttempts.toList().forEach { attempt ->
            cleanup.attempt("startup acquisition") { retireStartupAttempt(attempt) }
        }
        closeRetirement.retireHandoffs(cleanup)
        return maintenanceStopped && startupStopped && closeRetirement.allOwnedResourcesRetired(cleanup)
    }

    /** Keeps close-only ownership retirement separate from public broker operations. */
    private inner class CloseRetirement {
        fun retireHandoffs(cleanup: CleanupCollector) {
            acceptedHandoffs.toList().forEach { handoff ->
                cleanup.attempt("accepted socket handoff") { retireHandoff(handoff) }
            }
        }

        fun retireAllSlots(cleanup: CleanupCollector) {
            val leftover = mutableListOf<WorkerSlot>()
            idle.drainTo(leftover)
            // Destroy checked-out slots too: a worker mid-call belongs to a broker that is gone.
            synchronized(trackedSlots) { leftover.addAll(trackedSlots) }
            leftover.addAll(provisionalSlots)
            leftover.distinct().forEach { slot -> cleanup.attempt("worker") { destroySlot(slot) } }
        }

        fun allOwnedResourcesRetired(cleanup: CleanupCollector): Boolean =
            trackedSlots.isEmpty() &&
                idle.isEmpty() &&
                provisionalSlots.isEmpty() &&
                provisionalAttempts.isEmpty() &&
                acceptedHandoffs.isEmpty() &&
                retainedCapabilities.isEmpty() &&
                cleanup.outcome().complete
    }

    private fun prepareClose(): ClosePreparation =
        synchronized(lifecycleGate) {
            if (lifecycle == ProcessBrokerLifecycle.Closed) return@synchronized ClosePreparation.alreadyClosed()
            if (lifecycle == ProcessBrokerLifecycle.Closing) return@synchronized ClosePreparation.waitFor(checkNotNull(closeOperation))
            val transition = ProcessBrokerLifecycleMachine.evaluate(lifecycle, ProcessBrokerLifecycleEvent.CloseRequested)
            check(transition is ProcessBrokerLifecycleTransition.Advanced) { (transition as ProcessBrokerLifecycleTransition.ProtocolViolation).reason }
            lifecycle = transition.state
            startupCancelled = true
            val operation = CloseOperation(Deadline.afterMillis(startupTimeoutMs), CompletableFuture())
            closeOperation = operation
            ClosePreparation.execute(startupCompletion, operation)
        }

    private fun awaitStartup(
        completion: CountDownLatch?,
        deadline: Deadline,
    ): Boolean {
        if (completion == null || completion.count == 0L) return true
        val result = runCatching { completion.await(deadline.remainingMillis().coerceAtLeast(0).toLong(), TimeUnit.MILLISECONDS) }
        if (result.exceptionOrNull() is InterruptedException) Thread.currentThread().interrupt()
        return result.getOrDefault(false)
    }

    private fun awaitMaintenance(deadline: Deadline): Boolean {
        val result = runCatching { maintenance.awaitTermination(deadline.remainingMillis().coerceAtLeast(0).toLong(), TimeUnit.MILLISECONDS) }
        if (result.exceptionOrNull() is InterruptedException) Thread.currentThread().interrupt()
        val stopped = result.getOrDefault(false)
        if (!stopped) logger.warning("portal maintenance did not terminate before broker cleanup")
        return stopped
    }

    private fun retireCapabilities(capabilities: List<Capability.ReadFd>): CapabilityRetirementOutcome {
        retainedCapabilities.addAll(capabilities)
        val outcome = Capability.retireAll(capabilities)
        capabilities.filter(Capability.ReadFd::isRetired).forEach(retainedCapabilities::remove)
        return outcome
    }

    private fun retireRetainedCapabilities(cleanup: CleanupCollector) {
        if (retainedCapabilities.isEmpty()) return
        cleanup.attempt("received portal capabilities") {
            retireCapabilities(retainedCapabilities.toList()).failureOrNull()?.let(::raiseBrokerFailure)
        }
    }

    private fun awaitCloseOperation(operation: CloseOperation): BrokerCloseResult =
        try {
            val remaining = operation.deadline
                .remainingMillis()
                .coerceAtLeast(0)
                .toLong()
            operation.result.get(remaining, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PortalCallException("interrupted while waiting for broker cleanup", interrupted)
        } catch (timedOut: TimeoutException) {
            throw IncompleteBrokerCleanupException(
                "ProcessBroker close did not finish before its shared deadline",
                listOf(BrokerCleanupFailure("concurrent broker close", timedOut)),
            )
        } catch (failed: ExecutionException) {
            throw (failed.cause ?: failed)
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
            val pump = PortalWorkerOutput(process.inputStream)
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
                pump.ready.await(minOf(remaining, LIFECYCLE_POLL_SLICE_MS).toLong(), TimeUnit.MILLISECONDS)
            }
            check(pump.readinessObserved) { "portal worker failed readiness validation" }
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
            attempt.pump?.snapshot()?.let { diagnostics ->
                failure.addSuppressed(IllegalStateException("portal worker startup diagnostics: $diagnostics"))
            }
            throwWithCleanupFailure(failure, cleanupFailure)
        }
    }

    private fun retireStartupAttempt(attempt: StartupAttempt): Boolean {
        synchronized(attempt) {
            if (attempt.transferred) return true
            attempt.retirementRequested = true
            val cleanup = CleanupCollector()
            startupRetirement.retireChannel(attempt, cleanup)
            startupRetirement.retireProcess(attempt, cleanup)
            startupRetirement.retirePump(attempt, cleanup)
            startupRetirement.retireServer(attempt, cleanup)
            startupRetirement.retireEndpoint(attempt, cleanup)
            val outcome = cleanup.outcome()
            outcome.failures.forEach { logger.log(Level.WARNING, "failed to release ${it.resource}", it.cause) }
            if (attempt.fullyRetired) provisionalAttempts.remove(attempt)
            outcome.failureOrNull()?.let { throw it }
            return attempt.fullyRetired
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
                        accepted.get(minOf(remaining, LIFECYCLE_POLL_SLICE_MS).toLong(), TimeUnit.MILLISECONDS)
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

    // Each throw records an independent close result while preserving terminal native-close
    // semantics; collapsing them would risk retrying a descriptor whose integer was reused.
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
                        raiseBrokerFailure(terminal)
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
                        raiseBrokerFailure(terminal)
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
                    slot.pump.thread.join(OUTPUT_PUMP_JOIN_TIMEOUT_MS)
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
            outcome.failureOrNull()?.let(::raiseBrokerFailure)
            return slot.fullyRetired
        }
    }

    private class WorkerSlot(
        val process: Process,
        val channel: PortalChannel,
        val endpoint: PrivateUnixEndpoint,
        val server: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        val pump: PortalWorkerOutput,
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

    private data class ClosePreparation(
        val executeCleanup: Boolean,
        val waitForOtherClose: CloseOperation?,
        val startupToJoin: CountDownLatch?,
        val operation: CloseOperation?,
    ) {
        companion object {
            fun alreadyClosed(): ClosePreparation = ClosePreparation(false, null, null, null)

            fun waitFor(operation: CloseOperation): ClosePreparation = ClosePreparation(false, operation, null, null)

            fun execute(
                startup: CountDownLatch?,
                operation: CloseOperation,
            ): ClosePreparation = ClosePreparation(true, null, startup, operation)
        }
    }

    private data class CloseOperation(
        val deadline: Deadline,
        val result: CompletableFuture<BrokerCloseResult>,
    )

    private sealed interface BrokerCloseResult {
        data object Succeeded : BrokerCloseResult

        data class Failed(
            val failure: Throwable,
        ) : BrokerCloseResult

        fun raiseIfFailed() {
            when (this) {
                Succeeded -> Unit
                is Failed -> raiseBrokerFailure(failure)
            }
        }
    }

    private class StartupRetirement(
        private val sockets: SocketManager,
    ) {
        fun retireChannel(
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

        fun retireProcess(
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

        fun retirePump(
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
                pump.thread.join(OUTPUT_PUMP_JOIN_TIMEOUT_MS)
                check(!pump.thread.isAlive) { "provisional portal worker output pump did not exit" }
                attempt.pumpRetired = true
            }
        }

        fun retireServer(
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

        fun retireEndpoint(
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
                raiseBrokerFailure(terminal)
            }
        }
    }

    private class StartupAttempt(
        val endpoint: PrivateUnixEndpoint,
    ) {
        var server: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
        var process: Process? = null
        var pump: PortalWorkerOutput? = null
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

/**
 * [ProcessBroker.close] could not prove that every process, channel, endpoint, and maintenance
 * action reached a terminal state. Individual resource failures are retained for diagnostics.
 */
public class IncompleteBrokerCleanupException(
    message: String,
    public val failures: List<BrokerCleanupFailure>,
) : IllegalStateException(message)

/** A named resource cleanup failure retained by [IncompleteBrokerCleanupException]. */
public data class BrokerCleanupFailure(
    val resource: String,
    val cause: Throwable,
)

private fun raiseBrokerFailure(failure: Throwable): Nothing = throw failure

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
