package io.mazewall.portal

import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState
import io.mazewall.enforcer.api.execution.ExecutionDiagnostics
import io.mazewall.enforcer.api.execution.ExecutionGeneration
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.process.JavaAgentSelection
import io.mazewall.platform.process.JvmChildProcess
import io.mazewall.platform.process.JvmChildSpec
import io.mazewall.platform.process.JvmLaunchPreparation
import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.platform.process.destroyForciblyWithDescendants
import io.mazewall.platform.socket.PrivateUnixEndpoint
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.platform.socket.SocketManager
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
 * Placement: pooled worker JVMs plus capability FDs. Not a USER_NOTIF supervisor.
 * Owns a pool of portal worker JVMs and Unix RPC sockets.
 * Spawn workers before the broker installs process-wide seccomp.
 *
 * This public facade is also the ownership aggregate for startup attempts, checked-out workers,
 * and maintenance retirement; its helpers intentionally keep those resources private.
 * Closing stops admission and drains those owners. Failed cleanup is reported and remains
 * retryable; a caller must retain the broker until close succeeds.
 */
public class ProcessBroker internal constructor(
    private val limits: PortalRuntimeLimits = PortalRuntimeLimits(),
    private val sockets: SocketManager = RealSocketManager,
    private val launcher: ProcessLauncher = RealProcessLauncher,
    private val workerClasspath: String = defaultWorkerClasspath(),
    private val workerProfile: PortalWorkerProfile = PortalWorkerProfile(),
    private val cgroupRoot: java.nio.file.Path? = null,
    private val grantRoots: PortalGrantRoots = PortalGrantRoots.empty(),
    private val instanceMode: PortalInstanceMode = PortalInstanceMode.STATELESS,
    private val workerLaunch: PortalWorkerLaunch? = null,
    private val launchProfile: PortalLaunchProfile = PortalLaunchProfile(),
) : AutoCloseable {
    private val launchContract = launchProfile.contract()

    init {
        require(instanceMode != PortalInstanceMode.STATEFUL_SINGLETON || limits.workers == 1) { "stateful Portal groups require exactly one worker" }
        require(!workerProfile.requiresCgroup || cgroupRoot != null) {
            "limited portal worker profile requires a delegated cgroup root"
        }
    }

    public companion object {
        private const val LIFECYCLE_POLL_SLICE_MS = 50
        private const val OUTPUT_PUMP_JOIN_TIMEOUT_MS = 1_000L

        /** Legacy system-property injection is test-only and cannot affect production resolution. */
        private fun defaultWorkerClasspath(): String =
            if (System.getProperty("io.mazewall.test") == "true") {
                System.getProperty("io.mazewall.portal.worker.classpath").orEmpty()
            } else {
                ""
            }
    }

    private val logger: Logger = Logger.getLogger(ProcessBroker::class.java.name)
    private val cgroupController: PortalCgroupController = RealPortalCgroupController

    private val nextId = AtomicInteger(1)
    private val idle = ArrayBlockingQueue<WorkerSlot>(limits.workers)
    private val admission = PortalCallAdmission(limits)
    internal val operations = PortalOperationOwner(this, limits, admission)
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
    private val startupRetirement = StartupRetirement(sockets, cgroupController)
    private val closeRetirement = CloseRetirement()
    private val socketAcceptance = SocketAcceptance()
    private val generationCaptures = PortalGenerationCaptures()

    @Volatile private var mostRecentInvocation: PortalInvocationTiming? = null

    @Volatile private var mostRecentWorkerLifecycle: PortalWorkerLifecycle = PortalWorkerLifecycle.notStarted()

    @Volatile private var mostRecentGeneration: ExecutionGeneration = ExecutionGeneration.Unavailable

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
        val deadline = Deadline.afterMillis(limits.startupTimeoutMs)
        val acquired = ArrayList<WorkerSlot>(limits.workers)
        try {
            repeat(limits.workers) {
                check(lifecycle == ProcessBrokerLifecycle.Starting && !startupCancelled) { "broker startup cancelled" }
                val slot = spawnWorker(deadline) { lifecycle == ProcessBrokerLifecycle.Starting && !startupCancelled }
                provisionalSlots.add(slot)
                acquired += slot
                check(lifecycle == ProcessBrokerLifecycle.Starting && !startupCancelled) { "broker startup cancelled" }
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

    /** Grants a read-only descriptor below a root authorized at broker startup. */
    public fun openReadOnly(
        rootId: PortalGrantRootId,
        relative: String,
    ): Capability.ReadFd {
        val root = grantRoots.directory(rootId)
        check(lifecycle == ProcessBrokerLifecycle.New || lifecycle == ProcessBrokerLifecycle.Running) {
            "broker is not accepting grants: $lifecycle"
        }
        return openGrantedRead(root, relative)
    }

    internal fun sleep(millis: Int) {
        val buf = ByteArray(4)
        buf[0] = (millis ushr 24).toByte()
        buf[1] = (millis ushr 16).toByte()
        buf[2] = (millis ushr 8).toByte()
        buf[3] = millis.toByte()
        call(PortalMethod.Sleep, buf, emptyList())
    }

    internal fun spawnedWorkers(): Int = spawned.get()

    /** Broker-owned diagnostics; these do not contact, alter, or revive a worker. */
    internal fun workerPids(): List<Long> = trackedSlots.mapNotNull { slot -> slot.process.takeIf(Process::isAlive)?.pid() }.sorted()

    internal fun health(baseline: PortalServiceHealth): PortalServiceHealth =
        baseline.copy(
        cgroupUsage = cgroupUsage(),
        lastWorkerLifecycle = mostRecentWorkerLifecycle,
        workerPids = workerPids(),
        lastInvocation = mostRecentInvocation,
    )

    internal fun diagnostics(): ExecutionDiagnostics {
        val state = when (lifecycle) {
            ProcessBrokerLifecycle.New -> ExecutionDiagnosticState.UNKNOWN
            ProcessBrokerLifecycle.Starting -> ExecutionDiagnosticState.PREPARING
            ProcessBrokerLifecycle.Running -> ExecutionDiagnosticState.READY
            ProcessBrokerLifecycle.Closing -> ExecutionDiagnosticState.DRAINING
            ProcessBrokerLifecycle.Closed -> ExecutionDiagnosticState.CLOSED
            is ProcessBrokerLifecycle.Failed -> ExecutionDiagnosticState.FAILED
        }
        return ExecutionDiagnostics(
            state,
            mostRecentGeneration,
            operations.diagnostics(),
            trackedSlots.count { it.process.isAlive },
            operations.unfinishedInvocations(),
            state == ExecutionDiagnosticState.CLOSED,
        )
    }

    internal fun cgroupUsage(): List<PortalCgroupUsage> = trackedSlots.mapNotNull { slot -> slot.cgroup?.let(cgroupController::sample) }.sortedBy { it.directory }

    internal fun crashIdleWorkerProcess() {
        val slot =
            idle.poll(limits.callTimeoutMs, TimeUnit.MILLISECONDS)
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
        return if (PortalInvocationRetirement.deadline(this) == null) {
            admission.enter(payload.size, fds.size).use { callAdmitted(method, payload, fds) }
        } else {
            admission.validate(payload.size, fds.size)
            callAdmitted(method, payload, fds)
        }
    }

    @Suppress("TooGenericExceptionCaught") // fatal admitted calls retire their exact checked-out process before propagation
    private fun callAdmitted(method: PortalMethod, payload: ByteArray, fds: List<Capability.ReadFd>): ByteArray {
        val deadline = PortalInvocationRetirement.deadline(this) ?: Deadline.afterMillis(limits.callTimeoutMs)
        val admittedAt = System.nanoTime()
        val slot = checkoutWorker(deadline)
        val dispatchedAt = System.nanoTime()
        var returnedToPool = false
        var callState: PortalBrokerCallState = PortalBrokerCallState.Idle
        return try {
            val id = nextId.getAndIncrement()
            val request = PortalFrame(PortalKind.Request, id, method, PortalPayload(payload), fds.size)
            PortalInvocationRetirement.dispatched(this)
            callState = PortalBrokerCallMachine
                .evaluate(PortalBrokerCallState.Idle, PortalBrokerCallEvent.RequestSent(request))
                .state
            slot.channel.sendCapabilities(request, fds, deadline)
            val (reply, extra) = slot.channel.receive(deadline)
            retireCapabilities(extra).failureOrNull()?.let(::raiseBrokerFailure)
            val transition = PortalBrokerCallMachine.evaluate(callState, PortalBrokerCallEvent.ReplyReceived(reply))
            callState = transition.state
            dispatchBrokerCallEffect(
                transition.effect,
                onSuccess = { payloadBytes ->
                    returnToPoolOrDestroy(slot)
                    returnedToPool = true
                    payloadBytes
                },
                onRemoteError = { failure ->
                    returnToPoolOrDestroy(slot)
                    returnedToPool = true
                    throw PortalRemoteCallException(failure.type, failure.message)
                },
            )
        } catch (e: PortalCallException) {
            if (!returnedToPool) {
                PortalInvocationRetirement.retain(slot.retired, e)
                scheduleRecycle(slot)
            }
            raiseBrokerFailure(e)
        } catch (interrupted: InterruptedException) {
            handleInterruptedCall(
                onCleanup = {
                    if (!returnedToPool) {
                        PortalInvocationRetirement.retain(slot.retired, interrupted)
                        scheduleRecycle(slot)
                    }
                },
                interrupted = interrupted,
            )
        } catch (expectedRpcFailure: Exception) {
            val transportFailure = PortalBrokerCallMachine.evaluate(callState, PortalBrokerCallEvent.TransportFailed)
            check(transportFailure.state == PortalBrokerCallState.Failed)
            if (!returnedToPool) {
                PortalInvocationRetirement.retain(slot.retired, expectedRpcFailure)
                scheduleRecycle(slot)
            }
            throw PortalCallException("portal RPC failed", cause = expectedRpcFailure)
        } catch (fatal: Error) {
            if (!returnedToPool) {
                PortalInvocationRetirement.retain(slot.retired, fatal)
                val cleanup = runCatching { destroySlot(slot) }.exceptionOrNull()
                cleanup?.takeIf { it !== fatal }?.let(fatal::addSuppressed)
                runCatching { scheduleRecycle(slot) }.exceptionOrNull()?.takeIf { it !== fatal }?.let(fatal::addSuppressed)
            }
            raiseBrokerFailure(fatal)
        } finally {
            val completedAt = System.nanoTime()
            mostRecentInvocation =
                PortalInvocationTiming(
                    queueWait = java.time.Duration.ofNanos(dispatchedAt - admittedAt),
                    workerExecution = java.time.Duration.ofNanos(completedAt - dispatchedAt),
                )
        }
    }

    private fun checkoutWorker(deadline: Deadline): WorkerSlot =
        try {
        if (deadline.remainingMillis() <= 0) raiseBrokerFailure(PortalCallException("Portal deadline expired before worker checkout", PortalInvocationEvidence.NOT_STARTED))
        idle.poll(deadline.remainingMillis().toLong(), TimeUnit.MILLISECONDS)
            ?: throw PortalCallException("timed out waiting for an idle portal worker", PortalInvocationEvidence.NOT_STARTED)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw PortalCallException("interrupted while waiting for an idle portal worker", PortalInvocationEvidence.NOT_STARTED, interrupted)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        operations.assertExternalClose()
        val preparation = prepareClose()
        preparation.waitForOtherClose?.let { operation ->
            awaitBrokerCloseOperation(operation).raiseIfFailed()
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
        cleanup.attempt("operation dispatch shutdown") { operations.stop() }
        cleanup.attempt("maintenance shutdown") { maintenance.shutdownNow() }
        generationCaptures.cancel()
        val maintenanceStopped = awaitMaintenance(deadline)
        retireRetainedCapabilities(cleanup)
        closeRetirement.retireHandoffs(cleanup)
        provisionalSlots.toList().forEach { slot ->
            cleanup.attempt("provisional worker") { destroySlot(slot) }
        }
        val startupStopped = awaitBrokerStartup(startupToJoin, deadline)
        closeRetirement.retireAllSlots(cleanup)
        provisionalAttempts.toList().forEach { attempt ->
            cleanup.attempt("startup acquisition") { retireStartupAttempt(attempt) }
        }
        closeRetirement.retireHandoffs(cleanup)
        val operationsStopped = operations.await(deadline)
        var capturesStopped = false
        cleanup.attempt("generation capture ownership") { capturesStopped = generationCaptures.retire(deadline) }
        return capturesStopped && operationsStopped && maintenanceStopped && startupStopped && closeRetirement.allOwnedResourcesRetired(cleanup)
    }

    /** Keeps close-only ownership retirement separate from public broker operations. */
    private inner class CloseRetirement {
        fun retireHandoff(handoff: AcceptedSocketHandoff) {
            val failure = runCatching { handoff.cancel() }.exceptionOrNull()
            failure?.let {
                acceptedHandoffs.add(handoff)
                logger.log(Level.WARNING, "failed to retire accepted portal socket", failure)
            }
            if (handoff.fullyRetired) acceptedHandoffs.remove(handoff)
            failure?.let { throw it }
        }

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
            val operation = CloseOperation(Deadline.afterMillis(limits.closeTimeoutMs), CompletableFuture())
            closeOperation = operation
            ClosePreparation.execute(startupCompletion, operation)
        }

    private fun awaitMaintenance(deadline: Deadline): Boolean {
        val waitMs = maxOf(deadline.remainingMillis().toLong(), 5_000L)
        val result = runCatching { maintenance.awaitTermination(waitMs, TimeUnit.MILLISECONDS) }
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

    @Suppress("TooGenericExceptionCaught") // Provisional ownership must roll back on every startup failure.
    private fun spawnWorker(
        deadline: Deadline = Deadline.afterMillis(limits.startupTimeoutMs),
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
            val parentSession = java.util.UUID
                .randomUUID()
                .toString()
                .replace("-", "")
            val classpath = requireWorkerClasspath(workerClasspath)
            val generation = generationCaptures.measure(deadline, active) { budget ->
                (workerLaunch?.measurement ?: PortalGenerationMeasurement.Actual).measure(classpath, workerLaunch, budget)
            }
            val group = workerLaunch?.manifests?.firstOrNull()?.isolationGroup
            val generationArgs = listOf("--worker-generation", "${generation.inputs}:${generation.manifests}:${generation.runtime}") +
                group?.let { listOf("--worker-group", it) }.orEmpty() +
                workerLaunch
                    ?.artifact
                    ?.path
                    ?.let { listOf("--worker-bundle", it.toString()) }
                    .orEmpty()
            val spec =
            JvmChildSpec(
                mainClass = "io.mazewall.portal.worker.PortalWorkerMain",
                mainArgs =
                    listOf(ep.path, "--parent-session", parentSession, "--parent-timeout-ms", limits.startupTimeoutMs.toString(), "--launch-contract", launchContract.argument()) +
                        generationArgs +
                        if (workerProfile.requiresCgroup) listOf("--require-cgroup") else emptyList(),
                maxHeap = workerProfile.maxHeapBytes?.toString() ?: "64m",
                javaAgents = JavaAgentSelection.None,
                classpath = classpath,
                extraJvmArgs = launchProfile.jvmArgs,
            )
            PortalMeasurementBudget(deadline, active).check()
            val process = JvmChildProcess.start(launcher, spec, preparation = launchProfile.preparation ?: JvmLaunchPreparation {})
            synchronized(attempt) { attempt.process = process }
            val cgroup = cgroupController.attachRequired(cgroupRoot, process.pid(), workerProfile)
            synchronized(attempt) { attempt.cgroup = cgroup }
            check(active()) { "portal worker acquisition cancelled" }
            val pump = PortalWorkerOutput(process.inputStream)
            synchronized(attempt) { attempt.pump = pump }
            val peer = socketAcceptance.acceptWithin(server, process, ep.path, deadline, active)
            synchronized(attempt) { attempt.peer = peer }
            check(process.isAlive) { "portal worker exited during handshake" }
            check(active()) { "portal worker acquisition cancelled" }
            val channel = PortalChannel(peer, sockets, limits.maxRequestBytes, limits.maxResponseBytes, limits.maxCapabilities)
            synchronized(attempt) {
                attempt.channel = channel
                attempt.peer = null
            }
            check(active()) { "portal worker acquisition cancelled before parent authorization" }
            launchContract.checkDescriptors(process.pid())
            launchContract.checkStdio(process.pid())
            val resources = PortalParentResources.measure(process.pid(), workerProfile.requiresCgroup)
            PortalMeasurementBudget(deadline, active).check()
            channel.sendControl(PortalParentAuthorization(parentSession, process.pid(), launchContract.identity, resources).frame(), deadline)
            PortalParentAuthorization.acceptAcknowledgement(channel.receiveControl(deadline), parentSession, process.pid(), launchContract.identity, resources)
            PortalWorkerReady.accept(channel.receiveControl(deadline), PortalWorkerReady(parentSession, process.pid(), generation, launchContract.identity, resources))
            check(
                PortalReadyGate.evaluate(PortalReadyPhase.WAITING, true, active(), deadline.remainingMillis() > 0) == PortalReadyPhase.READY,
            ) { "portal worker ready after startup cancellation or deadline" }
            check(process.isAlive) { "portal worker exited during readiness handshake" }
            check(active()) { "portal worker acquisition cancelled" }
            val slot = WorkerSlot(
                process,
                channel,
                attempt.endpoint,
                server,
                pump,
                cgroup,
            )
            synchronized(attempt) {
                check(active() && !attempt.retirementRequested) { "portal worker acquisition cancelled before publication" }
                attempt.acquisitionFinished = true
                attempt.transferred = true
            }
            spawned.incrementAndGet()
            mostRecentGeneration = ExecutionGeneration.PortalInputs(generation.inputs, generation.manifests, generation.runtime)
            mostRecentWorkerLifecycle = PortalWorkerLifecycle(PortalWorkerLifecycleState.STARTED, process.pid())
            provisionalAttempts.remove(attempt)
            return slot
        } catch (failure: Throwable) {
            mostRecentWorkerLifecycle = PortalWorkerLifecycle(PortalWorkerLifecycleState.STARTUP_FAILED, detail = failure.message?.take(1024))
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
            startupRetirement.retireCgroup(attempt, cleanup)
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

    private inner class SocketAcceptance {
        @Suppress("TooGenericExceptionCaught") // Every failure must retire the unclaimed socket and child process.
        fun acceptWithin(
            listener: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            process: Process,
            socketPath: String,
            deadline: Deadline,
            active: () -> Boolean,
        ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            val handoff = AcceptedSocketHandoff(sockets::close)
            acceptedHandoffs.add(handoff)
            return try {
                executor.use {
                    val accepted = executor.submit { publishAcceptedSocket(listener, handoff) }
                    try {
                        awaitClaim(handoff, accepted, process, deadline, active).also { acceptedHandoffs.remove(handoff) }
                    } catch (failure: Throwable) {
                        abandon(handoff, accepted, process, socketPath)
                        throw failure
                    }
                }
            } finally {
                // A task cancelled before dispatch never runs the producer's finally.
                if (executor.isTerminated) {
                    handoff.completeProducer()
                    if (handoff.fullyRetired) acceptedHandoffs.remove(handoff)
                }
            }
        }

        private fun publishAcceptedSocket(
            listener: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            handoff: AcceptedSocketHandoff,
        ) {
            try {
                handoff.publish(sockets.accept(listener))
            } finally {
                handoff.completeProducer()
            }
        }

        private fun awaitClaim(
            handoff: AcceptedSocketHandoff,
            accepted: java.util.concurrent.Future<*>,
            process: Process,
            deadline: Deadline,
            active: () -> Boolean,
        ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
            while (true) {
                check(active()) { "portal worker connection cancelled" }
                handoff.claim()?.let { return it }
                check(process.isAlive) { "portal worker exited before connecting" }
                val remaining = deadline.remainingMillis()
                check(remaining > 0) { "portal worker connection timed out" }
                awaitAcceptance(accepted, remaining)
            }
        }

        private fun awaitAcceptance(
            accepted: java.util.concurrent.Future<*>,
            remaining: Int,
        ) {
            try {
                accepted.get(minOf(remaining, LIFECYCLE_POLL_SLICE_MS).toLong(), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                return
            } catch (failed: ExecutionException) {
                throw (failed.cause ?: failed)
            }
        }

        private fun abandon(
            handoff: AcceptedSocketHandoff,
            accepted: java.util.concurrent.Future<*>,
            process: Process,
            socketPath: String,
        ) {
            runCatching { handoff.cancel() }
            process.destroyForciblyWithDescendants()
            runCatching {
                java.nio.channels.SocketChannel
                    .open(java.net.UnixDomainSocketAddress.of(socketPath))
                    .use { }
            }
            accepted.cancel(true)
            closeRetirement.retireHandoff(handoff)
        }
    }

    private fun scheduleRecycle(dead: WorkerSlot) {
        synchronized(lifecycleGate) {
            if (instanceMode == PortalInstanceMode.STATEFUL_SINGLETON && lifecycle == ProcessBrokerLifecycle.Running) {
                lifecycle = ProcessBrokerLifecycleMachine
                    .evaluate(lifecycle, ProcessBrokerLifecycleEvent.WorkerLost("stateful portal worker lost; guest state cannot be recreated"))
                    .state
            }
        }
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
                    } catch (terminal: io.mazewall.platform.socket.NativeCloseFailure) {
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
                    } catch (terminal: io.mazewall.platform.socket.NativeCloseFailure) {
                        slot.serverRetired = true
                        raiseBrokerFailure(terminal)
                    }
                }
            }
            if (!slot.processDestroyed) {
                cleanup.attempt("worker process") {
                    slot.process.destroyForciblyWithDescendants()
                    slot.processDestroyed = true
                }
            }
            if (!slot.processReaped) {
                cleanup.attempt("worker process reap") {
                    check(slot.process.waitFor(2, TimeUnit.SECONDS)) { "portal worker did not exit after forced termination" }
                    slot.processReaped = true
                }
            }
            if (!slot.cgroupRetired) {
                cleanup.attempt("worker cgroup") {
                    slot.cgroup?.let(cgroupController::release)
                    slot.cgroupRetired = true
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
                mostRecentWorkerLifecycle = PortalWorkerLifecycle(PortalWorkerLifecycleState.RETIRED, slot.process.pid())
                trackedSlots.remove(slot)
                provisionalSlots.remove(slot)
                val retirementFailure = outcome.failureOrNull()
                if (retirementFailure == null) slot.retired.complete(Unit) else slot.retired.completeExceptionally(retirementFailure)
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
        val cgroup: PortalCgroupHandle?,
    ) {
        val retired = CompletableFuture<Unit>()
        var channelRetired: Boolean = false
        var serverRetired: Boolean = false
        var processDestroyed: Boolean = false
        var processReaped: Boolean = false
        var pumpRetired: Boolean = false
        var endpointRetired: Boolean = false
        var cgroupRetired: Boolean = false
        val fullyRetired: Boolean
            get() = channelRetired && serverRetired && processDestroyed && processReaped && pumpRetired && endpointRetired && cgroupRetired
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

    private class StartupRetirement(
        private val sockets: SocketManager,
        private val cgroups: PortalCgroupController,
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
                        process.destroyForciblyWithDescendants()
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

        fun retireCgroup(
            attempt: StartupAttempt,
            cleanup: CleanupCollector,
        ) {
            if (attempt.cgroupRetired) return
            val cgroup = attempt.cgroup
            if (cgroup == null && attempt.acquisitionFinished) {
                attempt.cgroupRetired = true
                return
            }
            if (cgroup == null) return
            cleanup.attempt("provisional worker cgroup") {
                cgroups.release(cgroup)
                attempt.cgroupRetired = true
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
            } catch (terminal: io.mazewall.platform.socket.NativeCloseFailure) {
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
        var cgroup: PortalCgroupHandle? = null
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
        var cgroupRetired = false
        var pumpRetired = false
        var endpointRetired = false
        val fullyRetired: Boolean
            get() =
                acquisitionFinished &&
                    channelRetired &&
                    serverRetired &&
                    processDestroyed &&
                    processReaped &&
                    cgroupRetired &&
                    pumpRetired &&
                    endpointRetired
    }
}

/**
 * [ProcessBroker.close] could not prove that every process, channel, endpoint, and maintenance
 * action reached a terminal state. Individual resource failures are retained for diagnostics.
 */
internal class IncompleteBrokerCleanupException(
    message: String,
    public val failures: List<BrokerCleanupFailure>,
) : IllegalStateException(message)

/** A named resource cleanup failure retained by [IncompleteBrokerCleanupException]. */
internal data class BrokerCleanupFailure(
    val resource: String,
    val cause: Throwable,
)

private fun raiseBrokerFailure(failure: Throwable): Nothing = throw failure

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

private fun awaitBrokerStartup(
    completion: CountDownLatch?,
    deadline: Deadline,
): Boolean {
    if (completion == null || completion.count == 0L) return true
    val result = runCatching { completion.await(deadline.remainingMillis().coerceAtLeast(0).toLong(), TimeUnit.MILLISECONDS) }
    if (result.exceptionOrNull() is InterruptedException) Thread.currentThread().interrupt()
    return result.getOrDefault(false)
}

private fun awaitBrokerCloseOperation(operation: CloseOperation): BrokerCloseResult =
    try {
        val remaining = operation.deadline
            .remainingMillis()
            .coerceAtLeast(0)
            .toLong()
        operation.result.get(remaining, TimeUnit.MILLISECONDS)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw PortalCallException("interrupted while waiting for broker cleanup", cause = interrupted)
    } catch (timedOut: TimeoutException) {
        throw IncompleteBrokerCleanupException(
            "ProcessBroker close did not finish before its shared deadline",
            listOf(BrokerCleanupFailure("concurrent broker close", timedOut)),
        )
    } catch (failed: ExecutionException) {
        throw (failed.cause ?: failed)
    }

private fun requireWorkerClasspath(workerClasspath: String): String {
    require(workerClasspath.isNotBlank()) { "portal worker classpath is required from a verified runtime artifact" }
    return workerClasspath
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

private fun dispatchBrokerCallEffect(
    effect: PortalBrokerCallEffect,
    onSuccess: (ByteArray) -> ByteArray,
    onRemoteError: (PortalRemoteFailure) -> Nothing,
): ByteArray =
    when (effect) {
    is PortalBrokerCallEffect.ReturnPayload -> onSuccess(effect.payload.copyToByteArray())
    is PortalBrokerCallEffect.RemoteError ->
        onRemoteError(
            try {
                PortalRemoteFailureCodec.decode(effect.payload.copyToByteArray())
            } catch (invalid: IllegalArgumentException) {
                throw PortalCallException("portal worker returned an invalid error payload", PortalInvocationEvidence.COMPLETED, invalid)
            },
        )
    is PortalBrokerCallEffect.RecycleWorker -> throw PortalCallException(effect.reason)
    is PortalBrokerCallEffect.ProtocolViolation -> throw PortalCallException(effect.reason)
    is PortalBrokerCallEffect.PostTerminalObservation ->
        throw PortalCallException("portal RPC observed ${effect.event} after ${effect.state}")
    PortalBrokerCallEffect.AwaitReply -> throw PortalCallException("portal RPC produced no terminal result")
}

private fun handleInterruptedCall(
    onCleanup: () -> Unit,
    interrupted: InterruptedException,
): Nothing {
    Thread.currentThread().interrupt()
    val failure = PortalCallException("portal RPC interrupted", cause = interrupted)
    try {
        onCleanup()
    } finally {
        Thread.currentThread().interrupt()
    }
    throw failure
}
