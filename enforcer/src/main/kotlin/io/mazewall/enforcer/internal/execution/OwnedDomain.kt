package io.mazewall.enforcer.internal.execution

import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.InstallationEnvironment
import io.mazewall.enforcer.api.InstallationReceipt
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState
import io.mazewall.enforcer.api.execution.ExecutionDiagnostics
import io.mazewall.enforcer.api.execution.ExecutionGeneration
import io.mazewall.enforcer.api.execution.InvocationDiagnostics
import io.mazewall.enforcer.api.execution.InvocationHandle
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperPreparation
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperSession
import io.mazewall.enforcer.internal.supervisor.SupervisorInstaller
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.internal.PreparedPath
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.fd.ResourceRetirement
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.LiveCompletion
import io.mazewall.platform.native.LiveSetup
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.WorkerOwner
import io.mazewall.platform.native.WorkerRegistrationReceipt
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val HELPER_TIMEOUT_NANOS = 5_000_000_000L

internal class HelperSupportCapture private constructor(
    private val native: NativeEngine,
    private val owner: Owner,
) {
    private enum class CarrierPhase { Capturing, Prepared, Cleaning }

    private sealed interface Owner {
        class Domain(
            val domain: OwnedDomain,
        ) : Owner

        class Carrier(
            val carrier: ScopingPreparation.ClaimProgress,
            val actor: Thread,
        ) : Owner {
            var cleanupActor: Thread? = null
            var phase = CarrierPhase.Capturing
        }
    }

    constructor(domain: OwnedDomain, native: NativeEngine) : this(native, Owner.Domain(domain))
    constructor(carrier: ScopingPreparation.ClaimProgress, native: NativeEngine) : this(native, Owner.Carrier(carrier, Thread.currentThread()))

    private val slots = mutableListOf<PreparedPath.HelperSlot>()

    private var preparedDomain: OwnedDomain? = null

    fun prepareTransfer(
        carrier: ScopingPreparation.ClaimProgress,
        domain: OwnedDomain,
    ) {
        val current = owner as? Owner.Carrier
        check(current != null && current.carrier === carrier && current.actor === Thread.currentThread()) { "Foreign helper support carrier" }
        check(preparedDomain == null && current.phase == CarrierPhase.Capturing)
        preparedDomain = domain
        current.phase = CarrierPhase.Prepared
    }

    fun open(path: Path): PreparedPath {
        check(permitsActor(cleanup = false)) { "Foreign helper support open" }
        val slot = PreparedPath.HelperSlot(this, native, path)
        slots.add(slot)
        return slot.open()
    }

    fun permits(
        slot: PreparedPath.HelperSlot,
        source: NativeEngine,
        cleanup: Boolean,
    ): Boolean = slots.any { it === slot } && source === native && permitsActor(cleanup)

    fun authorizeCarrierCleanup(preparation: ScopingPreparation) {
        val current = owner as? Owner.Carrier ?: error("Helper support is not carrier owned")
        val state = preparation.state as? ScopingPreparation.State.Retiring
        check(state != null && state.retained.inFlight && state.retained.helperCapture === this)
        check(current.carrier.helperCapture === this)
        check(current.actor === Thread.currentThread() || preparation.claimFinished.isDone)
        current.cleanupActor = Thread.currentThread()
        current.phase = CarrierPhase.Cleaning
    }

    private fun permitsActor(cleanup: Boolean): Boolean =
        when (val current = owner) {
        is Owner.Domain -> current.domain.permitsHelperCapture(this, native, cleanup)
        is Owner.Carrier ->
            current.carrier.helperCapture === this &&
            if (cleanup) {
                current.phase == CarrierPhase.Cleaning &&
                    current.cleanupActor === Thread.currentThread() ||
                    current.phase == CarrierPhase.Prepared &&
                    preparedDomain?.permitsHelperCapture(this, native, true) == true
            } else {
                current.phase == CarrierPhase.Capturing && current.actor === Thread.currentThread()
            }
    }

    fun requireCleanupActor() {
        check(permitsActor(cleanup = true)) { "Foreign helper support cleanup" }
    }

    fun close() {
        requireCleanupActor()
        var interrupted = Thread.interrupted()
        var failure: Throwable? = null
        try {
            slots.asReversed().forEach { slot ->
                val observed = slot.closeOwned()
                if (observed != null) {
                    if (observed.newlyDispatched && observed.cause is InterruptedException) {
                        interrupted = true
                        Thread.interrupted()
                    }
                    failure = CleanupFailures.merge(failure, observed.cause)
                }
            }
            failure?.let { throw it }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

/** Fixed physical generation; only its trusted bootstrap thread can construct workers. */
internal class OwnedDomain(
    val generation: Long,
    val limits: DomainLimits,
    private val policy: PolicyDefinition<*>,
    private val environment: InstallationEnvironment,
    private val preparedLandlock: io.mazewall.landlock.PreparedLandlock?,
    private val preparedOperation: io.mazewall.enforcer.api.execution.PreparedOperationResources?,
    name: String,
    internal val scoping: FrozenStackScoping,
    private val confined: ConfinedHelperConfiguration? = null,
    liveCarrier: ScopingPreparation.ClaimProgress? = null,
) {
    private enum class HelperRelease { Retained, Releasing, Released }

    private class ReceiverRetirementState(
        val receivers: List<LiveReceiverRetirement?>,
        val retired: Boolean,
    )

    internal val registrationKey: Any = generation
    private val lock = Any()
    private var liveRelease: LiveRelease? = if (scoping is FrozenStackScoping.Live) LiveRelease() else null
    private var phase = GenerationPhase.PREPARING
    private var failure: Throwable? = null
    private val bootstrap = Thread.currentThread()
    private val liveHelperContext = (scoping as? FrozenStackScoping.Live)?.bootstrap?.helperContext
    private val helperPreparationEnded = CountDownLatch(if (confined != null || liveHelperContext != null) 1 else 0)
    private var helperPlan: PreparedHelperReadPlan? = liveCarrier?.helperPlan
    private var helperCapture: HelperSupportCapture? = liveCarrier?.helperCapture
    private var helperCleanupActor: Thread? = null
    private var helperSession: ConfinedHelperSession? = null
    private var helperRelease = if (confined == null && liveCarrier == null) HelperRelease.Released else HelperRelease.Retained
    private var helperPinsClosed = false
    private var operationClosed = false
    private var landlockClosed = false
    private var directRetiring = false
    private var directReleased = false
    private var directRulesetComplete = false
    private val startup = CountDownLatch(limits.workers)
    private val threads = CopyOnWriteArrayList<Thread>()
    private val liveWorkers = LiveWorkers(this, lock, limits.workers, environment.native)
    private var nextWorkerSlot = 0
    private val pending = ConcurrentHashMap.newKeySet<OwnedInvocation<*>>()
    private val liveCancellationPermit = Any()
    private val admission = Semaphore(limits.maxAdmissions)
    private val admitted = AtomicLong()
    private val rejected = AtomicLong()
    internal val invocationMetrics = DomainInvocationMetrics()
    val cleanupFailure = AtomicReference<Throwable?>()
    private val threadBuilder = Thread.ofPlatform().name(name, 0L).inheritInheritableThreadLocals(false)
    private val executor = ThreadPoolExecutor(
        limits.workers,
        limits.workers,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(limits.maxAdmissions),
        ThreadFactory { runnable ->
            check(Thread.currentThread() === bootstrap) { "Worker replacement must originate from the trusted manager" }
            val slot = liveWorkers.slot(nextWorkerSlot++)
            threadBuilder.unstarted { runWorker(runnable, slot) }.also {
                slot.thread = it
                threads.add(it)
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val retirement = if (preparedLandlock != null || preparedOperation != null || confined != null) {
        PreparedRetirement(name + "resource-retirement", {
            awaitPhysicalWorkers(helperPreparationEnded, executor, threads)
            liveWorkerAdmission.awaitReceivers()
        }, ::releasePreparedResources, { recordCleanupFailure(cleanupFailure, it) })
    } else {
        null
    }

    internal val allowsDirectValidation: Boolean
        get() = synchronized(lock) { !directRetiring && (phase == GenerationPhase.PREPARING || phase == GenerationPhase.READY) }

    internal val hasDirectRulesetReceipt: Boolean
        get() = synchronized(lock) { directRetiring && directRulesetComplete }

    internal val isDirectWorkerActor: Boolean
        get() = synchronized(lock) { phase == GenerationPhase.PREPARING && RuntimeWorkerContext.current === this && threads.any { it === Thread.currentThread() } }

    internal val isDirectRetirementActor: Boolean
        get() = synchronized(lock) { directRetiring && retirement?.isCurrentReleaseActor() == true && physicalWorkersStopped(executor, threads) }

    fun prepare(deadline: Deadline) = prepare(deadline, noAfterDomainCommit)

    internal fun prepare(
        deadline: Deadline,
        afterDomainCommit: () -> Unit,
    ) {
        retirement?.let { owner ->
            ResourceRetirement.runWithRollback({ owner.start() }, {
                executor.shutdownNow()
                helperPreparationEnded.countDown()
                owner.releaseUnstarted()
            })
        }
        try {
            afterDomainCommit()
            prepareHelper(deadline)
            if (liveHelperContext != null) requireHelperReady(ConfinedDeadline.mint(environment.native, deadline::remainingNanos))
        } finally {
            helperPreparationEnded.countDown()
        }
        if (liveHelperContext == null) requireSupportedScopingStart(scoping)
        validateResources()
        val started = executor.prestartAllCoreThreads()
        // Early failure can stop the executor before the remaining workers are created.
        repeat((limits.workers - started).coerceAtLeast(0)) { startup.countDown() }
        if (!startup.await(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) fail(TimeoutException("Required worker preparation did not finish"))
        val problem = synchronized(lock) {
            if (scoping is FrozenStackScoping.Live && !liveWorkers.allObservationInstalled()) {
                failure = failure ?: IllegalStateException("Live observation endpoint was not installed")
                phase = GenerationLifecycle.evaluate(phase, GenerationEvent.FAILED)
            } else {
                phase = GenerationLifecycle.evaluate(phase, GenerationEvent.PREPARED)
            }
            failure ?: if (phase != GenerationPhase.READY) RejectedExecutionException("Domain preparation ended during drain") else null
        }
        if (problem != null) {
            fail(problem)
            awaitRetirement(deadline)
            throw problem
        }
        validateResources()
    }

    @Suppress("TooGenericExceptionCaught") // startup/retirement boundary preserves fatal identity and owns executor bookkeeping
    private fun runWorker(runnable: Runnable, slot: LiveWorkerSlot) {
        var receipt: InstallationReceipt? = null
        var terminal: Throwable? = null
        RuntimeWorkerContext.enter(this, scoping)
        try {
            try {
                if (scoping is FrozenStackScoping.Live) RuntimeWorkerContext.bindLiveWorker(liveWorkers.register(slot, scoping))
                val validation = if (scoping is FrozenStackScoping.Live) liveWorkerAdmission.reserveLiveValidation(slot) else null
                requireSupportedScopingStart(scoping)
                if (confined != null) requireHelperReady(ConfinedDeadline.mint(environment.native) { HELPER_TIMEOUT_NANOS })
                receipt = ContainedExecutors.installOnCurrentThread(policy, scoping, environment, preparedLandlock, validation)
                receipt.requireInstalled()
            } catch (installationFailure: Throwable) {
                if (slot.registrationReserved && slot.worker == null && slot.registrationFailure == null) slot.registrationFailure = installationFailure
                terminal = installationFailure
                fail(installationFailure)
            } finally {
                startup.countDown()
            }
            // Even failed startup enters the now-stopped executor's empty lifecycle loop,
            // so the JDK worker is retired rather than stranded before runWorker bookkeeping.
            try {
                runnable.run()
            } catch (workerFailure: Throwable) {
                terminal = terminal ?: workerFailure
                fail(workerFailure)
            }
        } finally {
            try {
                receipt?.close()
            } catch (closeFailure: Throwable) {
                cleanupFailure.compareAndSet(null, closeFailure)
                if (terminal == null) terminal = closeFailure else terminal.addSuppressed(closeFailure)
                fail(closeFailure)
            } finally {
                RuntimeWorkerContext.exit()
            }
        }
        if (terminal is Error) throw terminal
    }

    fun <T> submit(action: Callable<T>): InvocationHandle<T> {
        val task: OwnedInvocation<T>
        var liveRejection: Throwable? = null
        synchronized(lock) {
            if (phase != GenerationPhase.READY) {
                recordRejection()
                throw RejectedExecutionException("Domain generation is not admitting", failure)
            }
            val liveTask = if (scoping is FrozenStackScoping.Live) OwnedInvocation(this, action, liveCancellationPermit) else null
            if (!admission.tryAcquire()) {
                recordRejection()
                throw RejectedExecutionException("Domain admission limit reached")
            }
            task = liveTask ?: OwnedInvocation(this, action, liveCancellationPermit)
            pending.add(task)
            admitted.incrementAndGet()
            try {
                executor.execute(task)
            } catch (rejected: RejectedExecutionException) {
                recordRejection()
                if (scoping is FrozenStackScoping.Live) {
                    failure = failure ?: rejected
                    phase = GenerationLifecycle.evaluate(phase, GenerationEvent.FAILED)
                    liveRejection = rejected
                } else {
                    task.generationFailed(rejected)
                    fail(rejected)
                }
            } catch (replacementRefused: IllegalStateException) {
                if (scoping is FrozenStackScoping.Live) {
                    failure = failure ?: replacementRefused
                    phase = GenerationLifecycle.evaluate(phase, GenerationEvent.FAILED)
                    liveRejection = replacementRefused
                } else {
                    task.generationFailed(replacementRefused)
                    fail(replacementRefused)
                }
            }
        }
        liveRejection?.let(::fail)
        return task
    }

    fun fail(problem: Throwable) {
        var hasActiveLiveTask = false
        val first = synchronized(lock) {
            val recorded = failure ?: problem
            failure = recorded
            // A task claim uses this same gate; after failure closes it no queued task can Begin.
            hasActiveLiveTask = scoping is FrozenStackScoping.Live && liveWorkers.hasActiveTask()
            liveWorkers.revokeAllTasks()
            phase = GenerationLifecycle.evaluate(phase, GenerationEvent.FAILED)
            recorded
        }
        if (!hasActiveLiveTask) {
            executor.shutdownNow()
            retirement?.request()
            pending.toList().forEach { it.generationFailed(first) }
            return
        }
        var cleanup: Throwable? = null
        var interrupted = false

        fun retain(failure: Throwable?) {
            if (failure != null) {
                interrupted = interrupted || failure.wasInterrupted()
                cleanup = CleanupFailures.merge(cleanup, failure)
            }
        }

        retain(runCatching { invalidateLiveAdmission() }.exceptionOrNull())
        retain(runCatching { executor.shutdownNow() }.exceptionOrNull())
        retain(runCatching { retirement?.request() }.exceptionOrNull())
        retain(
            runCatching {
                pending.toList().forEach { task -> retain(runCatching { task.generationFailed(first) }.exceptionOrNull()) }
            }.exceptionOrNull(),
        )
        val failure = cleanup
        try {
            if (failure != null) recordCleanupFailure(cleanupFailure, failure)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        if (failure is Error) throw failure
    }

    fun beginDrain() {
        var hasActiveLiveTask = false
        synchronized(lock) {
            hasActiveLiveTask = scoping is FrozenStackScoping.Live && liveWorkers.hasActiveTask()
            liveWorkers.revokeAllTasks()
            phase = GenerationLifecycle.evaluate(phase, GenerationEvent.DRAIN)
        }
        try {
            if (hasActiveLiveTask) invalidateLiveAdmission()
        } finally {
            executor.shutdown()
            retirement?.request()
        }
    }

    fun awaitRetirement(deadline: Deadline) {
        executor.awaitTermination(deadline.remainingNanos(), TimeUnit.NANOSECONDS)
        threads.forEach { deadline.join(it) }
        retirement?.await(deadline)
        retireIfStopped()
    }

    fun retireIfStopped() {
        if (physicalWorkersStopped(executor, threads)) {
            retirement?.request()
            if (retired()) synchronized(lock) { phase = GenerationLifecycle.evaluate(phase, GenerationEvent.RETIRED) }
        }
    }

    private fun releasePreparedResources() {
        if (preparedOperation?.isDirectResult() == true) {
            synchronized(lock) {
                check(!directRetiring && retirement?.isCurrentReleaseActor() == true && physicalWorkersStopped(executor, threads))
                directRetiring = true
            }
            try {
                preparedOperation.awaitDirectValidators(io.mazewall.enforcer.api.execution.DirectClaimAction.RETIRE_DOMAIN)
                checkNotNull(preparedLandlock).closeDirect(io.mazewall.enforcer.api.execution.DirectClaimAction.RETIRE_DOMAIN)
                synchronized(lock) { directRulesetComplete = true }
                preparedOperation.retireDirect(io.mazewall.enforcer.api.execution.DirectClaimAction.RETIRE_DOMAIN)
                synchronized(lock) { directReleased = true }
            } finally {
                synchronized(lock) { directRetiring = false }
            }
            return
        }
        if (scoping is FrozenStackScoping.Live) {
            releaseLiveResources()
            return
        }
        if (confined != null) {
            releaseConfinedResources(ConfinedDeadline.mint(environment.native) { HELPER_TIMEOUT_NANOS })
            return
        }
        val cleanup = ResourceRetirement()
        // Claim the public owner's validation monitor before releasing its shared resource set.
        // A pending external validation must retain the complete descriptor/ruleset hierarchy.
        cleanup.attempt { preparedOperation?.retire() }
        cleanup.attempt { preparedLandlock?.close() }
        cleanup.finish()
    }

    /** Domain-bound original support slots remain reachable through uncertain cleanup. */
    internal fun permitsHelperCapture(
        capture: HelperSupportCapture,
        native: NativeEngine,
        cleanup: Boolean,
    ): Boolean =
        synchronized(lock) {
            if (helperCapture !== capture) return@synchronized false
            if (preparedOperation?.native !== native) return@synchronized false
            if (!cleanup) {
                return@synchronized confined != null &&
                    phase == GenerationPhase.PREPARING &&
                    bootstrap === Thread.currentThread()
            }
            if (confined == null && scoping !is FrozenStackScoping.Live) return@synchronized false
            helperRelease == HelperRelease.Releasing &&
                helperCleanupActor === Thread.currentThread() &&
                helperPreparationEnded.count == 0L &&
                physicalWorkersStopped(executor, threads)
        }

    private fun prepareHelper(deadline: Deadline) {
        val context: io.mazewall.enforcer.internal.supervisor.SupervisorContext
        val observations: HelperSecurityObservation
        val plan: PreparedHelperReadPlan
        val liveContext = liveHelperContext
        if (liveContext != null) {
            val live = scoping as FrozenStackScoping.Live
            context = liveContext
            observations = checkNotNull(live.bootstrap.helperAdmission).observations
            plan = checkNotNull(helperPlan)
        } else {
            val configuration = confined ?: return
            val operation = requireNotNull(preparedOperation)
            val resources = operation.resources
            val bootstrap = requireNotNull(configuration.context.confinedBootstrap).parent
            val parent = configuration.observations.observeAncestry(
                bootstrap.identity.pid,
                environment.native.process.gettid(),
                HelperSecurityObservation.Lineage.Tier1,
            )
            parent.requireLineage(bootstrap, HelperSecurityObservation.Lineage.Tier1)
            val binding = HelperBinding(
                parent.identity.pid,
                parent.identity.startTimeTicks,
                generation,
                resources.generation,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(configuration.policy.toJson().toByteArray())),
            )
            val capture = HelperSupportCapture(this, operation.native)
            synchronized(lock) {
                check(phase == GenerationPhase.PREPARING && Thread.currentThread() === this.bootstrap)
                check(confined === configuration && preparedOperation === operation && helperCapture == null)
                helperCapture = capture
            }
            plan = PreparedHelperReadPlan.captureOwned(configuration.policy, resources, binding, configuration.support, configuration.lifetime, operation.native, parent.ancestry, capture)
            synchronized(lock) {
                helperPlan = plan
                check(phase == GenerationPhase.PREPARING) { "Confined preparation was stopped before setup" }
            }
            context = configuration.context
            observations = configuration.observations
        }
        val result = SupervisorInstaller.prepareConfinedHelper(
            plan,
            context,
            environment.native,
            ConfinedDeadline.mint(environment.native, deadline::remainingNanos),
            retain = { session ->
                synchronized(lock) {
                    check(helperSession == null)
                    helperSession = session
                    check(phase == GenerationPhase.PREPARING) { "Confined preparation was stopped before connection acquisition" }
                }
            },
            cancelled = { synchronized(lock) { phase != GenerationPhase.PREPARING && phase != GenerationPhase.READY } },
            observations = observations,
        )
        when (result) {
            is ConfinedHelperPreparation.Ready -> check(result.binding == plan.manifest.binding && result.session === helperSession)
            is ConfinedHelperPreparation.Rejected -> throw result.cause
        }
    }

    private fun requireHelperReady(deadline: ConfinedDeadline) {
        if (confined == null && liveHelperContext == null) return
        val owners = synchronized(lock) { requireNotNull(helperPlan) to requireNotNull(helperSession) }
        owners.second.requireReady(owners.first.manifest.binding, deadline)
    }

    internal val liveWorkerAdmission = LiveWorkerAdmission(liveHelperContext, limits.workers)
    internal val liveTaskAdmission = LiveTaskAdmission()

    internal inner class LiveWorkerAdmission(
        private val originalContext: io.mazewall.enforcer.internal.supervisor.SupervisorContext?,
        capacity: Int,
    ) {
        private val liveValidation = arrayOfNulls<LiveValidationTransport>(capacity)
        private val liveValidationWorkers = arrayOfNulls<WorkerOwner>(capacity)
        private var receiverRetirement = ReceiverRetirementState(List(capacity) { null }, retired = true)
        private val liveValidationSpent = BooleanArray(capacity)
        private val liveValidationSent = BooleanArray(capacity)
        private val liveValidationSockets =
            arrayOfNulls<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>>(
                capacity,
            )

        internal fun registerReceiver(
            receipt: LiveValidationTransport,
            receiver: LiveReceiverRetirement,
            socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        ) {
            synchronized(lock) {
                val index = liveValidation.indexOfFirst { it === receipt }
                check(index >= 0 && retirement?.live() == true && phase == GenerationPhase.PREPARING) { "Live receiver has no current retirement owner" }
                val slot = liveWorkers.slot(index)
                val live = scoping as FrozenStackScoping.Live
                val plan = checkNotNull(helperPlan)
                check(
                    receiverRetirement.receivers[index] == null &&
                        liveValidationSent[index] &&
                        liveValidationSockets[index] === socket &&
                        slot.thread?.isAlive == true &&
                        slot.worker != null &&
                        slot.worker === liveValidationWorkers[index] &&
                        slot.worker?.owner === live.bootstrap.sealed &&
                        slot.worker?.tid == slot.tid &&
                        plan.manifest.binding.owner.domainGeneration == generation &&
                        checkNotNull(helperSession).matchesOriginal(plan, checkNotNull(originalContext)),
                ) { "Live receiver no longer belongs to its original worker" }
                val updated = receiverRetirement.receivers.toMutableList()
                updated[index] = receiver
                receiverRetirement = ReceiverRetirementState(updated.toList(), retired = false)
            }
        }

        internal fun receiversExited(): Boolean = synchronized(lock) { receiverRetirement.retired }

        internal fun awaitReceivers() {
            // Workers have physically stopped; registration cannot succeed after this snapshot.
            val retained = synchronized(lock) { receiverRetirement }
            retained.receivers.filterNotNull().forEach { receiver ->
                receiver.awaitPhysicalExit()
                check(receiver.physicallyExited()) { "Live receiver has not physically exited" }
                receiver.failure()?.let { recordCleanupFailure(cleanupFailure, it) }
            }
            synchronized(lock) {
                check(receiverRetirement === retained) { "Live receiver owner changed during retirement" }
                receiverRetirement = ReceiverRetirementState(retained.receivers, retired = true)
            }
        }

        internal fun reserve(slot: LiveWorkerSlot) {
            synchronized(lock) {
                check(
                    phase == GenerationPhase.PREPARING &&
                        liveWorkers.owns(slot) &&
                        !slot.registrationReserved &&
                        Thread.currentThread() === slot.thread &&
                        !Thread.currentThread().isVirtual,
                ) { "Live worker registration was not admitted" }
                slot.registrationReserved = true
            }
        }

        internal fun reserveLiveValidation(slot: LiveWorkerSlot): LiveValidationTransport {
            val candidate = newLiveValidationTransport(this@OwnedDomain)
            synchronized(lock) {
                check(
                    phase == GenerationPhase.PREPARING &&
                        liveWorkers.owns(slot) &&
                        slot.index in 0 until MAX_LIVE_VALIDATION_ATTEMPTS &&
                        slot.thread === Thread.currentThread() &&
                        !Thread.currentThread().isVirtual &&
                        slot.worker === RuntimeWorkerContext.liveWorker &&
                        slot.worker != null &&
                        helperSession != null &&
                        helperPlan != null &&
                        liveValidation[slot.index] == null,
                ) { "Live validation was not admitted by its original worker" }
                val plan = checkNotNull(helperPlan)
                val session = checkNotNull(helperSession)
                val context = checkNotNull(originalContext)
                val live = scoping as FrozenStackScoping.Live
                check(
                    plan.manifest.binding.owner.domainGeneration == generation &&
                        slot.worker?.owner === live.bootstrap.sealed &&
                        session.matchesOriginal(plan, context),
                ) { "Live validation original binding differs" }
                liveValidationWorkers[slot.index] = slot.worker
                liveValidation[slot.index] = candidate
            }
            return candidate
        }

        internal fun consumeLiveValidation(
            receipt: LiveValidationTransport,
            socket: io.mazewall.platform.fd.FileDescriptor<io.mazewall.platform.fd.FileDescriptorRole.UnixSocket, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>,
            deadline: ConfinedDeadline,
        ) {
            var admittedIndex = -1
            val captured = synchronized(lock) {
                val index = liveValidation.indexOfFirst { it === receipt }
                check(index >= 0 && !liveValidationSpent[index] && phase == GenerationPhase.PREPARING) { "Live validation receipt is not current" }
                val slot = liveWorkers.slot(index)
                val worker = checkNotNull(slot.worker)
                val live = scoping as FrozenStackScoping.Live
                check(
                    slot.thread?.isAlive == true &&
                        worker === liveValidationWorkers[index] &&
                        worker.owner === live.bootstrap.sealed &&
                        worker.tid == slot.tid,
                ) { "Live validation worker is no longer registered" }
                liveValidationSpent[index] = true
                admittedIndex = index
                val plan = checkNotNull(helperPlan)
                val session = checkNotNull(helperSession)
                check(
                    plan.manifest.binding.owner.domainGeneration == generation &&
                        session.matchesOriginal(plan, checkNotNull(originalContext)),
                ) {
                    "Live validation original binding differs"
                }
                Triple(session, plan.manifest.binding, worker)
            }
            val (session, binding, worker) = captured
            val owner = binding.owner
            val hex = java.util.HexFormat.of()
            val bytes = java.nio.ByteBuffer
                .allocate(149)
                .put(0xe5.toByte())
                .putInt(1)
                .putInt(owner.ownerPid.value)
                .putLong(owner.ownerStartTicks)
                .putLong(owner.domainGeneration)
                .put(hex.parseHex(owner.resourceGeneration))
                .put(hex.parseHex(owner.policyDigest))
                .put(hex.parseHex(binding.manifestDigest))
                .putInt(worker.tid.value)
                .putLong(worker.owner.installation.value)
                .putLong(worker.worker.value)
                .putLong((admittedIndex + 1).toLong())
                .array()
            session.authenticateLiveSocket(socket)
            synchronized(lock) {
                check(
                    phase == GenerationPhase.PREPARING &&
                        helperSession === session &&
                        liveValidation[admittedIndex] === receipt &&
                        liveWorkers.slot(admittedIndex).worker === worker,
                ) { "Live validation was revoked during authentication" }
            }
            session.sendAuthenticatedLivePrelude(socket, bytes, deadline)
            synchronized(lock) {
                check(phase == GenerationPhase.PREPARING && helperSession === session) { "Live validation was revoked during handoff" }
                liveValidationSent[admittedIndex] = true
                liveValidationSockets[admittedIndex] = socket
            }
        }

        internal fun installObservation(
            receipt: LiveValidationTransport,
            socket: io.mazewall.platform.fd.FileDescriptor<io.mazewall.platform.fd.FileDescriptorRole.UnixSocket, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>,
        ): LiveObservationEndpoint =
            synchronized(lock) {
                val index = liveValidation.indexOfFirst { it === receipt }
                check(index >= 0 && phase == GenerationPhase.PREPARING && liveValidationSent[index] && liveValidationSockets[index] === socket)
                val slot = liveWorkers.slot(index)
                val worker = checkNotNull(slot.worker)
                val live = scoping as FrozenStackScoping.Live
                val plan = checkNotNull(helperPlan)
                val session = checkNotNull(helperSession)
                check(
                    slot.endpoint === LiveEndpointState.NotInstalled &&
                        slot.thread?.isAlive == true &&
                        worker === liveValidationWorkers[index] &&
                        worker.owner === live.bootstrap.sealed &&
                        worker.enrollment === live.bootstrap.enrollment &&
                        worker.tid == slot.tid &&
                        plan.manifest.binding.owner.domainGeneration == generation &&
                        session.matchesOriginal(plan, checkNotNull(originalContext)),
                ) { "Live observation endpoint no longer belongs to its original worker" }
                LiveObservationEndpoint(this@OwnedDomain, slot, socket).also { slot.endpoint = LiveEndpointState.Installed(it) }
            }

        internal fun requireLiveValidation(receipt: LiveValidationTransport) {
            synchronized(lock) {
                val index = liveValidation.indexOfFirst { it === receipt }
                check(index >= 0 && liveValidationSent[index] && phase == GenerationPhase.PREPARING) { "Live validation receipt is not current" }
                val slot = liveWorkers.slot(index)
                val live = scoping as FrozenStackScoping.Live
                val plan = checkNotNull(helperPlan)
                val session = checkNotNull(helperSession)
                check(
                    slot.thread?.isAlive == true &&
                        slot.worker != null &&
                        slot.worker === liveValidationWorkers[index] &&
                        slot.worker?.owner === live.bootstrap.sealed &&
                        slot.worker?.tid == slot.tid &&
                        plan.manifest.binding.owner.domainGeneration == generation &&
                        session.matchesOriginal(plan, checkNotNull(originalContext)),
                ) {
                    "Live validation worker is no longer registered"
                }
            }
        }

        internal fun liveValidationSocketPath(receipt: LiveValidationTransport): String =
            synchronized(lock) {
                val index = liveValidation.indexOfFirst { it === receipt }
                check(index >= 0 && phase == GenerationPhase.PREPARING && !liveValidationSent[index]) { "Live validation receipt is not current" }
                check(liveWorkers.slot(index).thread?.isAlive == true && helperSession != null)
                checkNotNull(originalContext).socketPath
            }
    }

    private fun isPendingInvocation(invocation: OwnedInvocation<*>): Boolean = pending.contains(invocation)

    internal inner class LiveTaskAdmission {
        internal fun claimLiveTask(invocation: OwnedInvocation<*>): LiveWorkerSlot =
            synchronized(lock) {
                val slot = liveWorkers.currentSlot()
                check(
                    invocation.belongsTo(this@OwnedDomain) &&
                        isPendingInvocation(invocation) &&
                        phase == GenerationPhase.READY &&
                        slot != null &&
                        slot.worker === RuntimeWorkerContext.liveWorker &&
                        slot.activeTask == null,
                ) {
                    "Live invocation was not admitted on its registered worker"
                }
                slot.activeTask = invocation
                slot
            }

        internal fun publishLiveTask(
            slot: LiveWorkerSlot,
            invocation: OwnedInvocation<*>,
            lease: io.mazewall.platform.native.TaskLease,
        ) {
            synchronized(lock) {
                val live = scoping as FrozenStackScoping.Live
                check(
                    invocation.belongsTo(this@OwnedDomain) &&
                        liveWorkers.owns(slot) &&
                        slot.thread === Thread.currentThread() &&
                        slot.activeTask === invocation &&
                        slot.worker === lease.worker &&
                        lease.worker === RuntimeWorkerContext.liveWorker &&
                        lease.worker.owner === live.bootstrap.sealed &&
                        lease.worker.enrollment === live.bootstrap.enrollment &&
                        slot.tid == lease.worker.tid &&
                        !slot.hasPublication() &&
                        !slot.hasCleanup(),
                ) { "Live task publication was revoked or differs from its original owner" }
                // Retain the exact owned lease before any allocation or admission check can fail.
                slot.retainCleanup(invocation, lease)
                check(phase == GenerationPhase.READY && isPendingInvocation(invocation) && slot.endpoint !is LiveEndpointState.Lost) {
                    "Live task publication was revoked"
                }
                slot.publishRetained(invocation, lease)
            }
        }

        internal fun registerLiveCleanup(
            slot: LiveWorkerSlot,
            invocation: OwnedInvocation<*>,
            lease: io.mazewall.platform.native.TaskLease,
        ) {
            synchronized(lock) {
                check(liveWorkers.owns(slot) && slot.activeTask === invocation && slot.worker === lease.worker && !slot.hasPublication())
                check(!slot.hasCleanup())
                slot.retainCleanup(invocation, lease)
            }
        }

        internal fun observeLiveTask(
            endpoint: LiveObservationEndpoint,
            tid: Int,
        ): Boolean {
            val claim = claimLiveObservation(endpoint, tid) ?: return false
            val lease = claim.lease
            val first = firstLiveSnapshot(lease) ?: return false
            val second = (
                lease.worker.owner.capability
                    .copy(lease, first.invocation) as? io.mazewall.platform.native.LiveRead.Available
            )?.snapshot
            return second != null && sameLiveSnapshots(lease, first, second) && isCurrentLiveObservation(endpoint, claim)
        }

        private fun firstLiveSnapshot(lease: io.mazewall.platform.native.TaskLease): io.mazewall.platform.native.LiveSnapshot? {
            val first = (
                lease.worker.owner.capability
                    .copy(lease) as? io.mazewall.platform.native.LiveRead.Available
            )?.snapshot
            return if (first != null && first.task === lease && (scoping as FrozenStackScoping.Live).bootstrap.matchesObservation(first)) first else null
        }

        private fun claimLiveObservation(
            endpoint: LiveObservationEndpoint,
            tid: Int,
        ): LiveTaskPublication? = synchronized(lock) { claimLiveObservationUnderGate(endpoint, tid) }

        private fun claimLiveObservationUnderGate(
            endpoint: LiveObservationEndpoint,
            tid: Int,
        ): LiveTaskPublication? {
            val slot = endpoint.slot
            val current = phase == GenerationPhase.READY && liveWorkers.owns(slot) && slot.tid == Tid(tid)
            return if (current) slot.claimPublication(endpoint) else null
        }

        private fun sameLiveSnapshots(
            lease: io.mazewall.platform.native.TaskLease,
            first: io.mazewall.platform.native.LiveSnapshot,
            second: io.mazewall.platform.native.LiveSnapshot,
        ): Boolean =
            second.task === lease &&
                first.invocation == second.invocation &&
                first.publication == second.publication &&
                first.binding == second.binding &&
                first.frames == second.frames

        private fun isCurrentLiveObservation(
            endpoint: LiveObservationEndpoint,
            claim: LiveTaskPublication,
        ): Boolean = synchronized(lock) { isCurrentLiveObservationUnderGate(endpoint, claim) }

        private fun isCurrentLiveObservationUnderGate(
            endpoint: LiveObservationEndpoint,
            claim: LiveTaskPublication,
        ): Boolean = phase == GenerationPhase.READY && endpoint.slot.isCurrentPublication(endpoint, claim)

        internal fun revokeLiveTask(
            slot: LiveWorkerSlot,
            invocation: OwnedInvocation<*>,
            lease: io.mazewall.platform.native.TaskLease,
        ) {
            synchronized(lock) {
                check(liveWorkers.owns(slot) && slot.activeTask === invocation)
                if (slot.hasPublication()) {
                    check(slot.publishedMatches(invocation, lease)) { "Live task publication belongs to another lease" }
                    slot.revokePublication()
                } else if (!slot.revokedMatches(invocation, lease) && !slot.cleanupMatches(invocation, lease)) {
                    check(canRetainCleanup(slot, invocation, lease)) { "Live task cleanup does not match its original lease" }
                    slot.retainCleanup(invocation, lease)
                }
            }
        }

        private fun canRetainCleanup(
            slot: LiveWorkerSlot,
            invocation: OwnedInvocation<*>,
            lease: io.mazewall.platform.native.TaskLease,
        ): Boolean {
            val originalWorker = slot.thread === Thread.currentThread() && slot.worker === lease.worker
            val noConflict = !slot.hasCleanup() && !slot.wasRevokedFor(invocation)
            return originalWorker && noConflict && invocation.retainsLiveLease(lease)
        }
    }

    internal fun cancelLiveInvocation(
        invocation: OwnedInvocation<*>,
        permit: Any,
    ): OwnedInvocation.LiveCancellation =
        synchronized(lock) {
            check(permit === liveCancellationPermit && invocation.belongsTo(this)) { "Live cancellation does not own this domain" }
            if (!pending.contains(invocation)) return@synchronized OwnedInvocation.LiveCancellation.ENDED
            val transition = invocation.markLiveCancellation(permit)
            if (transition != OwnedInvocation.LiveCancellation.ENDED && phase == GenerationPhase.READY) {
                liveWorkers.revokeAllTasks()
                phase = GenerationPhase.DRAINING
            }
            transition
        }

    fun initialConfinedRetirementEnded(): Boolean =
        (confined != null || scoping is FrozenStackScoping.Live) &&
            physicalWorkersStopped(executor, threads) &&
            liveWorkerAdmission.receiversExited() &&
            retirement?.finished() == true

    internal val isLiveScoping: Boolean get() = scoping is FrozenStackScoping.Live

    fun initialLiveRetirementEnded(): Boolean = scoping is FrozenStackScoping.Live && initialConfinedRetirementEnded()

    fun retryConfinedRetirement(deadline: ConfinedDeadline) {
        check(initialConfinedRetirementEnded()) { "Initial helper retirement still owns cleanup" }
        if (scoping is FrozenStackScoping.Live) releaseLiveResources(allowPendingRetry = true) else releaseConfinedResources(deadline)
        retireIfStopped()
    }

    internal val liveHelperRetirement = LiveHelperRetirement()

    internal inner class LiveHelperRetirement {
        fun abortBeforePrepare() {
            check(Thread.currentThread() === bootstrap) { "Only the originating bootstrap can retire an unstarted domain" }
            executor.shutdownNow()
            helperPreparationEnded.countDown()
            retirement?.releaseUnstarted()
        }

        fun close() {
            if (!helperPinsClosed) {
                synchronized(lock) {
                    check(helperPreparationEnded.count == 0L && physicalWorkersStopped(executor, threads))
                    check(helperRelease == HelperRelease.Retained)
                    helperRelease = HelperRelease.Releasing
                    helperCleanupActor = Thread.currentThread()
                }
                try {
                    retireSessionAndPins(ConfinedDeadline.mint(environment.native) { HELPER_TIMEOUT_NANOS })
                } finally {
                    synchronized(lock) {
                        helperCleanupActor = null
                        helperRelease = if (helperPinsClosed) HelperRelease.Released else HelperRelease.Retained
                    }
                }
            }
        }

        fun retireSessionAndPins(deadline: ConfinedDeadline) {
            helperSession?.retire(deadline)
            if (!helperPinsClosed) {
                val plan = helperPlan
                if (plan == null) helperCapture?.close() else plan.close()
                helperPinsClosed = true
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Commit exact Unknown and history before propagating any live cleanup failure.
    private fun releaseLiveResources(allowPendingRetry: Boolean = false) {
        check(liveWorkerAdmission.receiversExited()) { "Live receiver still owns its resources" }
        val live = scoping as FrozenStackScoping.Live
        var progress = synchronized(lock) {
            val current = checkNotNull(liveRelease)
            if (!current.mayRelease(allowPendingRetry)) {
                return
            }
            current.copy(pass = ScopingPreparation.ReleasePass.Releasing).also { liveRelease = it }
        }
        val owner = live.bootstrap.staging
        try {
            if (progress.invalidate == ScopingPreparation.ReleaseStep.NotAttempted) {
                progress = progress.copy(invalidate = ScopingPreparation.ReleaseStep.Unknown)
                val outcome = owner.capability.invalidate(owner)
                progress = observeLiveInvalidation(progress, outcome)
                if (outcome !is io.mazewall.platform.native.LiveCompletion.Complete) return
            }
            val workerOutcome = liveWorkers.retire(live)
            if (workerOutcome is LiveCompletion.Complete) {
                if (progress.installation == ScopingPreparation.ReleaseStep.NotAttempted) {
                    progress = progress.copy(installation = ScopingPreparation.ReleaseStep.Unknown)
                    val outcome = owner.capability.retireInstallation(owner)
                    progress = observeLiveRetirement(progress, outcome)
                    if (outcome !is io.mazewall.platform.native.LiveCompletion.Complete) return
                }
                liveHelperRetirement.close()
                if (progress.landlock == ScopingPreparation.ReleaseStep.NotAttempted) {
                    progress = progress.copy(landlock = ScopingPreparation.ReleaseStep.Unknown)
                    preparedLandlock?.close()
                    progress = progress.copy(landlock = ScopingPreparation.ReleaseStep.Complete)
                }
                if (progress.resources == ScopingPreparation.ReleaseStep.NotAttempted) {
                    progress = progress.copy(resources = ScopingPreparation.ReleaseStep.Unknown)
                    preparedOperation?.retire()
                    progress = progress.copy(resources = ScopingPreparation.ReleaseStep.Complete)
                }
            } else {
                progress = progress.copy(
                    nativeOutcome = workerOutcome,
                    failure = workerRetirementFailure(progress.failure, workerOutcome),
                )
            }
        } catch (failure: Throwable) {
            progress = progress.copy(failure = CleanupFailures.merge(progress.failure, failure))
            throw failure
        } finally {
            synchronized(lock) { liveRelease = progress.copy(pass = ScopingPreparation.ReleasePass.Retained) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Failed helper reap retains every original; later retries keep the first cleanup failure.
    private fun releaseConfinedResources(deadline: ConfinedDeadline) {
        val claimed = synchronized(lock) {
            when (helperRelease) {
                HelperRelease.Released, HelperRelease.Releasing -> false
                HelperRelease.Retained -> {
                    check(helperPreparationEnded.count == 0L && physicalWorkersStopped(executor, threads))
                    helperRelease = HelperRelease.Releasing
                    helperCleanupActor = Thread.currentThread()
                    true
                }
            }
        }
        if (!claimed) return
        var released = false
        try {
            liveHelperRetirement.retireSessionAndPins(deadline)
            if (!operationClosed) {
                preparedOperation?.retire()
                operationClosed = true
            }
            if (!landlockClosed) {
                preparedLandlock?.close()
                landlockClosed = true
            }
            released = true
        } catch (failure: Throwable) {
            recordCleanupFailure(cleanupFailure, failure)
            throw failure
        } finally {
            synchronized(lock) {
                helperCleanupActor = null
                helperRelease = if (released) HelperRelease.Released else HelperRelease.Retained
            }
        }
    }

    internal fun closeLiveAdmission(endpoint: LiveObservationEndpoint? = null) {
        if (scoping !is FrozenStackScoping.Live) return
        if (endpoint != null) {
            val lost = synchronized(lock) {
                val slot = endpoint.slot
                if (!liveWorkers.owns(slot) || !slot.endpointIsInstalled(endpoint)) {
                    false
                } else {
                    slot.endpoint = LiveEndpointState.Lost(endpoint)
                    slot.revokePublication()
                    if (phase == GenerationPhase.READY) {
                        phase = GenerationPhase.DRAINING
                        true
                    } else {
                        false
                    }
                }
            }
            if (lost) fail(IllegalStateException("Live observation endpoint was lost"))
        } else {
            synchronized(lock) {
                liveWorkers.revokeAllTasks()
                if (phase == GenerationPhase.READY) phase = GenerationPhase.DRAINING
            }
            invalidateLiveAdmission()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Retain the single original invalidate outcome before exposing a cleanup failure.
    private fun invalidateLiveAdmission() {
        val live = scoping as? FrozenStackScoping.Live ?: return
        var progress = synchronized(lock) {
            val current = checkNotNull(liveRelease)
            if (current.pass == ScopingPreparation.ReleasePass.Releasing ||
                current.invalidate != ScopingPreparation.ReleaseStep.NotAttempted ||
                current.earlyInvalidateAttempted ||
                current.nativeOutcome != null ||
                current.failure != null
            ) {
                return
            }
            current
                .copy(
                    pass = ScopingPreparation.ReleasePass.Releasing,
                    invalidate = ScopingPreparation.ReleaseStep.Unknown,
                    earlyInvalidateAttempted = true,
                ).also { liveRelease = it }
        }
        try {
            val owner = live.bootstrap.staging
            progress = observeLiveInvalidation(progress, owner.capability.invalidate(owner))
        } catch (failure: Throwable) {
            progress = progress.copy(failure = CleanupFailures.merge(progress.failure, failure))
            restoreInterrupt(failure)
            throw failure
        } finally {
            synchronized(lock) { liveRelease = progress.copy(pass = ScopingPreparation.ReleasePass.Retained) }
        }
    }

    fun validateResources() {
        preparedLandlock?.validate()
        preparedOperation?.validate()
        if (confined != null) requireHelperReady(ConfinedDeadline.mint(environment.native) { HELPER_TIMEOUT_NANOS })
    }

    internal val needsResourceValidation: Boolean get() = preparedLandlock != null

    fun retired(): Boolean =
        physicalWorkersStopped(executor, threads) &&
            liveWorkerAdmission.receiversExited() &&
            (retirement?.finished() != false) &&
            (preparedOperation?.isDirectResult() != true || synchronized(lock) { directReleased }) &&
            synchronized(lock) { liveReleaseComplete(liveRelease) } &&
            synchronized(lock) { helperRelease == HelperRelease.Released }

    fun drainSnapshot(): DomainDrainSnapshot {
        // All asynchronous failure producers must physically end before a successful
        // observation. Later completion cannot upgrade this captured pending state.
        val workersStopped = physicalWorkersStopped(executor, threads)
        val outcome = retirement?.outcome()
        val retired =
            workersStopped &&
                liveWorkerAdmission.receiversExited() &&
                (retirement == null || outcome?.releaseAttempted == true) &&
                (preparedOperation?.isDirectResult() != true || synchronized(lock) { directReleased }) &&
                synchronized(lock) { liveReleaseComplete(liveRelease) } &&
                synchronized(lock) { helperRelease == HelperRelease.Released }
        val live = liveWorkers()
        val retained = cleanupFailure.get()
        val liveFailure = synchronized(lock) { liveRelease?.failure }
        val history = liveFailure?.let { CleanupFailures.merge(retained, it) } ?: retained
        val failure = outcome?.failure?.let { CleanupFailures.merge(history, it) } ?: history
        return DomainDrainSnapshot(retired, live, pending.size, failure)
    }

    fun permitsInlineContinuation(): Boolean =
        synchronized(lock) {
        failure == null && (phase == GenerationPhase.READY || phase == GenerationPhase.DRAINING)
    }

    fun failedRetirementPending(): Boolean = synchronized(lock) { failure != null && !retired() }

    fun liveWorkers(): Int = threads.count { it.isAlive } + if (retirement?.live() == true) 1 else 0

    fun recordRejection() {
        rejected.incrementAndGet()
    }

    fun diagnostics(runtimeAccepting: Boolean): ExecutionDiagnostics =
        synchronized(lock) {
        val cleanupFailed = cleanupFailure.get() != null
        val sampled = when (phase) {
            GenerationPhase.PREPARING -> ExecutionDiagnosticState.PREPARING
            GenerationPhase.READY -> if (runtimeAccepting) ExecutionDiagnosticState.READY else ExecutionDiagnosticState.DRAINING
            GenerationPhase.DRAINING -> ExecutionDiagnosticState.DRAINING
            GenerationPhase.FAILED -> ExecutionDiagnosticState.FAILED
            GenerationPhase.CLOSED -> ExecutionDiagnosticState.CLOSED
        }
        ExecutionDiagnostics(
            if (failure != null || cleanupFailed) ExecutionDiagnosticState.FAILED else sampled,
            ExecutionGeneration.InProcess(generation),
            invocationMetrics.snapshot(admitted.get(), rejected.get()),
            liveWorkers(),
            pending.size,
            phase == GenerationPhase.CLOSED && retired() && !cleanupFailed && pending.isEmpty(),
        )
    }

    fun finished(
        task: OwnedInvocation<*>,
        removeQueued: Boolean = false,
    ) {
        if (removeQueued) executor.remove(task)
        check(pending.remove(task)) { "Invocation admission already released" }
        admission.release()
    }
}

private class LiveWorkers(
    private val domain: OwnedDomain,
    private val gate: Any,
    capacity: Int,
    private val native: NativeEngine,
) {
    private val slots = List(capacity) { LiveWorkerSlot(it, gate) }

    fun slot(index: Int): LiveWorkerSlot = slots[index]

    fun owns(slot: LiveWorkerSlot): Boolean = slots.getOrNull(slot.index) === slot

    fun currentSlot(): LiveWorkerSlot? = slots.firstOrNull { it.thread === Thread.currentThread() }

    fun hasActiveTask(): Boolean = slots.any { it.activeTask != null }

    fun allObservationInstalled(): Boolean = slots.all { it.endpoint is LiveEndpointState.Installed }

    fun revokeAllTasks() = slots.forEach { it.revokePublication() }

    fun register(
        slot: LiveWorkerSlot,
        live: FrozenStackScoping.Live,
    ): WorkerOwner {
        live.bootstrap.reserveLiveWorker(domain, slot)
        val owner = live.bootstrap.sealed
        slot.tid = native.process.gettid()
        val receipt = owner.capability.prepareWorkerReceipt(owner)
        slot.receipt = receipt
        slot.registrationAttempted = true
        when (val result = owner.capability.registerCurrent(owner, receipt)) {
            is LiveSetup.Ready -> {
                validateLiveWorker(slot, live, result.value)
                slot.worker = result.value
                return result.value
            }
            is LiveSetup.Refused -> throw IllegalStateException("Live worker registration refused: ${result.failure}", result.failure.cause)
        }
    }

    private fun validateLiveWorker(
        slot: LiveWorkerSlot,
        live: FrozenStackScoping.Live,
        worker: WorkerOwner,
    ) {
        check(worker.owner === live.bootstrap.sealed && worker.enrollment === live.bootstrap.enrollment && worker.tid == slot.tid) {
            "Live worker identity differs from its original slot"
        }
    }

    fun retire(live: FrozenStackScoping.Live): LiveCompletion {
        for (slot in slots) {
            if (!slot.registrationReserved) continue
            check(slot.thread?.isAlive == false) { "Live worker must be joined before retirement" }
            if (!slot.registrationAttempted) continue
            var worker = slot.worker
            if (worker == null) {
                check(!slot.recoveryAttempted) { "Live worker registration remains unknown" }
                slot.recoveryAttempted = true
                when (
                    val recovered = live.bootstrap.sealed.capability
                        .recoverWorker(checkNotNull(slot.receipt))
                ) {
                    is LiveSetup.Ready -> {
                        validateLiveWorker(slot, live, recovered.value)
                        worker = recovered.value
                        slot.worker = worker
                    }
                    is LiveSetup.Refused -> return LiveCompletion.Refused(recovered.failure)
                }
            }
            if (!slot.retirementAttempted) {
                slot.retirementAttempted = true
                slot.retirement = live.bootstrap.sealed.capability
                    .retireWorker(checkNotNull(worker))
            }
            when (val outcome = slot.retirement) {
                LiveCompletion.Complete -> slot.activeTask = null
                is LiveCompletion.Pending, is LiveCompletion.Refused -> return outcome
                null -> error("Live worker retirement remains unknown")
            }
        }
        return LiveCompletion.Complete
    }
}

internal class LiveWorkerSlot(
    val index: Int,
    private val gate: Any,
) {
    var thread: Thread? = null
    var tid: Tid? = null
    var receipt: WorkerRegistrationReceipt? = null
    var registrationReserved = false
    var registrationFailure: Throwable? = null
    var registrationAttempted = false
    var worker: WorkerOwner? = null
    var recoveryAttempted = false
    var retirementAttempted = false
    var retirement: LiveCompletion? = null
    var activeTask: OwnedInvocation<*>? = null
    var endpoint: LiveEndpointState = LiveEndpointState.NotInstalled
    private var publication: LiveTaskPublication? = null
    private var revokedPublication: LiveTaskPublication? = null
    private var cleanupLease: io.mazewall.platform.native.TaskLease? = null

    fun hasPublication(): Boolean = publication != null

    fun hasCleanup(): Boolean = cleanupLease != null

    fun retainCleanup(
        invocation: OwnedInvocation<*>,
        lease: io.mazewall.platform.native.TaskLease,
    ) {
        check(Thread.holdsLock(gate) && thread === Thread.currentThread() && activeTask === invocation && worker === lease.worker)
        check(invocation.retainsLiveLease(lease) && !hasCleanup())
        cleanupLease = lease
    }

    fun publishRetained(
        invocation: OwnedInvocation<*>,
        lease: io.mazewall.platform.native.TaskLease,
    ) {
        check(Thread.holdsLock(gate) && thread === Thread.currentThread() && activeTask === invocation && worker === lease.worker)
        check(cleanupMatches(invocation, lease) && endpoint !is LiveEndpointState.Lost && !hasPublication())
        publication = LiveTaskPublication(invocation, lease)
        cleanupLease = null
    }

    fun endpointIsInstalled(candidate: LiveObservationEndpoint): Boolean =
        when (val state = endpoint) {
            LiveEndpointState.NotInstalled -> false
            is LiveEndpointState.Installed -> state.endpoint === candidate
            is LiveEndpointState.Lost -> false
        }

    fun claimPublication(candidate: LiveObservationEndpoint): LiveTaskPublication? {
        val current = publication
        return if (Thread.holdsLock(gate) &&
            endpointIsInstalled(candidate) &&
            current != null &&
            activeTask === current.invocation &&
            worker === current.lease.worker
        ) {
            current
        } else {
            null
        }
    }

    fun isCurrentPublication(
        candidate: LiveObservationEndpoint,
        claim: LiveTaskPublication,
    ): Boolean = claimPublication(candidate) === claim

    fun publishedMatches(
        invocation: OwnedInvocation<*>,
        lease: io.mazewall.platform.native.TaskLease,
    ): Boolean {
        val current = publication ?: return false
        return current.invocation === invocation && current.lease === lease
    }

    fun revokedMatches(
        invocation: OwnedInvocation<*>,
        lease: io.mazewall.platform.native.TaskLease,
    ): Boolean {
        val previous = revokedPublication ?: return false
        return previous.invocation === invocation && previous.lease === lease
    }

    fun wasRevokedFor(invocation: OwnedInvocation<*>): Boolean {
        val previous = revokedPublication ?: return false
        return previous.invocation === invocation
    }

    fun cleanupMatches(
        invocation: OwnedInvocation<*>,
        lease: io.mazewall.platform.native.TaskLease,
    ): Boolean = activeTask === invocation && cleanupLease === lease

    fun revokePublication() {
        check(Thread.holdsLock(gate))
        publication?.let {
            revokedPublication = it
            publication = null
        }
    }

    fun complete(invocation: OwnedInvocation<*>) {
        synchronized(gate) {
            check(activeTask === invocation && thread === Thread.currentThread())
            activeTask = null
            cleanupLease = null
        }
    }
}

internal class LiveTaskPublication(
    val invocation: OwnedInvocation<*>,
    val lease: io.mazewall.platform.native.TaskLease,
)

internal sealed interface LiveEndpointState {
    data object NotInstalled : LiveEndpointState

    class Installed(
        val endpoint: LiveObservationEndpoint,
    ) : LiveEndpointState

    class Lost(
        val endpoint: LiveObservationEndpoint,
    ) : LiveEndpointState
}

internal class DomainInvocationMetrics {
    private val cancellationRequests = AtomicLong()
    private val delivered = AtomicLong()
    private val ended = AtomicLong()

    fun recordCancellation() {
        cancellationRequests.incrementAndGet()
    }

    fun recordDelivery() {
        delivered.incrementAndGet()
    }

    fun recordEnd() {
        ended.incrementAndGet()
    }

    fun snapshot(
        admitted: Long,
        rejected: Long,
    ): InvocationDiagnostics = InvocationDiagnostics(admitted, rejected, cancellationRequests.get(), delivered.get(), ended.get())
}

private fun workerRetirementFailure(
    previous: Throwable?,
    outcome: LiveCompletion,
): Throwable? =
    when (outcome) {
        is LiveCompletion.Pending -> previous
        is LiveCompletion.Refused -> CleanupFailures.merge(
            previous,
            IllegalStateException("Native worker retirement refused: ${outcome.failure}", outcome.failure.cause),
        )
        LiveCompletion.Complete -> error("Unreachable completed worker retirement")
    }

private val noAfterDomainCommit: () -> Unit = {}

private fun requireSupportedScopingStart(scoping: FrozenStackScoping) {
    when (scoping) {
        is FrozenStackScoping.Legacy -> Unit
        is FrozenStackScoping.Live -> throw UnsupportedOperationException("Live stack scoping dispatcher is not available")
    }
}

private fun physicalWorkersStopped(
    executor: ThreadPoolExecutor,
    threads: List<Thread>,
): Boolean = executor.isTerminated && threads.none { it.isAlive }

private fun awaitPhysicalWorkers(
    helperPreparationEnded: CountDownLatch,
    executor: ThreadPoolExecutor,
    threads: List<Thread>,
) {
    var interrupted = Thread.interrupted()
    try {
        while (helperPreparationEnded.count != 0L) {
            try {
                helperPreparationEnded.await()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        while (!executor.isTerminated) {
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        threads.forEach { worker ->
            while (worker.isAlive) {
                try {
                    worker.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

private data class LiveRelease(
    val pass: ScopingPreparation.ReleasePass = ScopingPreparation.ReleasePass.Retained,
    val invalidate: ScopingPreparation.ReleaseStep = ScopingPreparation.ReleaseStep.NotAttempted,
    val installation: ScopingPreparation.ReleaseStep = ScopingPreparation.ReleaseStep.NotAttempted,
    val landlock: ScopingPreparation.ReleaseStep = ScopingPreparation.ReleaseStep.NotAttempted,
    val resources: ScopingPreparation.ReleaseStep = ScopingPreparation.ReleaseStep.NotAttempted,
    val nativeOutcome: io.mazewall.platform.native.LiveCompletion? = null,
    val failure: Throwable? = null,
    val earlyInvalidateAttempted: Boolean = false,
) {
    fun mayRelease(allowPendingRetry: Boolean): Boolean {
        val available = pass != ScopingPreparation.ReleasePass.Releasing &&
            failure == null &&
            nativeOutcome !is LiveCompletion.Refused
        val earlyPending = earlyInvalidateAttempted && nativeOutcome is LiveCompletion.Pending && !allowPendingRetry
        val unknown = listOf(invalidate, installation, landlock, resources).contains(ScopingPreparation.ReleaseStep.Unknown)
        return available && !earlyPending && !unknown
    }
}

private fun restoreInterrupt(failure: Throwable) {
    if (failure.wasInterrupted()) Thread.currentThread().interrupt()
}

private fun Throwable.wasInterrupted(): Boolean = this is InterruptedException

private fun liveReleaseComplete(progress: LiveRelease?): Boolean =
    progress == null ||
        (
            progress.pass == ScopingPreparation.ReleasePass.Retained &&
            progress.invalidate == ScopingPreparation.ReleaseStep.Complete &&
            progress.installation == ScopingPreparation.ReleaseStep.Complete &&
            progress.landlock == ScopingPreparation.ReleaseStep.Complete &&
            progress.resources == ScopingPreparation.ReleaseStep.Complete
        )

private fun recordCleanupFailure(
    retained: AtomicReference<Throwable?>,
    problem: Throwable,
) {
    retained.updateAndGet { CleanupFailures.merge(it, problem) }
}

private fun observeLiveInvalidation(
    progress: LiveRelease,
    outcome: io.mazewall.platform.native.LiveCompletion,
): LiveRelease =
    when (outcome) {
        io.mazewall.platform.native.LiveCompletion.Complete -> progress.copy(invalidate = ScopingPreparation.ReleaseStep.Complete, nativeOutcome = null)
        is io.mazewall.platform.native.LiveCompletion.Pending -> progress.copy(invalidate = ScopingPreparation.ReleaseStep.NotAttempted, nativeOutcome = outcome)
        is io.mazewall.platform.native.LiveCompletion.Refused -> progress.copy(
            invalidate = ScopingPreparation.ReleaseStep.NotAttempted,
            nativeOutcome = outcome,
            failure = CleanupFailures.merge(progress.failure, IllegalStateException("Native invalidation refused: ${outcome.failure}", outcome.failure.cause)),
        )
    }

private fun observeLiveRetirement(
    progress: LiveRelease,
    outcome: io.mazewall.platform.native.LiveCompletion,
): LiveRelease =
    when (outcome) {
        io.mazewall.platform.native.LiveCompletion.Complete -> progress.copy(installation = ScopingPreparation.ReleaseStep.Complete, nativeOutcome = null)
        is io.mazewall.platform.native.LiveCompletion.Pending -> progress.copy(installation = ScopingPreparation.ReleaseStep.NotAttempted, nativeOutcome = outcome)
        is io.mazewall.platform.native.LiveCompletion.Refused -> progress.copy(
            installation = ScopingPreparation.ReleaseStep.NotAttempted,
            nativeOutcome = outcome,
            failure = CleanupFailures.merge(progress.failure, IllegalStateException("Native retirement refused: ${outcome.failure}", outcome.failure.cause)),
        )
    }

private const val MAX_LIVE_VALIDATION_ATTEMPTS = 200
