package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy
import io.mazewall.enforcer.api.InstallationEnvironment
import io.mazewall.enforcer.api.OperationBinding
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.ReviewedOperationArtifact
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.api.Uncompiled
import io.mazewall.enforcer.api.frozenCopy
import io.mazewall.enforcer.api.hasWorkerFilesystemIntent
import io.mazewall.enforcer.diagnostics.requirePlatformThread
import io.mazewall.enforcer.internal.containmentFailureForCaller
import io.mazewall.enforcer.internal.execution.ConfinedHelperConfiguration
import io.mazewall.enforcer.internal.execution.Deadline
import io.mazewall.enforcer.internal.execution.DomainDrainSnapshot
import io.mazewall.enforcer.internal.execution.DrainLifecycle
import io.mazewall.enforcer.internal.execution.ExecutionTransition
import io.mazewall.enforcer.internal.execution.ExecutionTransitionGuard
import io.mazewall.enforcer.internal.execution.HelperSupportLifetime
import io.mazewall.enforcer.internal.execution.OwnedDomain
import io.mazewall.enforcer.internal.execution.ReviewedHelperSupport
import io.mazewall.enforcer.internal.execution.RuntimeWorkerContext
import io.mazewall.enforcer.internal.execution.ScopingPreparation
import io.mazewall.enforcer.internal.supervisor.SupervisorContext
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.platform.fd.CleanupFailures
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Application-owned synchronous platform workers. The explicit process baseline is permanent.
 * Caller timeout/cancellation is not physical completion; no caller-runs or shared-worker fallback.
 * Completion observers, lazy results and unrelated helper executors are outside the body contract.
 */
public class ManagedRuntime private constructor(
    public val runtime: RuntimeProfile,
    private val environment: InstallationEnvironment,
    private val preparationTimeout: Duration,
    private val closeTimeout: Duration,
    private val legacyAcquisitionEffects: LegacyAcquisitionEffects,
    private val operationsPermit: Any,
) : AutoCloseable {
    private constructor(runtime: RuntimeProfile, environment: InstallationEnvironment, preparationTimeout: Duration, closeTimeout: Duration, effects: LegacyAcquisitionEffects) :
        this(runtime, environment, preparationTimeout, closeTimeout, effects, Any())

    private constructor(canonical: CanonicalAcquisition, permit: Any, preparationTimeout: Duration, closeTimeout: Duration) :
        this(
            RuntimeProfile.HOTSPOT_JIT.also {
            check(canonical.permitsBootstrap(permit))
        },
            canonical.captureEnvironment(permit),
            preparationTimeout,
            closeTimeout,
            defaultLegacyAcquisitionEffects,
            permit,
        ) {
        canonical.checkedAssociation(permit)?.let { association ->
            checkedOperations = CheckedOperationsImpl(this, shared, association, permit)
        }
        phase = Phase.BOOTSTRAPPING
    }

    private constructor(runtime: RuntimeProfile, environment: InstallationEnvironment, preparationTimeout: Duration, closeTimeout: Duration) :
        this(runtime, environment, preparationTimeout, closeTimeout, defaultLegacyAcquisitionEffects)

    private constructor(runtime: RuntimeProfile, preparationTimeout: Duration, closeTimeout: Duration) :
        this(runtime, InstallationEnvironment.capture(Platform.FallbackBehavior.FAIL), preparationTimeout, closeTimeout) {
        phase = Phase.BOOTSTRAPPING
    }

    internal var checkedOperations: CheckedOperations? = null
        private set

    private enum class Phase { BOOTSTRAPPING, OPEN, DRAINING, CLOSED }

    private enum class CheckedAttachPhase { Awaiting, RawRetained, ReducerAttached }

    private class RuntimeState {
        val lock = Any()
        var phase = Phase.OPEN
        val domains = LinkedHashMap<Long, OwnedDomain>()
        var preparations = 0
        var scopingPreparation: ScopingPreparation? = null
        var checkedPreparation: ScopingPreparation? = null
        var checkedProducer: Thread? = null
        var checkedClaimed = false
        var checkedAttachPhase = CheckedAttachPhase.Awaiting

        @Volatile var checkedRawOwner: io.mazewall.platform.native.InstallationOwner? = null

        @Volatile var checkedRawFailure: Throwable? = null

        @Volatile var checkedAttachmentFailure: Throwable? = null

        @Volatile var checkedNotificationFailure: Throwable? = null
        var checkedUnknown = false
        val helperDrainFailure = AtomicReference<Throwable?>()
        var nextGeneration = 0L
    }

    private val shared = RuntimeState()
    private val lock = shared.lock
    private var phase: Phase
        get() = shared.phase
        set(value) {
            shared.phase = value
        }
    private val id = identities.incrementAndGet()
    private var nextGeneration: Long
        get() = shared.nextGeneration
        set(value) {
            shared.nextGeneration = value
        }
    private val domains = shared.domains
    private var preparations: Int
        get() = shared.preparations
        set(value) {
            shared.preparations = value
        }
    private var scopingPreparation: ScopingPreparation?
        get() = shared.scopingPreparation
        set(value) {
            shared.scopingPreparation = value
        }
    private val legacyOwnershipKey = Any()
    private var helperDrainMode = false
    private var helperDrainSweep: Future<*>? = null
    private val helperDrainFailure = shared.helperDrainFailure
    private val managerThread = AtomicReference<Thread?>()
    private val manager = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        ThreadFactory { task ->
            check(managerThread.get() == null) { "A failed manager cannot be replaced by a caller" }
            Thread
                .ofPlatform()
                .name("mazewall-manager-$id")
                .inheritInheritableThreadLocals(false)
                .unstarted(task)
                .also { check(managerThread.compareAndSet(null, it)) }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private val scopingFailureBoundary = ScopingFailureBoundary(this, shared, operationsPermit)
    private val legacyControl = RuntimeLegacyControl(this, shared, legacyOwnershipKey, lock, manager, managerThread, environment, legacyAcquisitionEffects, operationsPermit)
    private val legacyDrainBoundary = DrainBoundary(this, shared, legacyControl, managerThread, operationsPermit)
    private val operations = RuntimeOperations(this, shared, operationsPermit, manager, managerThread, environment, legacyControl, preparationTimeout, closeTimeout, runtime)

    private class RuntimeOperations(
        private val runtimeOwner: ManagedRuntime,
        private val shared: RuntimeState,
        private val permit: Any,
        private val manager: ThreadPoolExecutor,
        private val managerThread: AtomicReference<Thread?>,
        private val environment: InstallationEnvironment,
        private val legacyControl: RuntimeLegacyControl,
        private val preparationTimeout: Duration,
        private val closeTimeout: Duration,
        private val runtime: RuntimeProfile,
    ) {
        private val lock = shared.lock
        private val domains = shared.domains
        private var phase: Phase
            get() = shared.phase
            set(value) {
                shared.phase = value
            }
        private val scopingPreparation: ScopingPreparation?
            get() = shared.scopingPreparation
        private var nextGeneration: Long
            get() = shared.nextGeneration
            set(value) {
                shared.nextGeneration = value
            }
        private var canonicalOwner: CanonicalAcquisition? = null
        private var canonicalTask: FutureTask<Unit>? = null
        private var initialBaselineReserved = false
        private var baselineDispatchReserved = false
        private var scopingRelease: Future<*>? = null

        private fun authenticate(candidatePermit: Any) {
            check(candidatePermit === permit && runtimeOwner.matchesOriginalState(shared, this, candidatePermit))
        }

        fun claimCanonicalOwner(
            candidatePermit: Any,
            owner: CanonicalAcquisition,
        ) {
            authenticate(candidatePermit)
            check(owner.matchesRuntime(runtimeOwner))
            synchronized(lock) {
                check(phase == Phase.BOOTSTRAPPING && canonicalOwner == null && !initialBaselineReserved)
                canonicalOwner = owner
            }
        }

        fun isCanonicalManager(
            candidatePermit: Any,
            owner: CanonicalAcquisition,
        ): Boolean = candidatePermit === permit && runtimeOwner.matchesOriginalState(shared, this, candidatePermit) && canonicalOwner === owner && Thread.currentThread() === managerThread.get()

        private fun reserveBaselineDispatch(
            candidatePermit: Any,
            owner: CanonicalAcquisition?,
        ): Boolean {
            if (candidatePermit !== permit || !runtimeOwner.matchesOriginalState(shared, this, candidatePermit)) return false
            return synchronized(lock) {
                if (Thread.currentThread() === managerThread.get() &&
                    phase == Phase.BOOTSTRAPPING &&
                    initialBaselineReserved &&
                    !baselineDispatchReserved &&
                    canonicalOwner === owner
                ) {
                    baselineDispatchReserved = true
                    true
                } else {
                    false
                }
            }
        }

        fun reserveCanonicalDispatch(
            candidatePermit: Any,
            owner: CanonicalAcquisition,
        ): Boolean = reserveBaselineDispatch(candidatePermit, owner)

        // The original manager records any failure before the waiting owner observes its outcome.
        @Suppress("TooGenericExceptionCaught")
        fun canonicalSubmit(
            candidatePermit: Any,
            owner: CanonicalAcquisition,
        ) {
            authenticate(candidatePermit)
            val task = FutureTask {
                check(isCanonicalManager(candidatePermit, owner))
                if (owner.entered(runtimeOwner)) {
                    try {
                        environment.platform.capabilities
                        ContainedExecutors.installOnProcess(Policy.NO_EXEC_HOTSPOT.definition.frozenCopy(), environment).use { it.requireInstalled() }
                        owner.finished(runtimeOwner, null)
                    } catch (failure: Throwable) {
                        owner.finished(runtimeOwner, failure)
                    }
                }
            }
            synchronized(lock) {
                check(canonicalOwner === owner && phase == Phase.BOOTSTRAPPING && !initialBaselineReserved)
                initialBaselineReserved = true
                canonicalTask = task
            }
            manager.execute(task)
            manager.execute { owner.returned(runtimeOwner) }
        }

        fun matchesDiscardedCanonical(
            candidatePermit: Any,
            owner: CanonicalAcquisition,
            task: Runnable,
        ): Boolean =
            synchronized(lock) {
            authenticate(candidatePermit)
            canonicalOwner === owner && canonicalTask === task && phase == Phase.DRAINING
        }

        fun canonicalCancel(
            candidatePermit: Any,
            owner: CanonicalAcquisition,
        ) {
            authenticate(candidatePermit)
            val task = synchronized(lock) {
                check(canonicalOwner === owner)
                canonicalTask
            }
            task?.cancel(true)
        }

        fun canonicalManagerTerminated(
            candidatePermit: Any,
            owner: CanonicalAcquisition,
        ): Boolean {
            authenticate(candidatePermit)
            check(canonicalOwner === owner)
            return manager.isTerminated && managerThread.get()?.isAlive != true
        }

        fun canonicalTransferredHelperRetired(
            candidatePermit: Any,
            owner: CanonicalAcquisition,
            context: SupervisorContext,
        ): Boolean {
            authenticate(candidatePermit)
            check(canonicalOwner === owner)
            if (!canonicalManagerTerminated(candidatePermit, owner)) return false
            return synchronized(lock) {
                val settled = scopingPreparation?.state as? ScopingPreparation.State.TransferredSettled
                val domain = settled?.domain
                val live = domain?.scoping as? io.mazewall.enforcer.internal.execution.FrozenStackScoping.Live
                (phase == Phase.DRAINING || phase == Phase.CLOSED) &&
                    shared.preparations == 0 &&
                    !shared.checkedUnknown &&
                    domain != null &&
                    (domains as Map<*, *>)[domain.registrationKey] === domain &&
                    live != null &&
                    live.bootstrap.helperContext === context &&
                    domains.values.all { it.retired() }
            }
        }

        fun canonicalActivate(
            candidatePermit: Any,
            owner: CanonicalAcquisition,
        ): Boolean =
            synchronized(lock) {
            authenticate(candidatePermit)
            check(canonicalOwner === owner)
            if (phase == Phase.BOOTSTRAPPING) {
                phase = Phase.OPEN
                true
            } else {
                false
            }
        }

        fun installInitialBaseline(
            candidatePermit: Any,
            baseline: Policy<PolicyScope.ProcessWideSafe, Uncompiled>,
        ) {
            authenticate(candidatePermit)
            requirePlatformThread()
            requirePositive(preparationTimeout)
            requirePositive(closeTimeout)
            requireUnrestrictedBootstrap()
            require(!baseline.definition.hasWorkerFilesystemIntent()) { "The host baseline must have process-safe scope" }
            require(baseline.allowMmapExec == runtime.allowsExecutableMappings) { "Process mapping floor does not match the declared runtime" }
            synchronized(lock) {
                check(phase == Phase.BOOTSTRAPPING && !initialBaselineReserved) { "Initial baseline already reserved" }
                initialBaselineReserved = true
            }
            awaitPreparation(manager.submit { installInitialBaselineOnManager(candidatePermit, baseline) }, preparationTimeout)
            synchronized(lock) {
                check(phase == Phase.BOOTSTRAPPING) { "Runtime was retired during bootstrap" }
                phase = Phase.OPEN
            }
        }

        fun installInitialBaselineOnManager(
            candidatePermit: Any,
            baseline: Policy<PolicyScope.ProcessWideSafe, Uncompiled>,
        ) {
            authenticate(candidatePermit)
            check(reserveBaselineDispatch(candidatePermit, null))
            environment.platform.capabilities
            ContainedExecutors.installOnProcess(baseline.definition.frozenCopy(), environment).use { it.requireInstalled() }
        }

        fun managerTerminated(candidatePermit: Any): Boolean = candidatePermit === permit && runtimeOwner.matchesOriginalState(shared, this, candidatePermit) && manager.isTerminated

        fun requireAdmission(candidatePermit: Any) =
            synchronized(lock) {
            authenticate(candidatePermit)
            requireRuntimeOpen(phase == Phase.OPEN)
        }

        fun retireFailedScopingClaim(
            candidatePermit: Any,
            preparation: ScopingPreparation,
        ) {
            authenticate(candidatePermit)
            synchronized(lock) {
                check(Thread.currentThread() === managerThread.get() && scopingPreparation === preparation)
            }
            requestScopingRelease(candidatePermit, preparation)
        }

        fun mayAwaitHelperSweep(
            candidatePermit: Any,
            control: RuntimeLegacyControl,
        ): Boolean =
            synchronized(lock) {
            authenticate(candidatePermit)
            check(control === legacyControl && (phase == Phase.DRAINING || phase == Phase.CLOSED))
            phase == Phase.DRAINING
        }

        fun mayReleaseScoping(
            candidatePermit: Any,
            preparation: ScopingPreparation,
        ): Boolean =
            synchronized(lock) {
            authenticate(candidatePermit)
            Thread.currentThread() === managerThread.get() && scopingPreparation === preparation && (phase == Phase.OPEN || phase == Phase.DRAINING)
        }

        fun awaitConfinedSweep(
            candidatePermit: Any,
            control: RuntimeLegacyControl,
            deadline: Deadline,
        ) {
            authenticate(candidatePermit)
            synchronized(lock) {
                check(control === legacyControl && (phase == Phase.DRAINING || phase == Phase.CLOSED))
                if (phase == Phase.CLOSED) return
            }
            val sweep = runtimeOwner.awaitHelperSweep(control, deadline)
            try {
                sweep?.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS)
                if (manager.isShutdown) {
                    manager.awaitTermination(deadline.remainingNanos(), TimeUnit.NANOSECONDS)
                    managerThread.get()?.let(deadline::join)
                }
            } catch (_: TimeoutException) {
                // Caller budget expires without cancelling the retained sweep.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: ExecutionException) {
                // The manager recorded the failure before completing the future.
            } catch (_: CancellationException) {
                // The bounded manager rejected this sweep.
            }
        }

        fun awaitScopingDrainRelease(
            candidatePermit: Any,
            control: RuntimeLegacyControl,
            preparation: ScopingPreparation,
            deadline: Deadline,
        ) {
            authenticate(candidatePermit)
            synchronized(lock) {
                check(control === legacyControl && scopingPreparation === preparation && (phase == Phase.DRAINING || phase == Phase.CLOSED))
                if (phase == Phase.CLOSED) return
            }
            requestScopingRelease(candidatePermit, preparation)?.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS)
        }

        fun drainLegacy(
            candidatePermit: Any,
            deadline: Deadline,
        ): DrainReport {
            authenticate(candidatePermit)
            val resources = synchronized(lock) { domains.values.toList() }
            resources.forEach { it.beginDrain() }
            if (!legacyControl.awaitCurrent(deadline)) {
                val snapshots = resources.map { it.drainSnapshot() }
                val acquisition = legacyControl.acquisitionSnapshot()
                return DrainLifecycle.evaluate(false, managerThread.get()?.isAlive == true, listOfNotNull(acquisition) + snapshots)
            }
            val discarded = manager.shutdownNow()
            val canonical = canonicalOwner
            if (canonical != null) {
                discarded.forEach { task ->
                if (matchesDiscardedCanonical(candidatePermit, canonical, task)) canonical.discarded(runtimeOwner, task)
            }
            }
            try {
                resources.forEach { it.awaitRetirement(deadline) }
                manager.awaitTermination(deadline.remainingNanos(), TimeUnit.NANOSECONDS)
                managerThread.get()?.let { deadline.join(it) }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val managerLive = managerThread.get()?.isAlive == true
            val outcome = DrainLifecycle.evaluate(manager.isTerminated && !managerLive, managerLive, resources.map { it.drainSnapshot() })
            val acquisitionFailure = legacyControl.failureObservation()
            if (acquisitionFailure != null) {
                val incomplete = outcome as? DrainReport.Incomplete
                return DrainReport.Incomplete(incomplete?.unfinishedInvocations ?: 0, incomplete?.liveWorkers ?: 0, CleanupFailures.merge(incomplete?.cleanupFailure, acquisitionFailure))
            }
            return outcome
        }

        private fun requestScopingRelease(
            candidatePermit: Any,
            preparation: ScopingPreparation,
        ): Future<*>? {
            authenticate(candidatePermit)
            val candidate = FutureTask { runtimeOwner.releaseScopingOnManager(preparation) }
            val reserved = synchronized(lock) {
                check(scopingPreparation === preparation)
                val state = preparation.state as? ScopingPreparation.State.Retiring
                if (phase == Phase.CLOSED ||
                    state == null ||
                    (
                        !state.retained.inFlight &&
                        (
                        state.retained.step in setOf(
                            ScopingPreparation.ReleaseStage.RESOURCES_UNKNOWN,
                            ScopingPreparation.ReleaseStage.NATIVE_UNKNOWN,
                            ScopingPreparation.ReleaseStage.NATIVE_REFUSED,
                        ) ||
                            manager.isShutdown ||
                            manager.isTerminated
                    )
                    )
                ) {
                        return null
                    }
                if (state.retained.inFlight) return scopingRelease
                val effects = transitionScoping(scopingPreparation, preparation, ScopingPreparation.Event.ReleaseRequested)
                check(ScopingPreparation.Effect.SubmitRelease in effects)
                scopingRelease = candidate
                candidate
            }
            try {
                manager.execute(reserved)
            } catch (_: RejectedExecutionException) {
                val effects = synchronized(lock) {
                    val state = preparation.state as ScopingPreparation.State.Retiring
                    transitionScoping(scopingPreparation, preparation, ScopingPreparation.Event.ReleaseCompleted(state.retained))
                }
                check(ScopingPreparation.Effect.Notify in effects)
                // FutureTask cancellation wakes every waiter; no release work was accepted.
                reserved.cancel(false)
            }
            return reserved
        }

        fun legacyGenerationOperation(
            candidatePermit: Any,
            candidate: Any,
            candidateKey: Any,
            action: LegacyGenerationAction,
        ): Long =
            synchronized(lock) {
            authenticate(candidatePermit)
            check(legacyControl.isOnManager(candidate, candidateKey))
            requireRuntimeOpen(phase == Phase.OPEN)
            when (action) {
                LegacyGenerationAction.Reserve -> Math.addExact(nextGeneration, 1L).also { nextGeneration = it }
                is LegacyGenerationAction.Commit -> {
                    check((candidate as LegacyAcquisition).isPendingDomain(action.domain, action.ref))
                    check(candidate.canCommit()) { "Preparation waiter abandoned before domain commit" }
                    check(domains.values.none { it.failedRetirementPending() || it.cleanupFailure.get() != null }) { "Prior failed generation still has live execution or cleanup" }
                    check(action.domain.generation <= nextGeneration)
                    domains[action.domain.generation] = action.domain
                    action.ref.set(action.domain)
                    legacyControl.transfer(candidate, candidateKey)
                    candidate.commitDirectDomain(action.domain)
                    action.domain.generation
                }
            }
        }

        fun closeScopingPreparation(
            candidatePermit: Any,
            preparation: ScopingPreparation,
        ) {
            authenticate(candidatePermit)
            val deadline = Deadline(closeTimeout)
            val claimInFlight = synchronized(lock) {
                check(scopingPreparation === preparation)
                when (val state = preparation.state) {
                    is ScopingPreparation.State.Constructing -> error("Prepared stack scoping cleanup is incomplete")
                    is ScopingPreparation.State.Ready -> {
                        transitionScoping(scopingPreparation, preparation, ScopingPreparation.Event.CloseRequested)
                        false
                    }
                    is ScopingPreparation.State.Claiming -> {
                        val effects = transitionScoping(scopingPreparation, preparation, ScopingPreparation.Event.CloseRequested)
                        check(effects.size == 1 && effects[0] === ScopingPreparation.Effect.ObserveClaim)
                        true
                    }
                    is ScopingPreparation.State.Retiring -> false
                    is ScopingPreparation.State.DomainOwned -> {
                        val effects = transitionScoping(scopingPreparation, preparation, ScopingPreparation.Event.CloseRequested)
                        check(effects.size == 1 && effects[0] === ScopingPreparation.Effect.ObserveDomain)
                        return
                    }
                    is ScopingPreparation.State.TransferredSettled -> return
                    is ScopingPreparation.State.PreparedSettled -> {
                        state.failure?.let { throw it }
                        return
                    }
                }
            }
            val first = if (claimInFlight) preparation.claimFinished else requestScopingRelease(candidatePermit, preparation)
            var waitFailure = awaitScopingClose(first, deadline)
            if (claimInFlight && waitFailure == null && synchronized(lock) { preparation.state is ScopingPreparation.State.Retiring }) {
                waitFailure = awaitScopingClose(requestScopingRelease(candidatePermit, preparation), deadline)
            }
            val state = synchronized(lock) { preparation.state }
            completeScopingClose(state, waitFailure)
        }
    }

    internal class CanonicalAcquisition(
        private val checkedRequest: io.mazewall.ffi.internal.CheckedBootstrapRequest? = null,
    ) {
        private var originalAssociation: io.mazewall.ffi.internal.CheckedBootstrapAssociation? = null
        private var helperManager: io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager? = null
        private var helperStarted = false
        private var startedHelperContext: SupervisorContext? = null

        // Startup records every failure on the owning acquisition before propagating it.
        @Suppress("TooGenericExceptionCaught")
        private fun startHelper(manager: io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager): SupervisorContext {
            synchronized(gate) {
                check(state == State.STARTING && startActor === Thread.currentThread() && helperManager == null)
                helperManager = manager
            }
            return try {
                manager.prepareConfinedReadBootstrap().also { context ->
                    synchronized(gate) {
                        helperStarted = true
                        startedHelperContext = context
                    }
                }
            } catch (failure: InterruptedException) {
                recordCleanupFailure(failure)
                Thread.currentThread().interrupt()
                throw failure
            } catch (failure: Throwable) {
                recordCleanupFailure(failure)
                throw failure
            }
        }

        private enum class State { NEW, STARTING, ACTIVE, RETIRING, COMPLETE, UNKNOWN }

        private val gate = Any()
        private val canonicalPermit = Any()
        private var startActor: Thread? = null
        private val taskReturned = java.util.concurrent.CountDownLatch(1)

        @Volatile private var original: ManagedRuntime? = null
        private var state = State.NEW
        private var closeBudget: Duration? = null
        private var setupInFlight = false
        private var retirementInFlight = false
        private var entered = false
        private var finished = false
        private var returned = false
        private var neverStarted = false

        @Volatile private var installFailure: Throwable? = null

        @Volatile private var callerFailure: Throwable? = null

        @Volatile private var cleanupFailure: Throwable? = null

        @Volatile private var metadataFailure: Throwable? = null

        @Volatile private var primary: Throwable? = null
        private var installDelivered = false
        private var callerDelivered = false
        private var cleanupDelivered = false
        private var metadataDelivered = false
        private var installUnknown = false
        private var cleanupUnknown = false
        private var metadataUnknown = false
        private var physicalComplete = false

        fun permitsBootstrap(candidatePermit: Any): Boolean =
            synchronized(gate) {
            candidatePermit === canonicalPermit && state == State.STARTING && startActor === Thread.currentThread() && original == null
        }

        fun captureEnvironment(candidatePermit: Any): InstallationEnvironment {
            val request = checkedRequest ?: return InstallationEnvironment.capture(Platform.FallbackBehavior.FAIL)
            check(permitsBootstrap(candidatePermit))
            val environment = InstallationEnvironment.capture(Platform.FallbackBehavior.FAIL)
            val association = request.check(environment.native)
            synchronized(gate) {
                check(candidatePermit === canonicalPermit && state == State.STARTING && startActor === Thread.currentThread() && original == null)
                originalAssociation = association
            }
            if (request.helperSupport != null) {
                val manager = io.mazewall.enforcer.internal.supervisor
                    .SupervisorDaemonManager(environment.native)
                val context = startHelper(manager)
                synchronized(gate) { association.bindHelperContext(this, context) }
            }
            return environment
        }

        fun requireHelperContext(
            association: io.mazewall.ffi.internal.CheckedBootstrapAssociation,
            context: SupervisorContext,
        ) {
            check(Thread.holdsLock(gate) && state == State.STARTING && startActor === Thread.currentThread() && original == null)
            check(originalAssociation === association && helperManager != null && helperStarted && startedHelperContext === context)
        }

        fun checkedAssociation(candidatePermit: Any): io.mazewall.ffi.internal.CheckedBootstrapAssociation? {
            if (checkedRequest == null) return null
            return synchronized(gate) {
                check(candidatePermit === canonicalPermit && state == State.STARTING && startActor === Thread.currentThread() && original == null)
                originalAssociation
            }
        }

        fun matchesRuntime(candidate: ManagedRuntime): Boolean = original === candidate

        fun entered(candidate: ManagedRuntime): Boolean {
            check(candidate === original && candidate.operations.isCanonicalManager(canonicalPermit, this))
            return synchronized(gate) {
                if (entered || neverStarted) {
                    false
                } else if (state == State.STARTING && candidate.operations.reserveCanonicalDispatch(canonicalPermit, this)) {
                    entered = true
                    true
                } else {
                    neverStarted = true
                    false
                }
            }
        }

        fun finished(
            candidate: ManagedRuntime,
            failure: Throwable?,
        ) {
            check(candidate === original && candidate.operations.isCanonicalManager(canonicalPermit, this))
            synchronized(gate) {
                check(entered && !finished)
                finished = true
                if (failure != null) {
                    installFailure = failure
                    selectPrimary(failure)
                }
            }
        }

        fun discarded(
            candidate: ManagedRuntime,
            task: Runnable,
        ) {
            check(candidate === original && candidate.operations.matchesDiscardedCanonical(canonicalPermit, this, task))
            synchronized(gate) {
                check(!entered)
                neverStarted = true
            }
        }

        fun returned(candidate: ManagedRuntime) {
            check(candidate === original && candidate.operations.isCanonicalManager(canonicalPermit, this))
            synchronized(gate) { returned = true }
            taskReturned.countDown()
        }

        // Called under gate: only identity/type checks and preallocated scalar stores.
        private fun selectPrimary(failure: Throwable) {
            val prior = primary
            if (prior == null || (failure is Error && prior !is Error)) primary = failure
        }

        private fun retainCaller(failure: Throwable) {
            if (callerFailure == null) callerFailure = failure
            selectPrimary(failure)
        }

        private fun markDelivered(failure: Error) {
            if (installFailure === failure) installDelivered = true
            if (callerFailure === failure) callerDelivered = true
            if (cleanupFailure === failure) cleanupDelivered = true
            if (metadataFailure === failure) metadataDelivered = true
        }

        private fun nextError(): Error? {
            val a = installFailure
            val b = callerFailure
            val c = cleanupFailure
            val d = metadataFailure
            return when {
                a is Error && !installDelivered -> a
                b is Error && !callerDelivered -> b
                c is Error && !cleanupDelivered -> c
                d is Error && !metadataDelivered -> d
                else -> null
            }
        }

        // Reconciliation records the exact failure before making its outcome Unknown.
        @Suppress("TooGenericExceptionCaught")
        private fun mergeHistory() {
            val selected = primary ?: return
            try {
                var merged = selected
                installFailure?.let { merged = CleanupFailures.merge(merged, it) }
                callerFailure?.let { merged = CleanupFailures.merge(merged, it) }
                cleanupFailure?.let { merged = CleanupFailures.merge(merged, it) }
                synchronized(gate) { if (primary === selected) primary = merged }
            } catch (failure: Throwable) {
                synchronized(gate) {
                    if (metadataFailure == null) metadataFailure = failure
                    selectPrimary(failure)
                    metadataUnknown = true
                    state = State.UNKNOWN
                }
            }
        }

        // Startup records the original failure before propagating it, including fatal identity.
        @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
        fun start(
            preparationTimeout: Duration,
            closeTimeout: Duration,
        ): ManagedRuntime {
            requirePlatformThread()
            requirePositive(preparationTimeout)
            requirePositive(closeTimeout)
            requireUnrestrictedBootstrap()
            synchronized(gate) {
                check(state == State.NEW) { "Runtime acquisition is single use" }
                state = State.STARTING
                startActor = Thread.currentThread()
                closeBudget = closeTimeout
                setupInFlight = true
            }
            val runtime: ManagedRuntime
            try {
                runtime = ManagedRuntime(this, canonicalPermit, preparationTimeout, closeTimeout)
                original = runtime
                runtime.operations.claimCanonicalOwner(canonicalPermit, this)
                val maySubmit = synchronized(gate) { state == State.STARTING }
                if (maySubmit) {
                    runtime.operations.canonicalSubmit(canonicalPermit, this)
                } else {
                    synchronized(gate) { neverStarted = true }
                }
            } catch (failure: Throwable) {
                synchronized(gate) {
                    retainCaller(failure)
                    setupInFlight = false
                    if (cleanupUnknown || original != null) {
                        installUnknown = true
                        state = State.UNKNOWN
                    } else {
                        state = State.RETIRING
                    }
                    if (failure is Error) markDelivered(failure)
                }
                CanonicalFailure.propagate(failure)
            }
            val skipped = synchronized(gate) {
                setupInFlight = false
                neverStarted
            }
            if (skipped) {
                val failure = IllegalStateException("Runtime was retired during bootstrap")
                synchronized(gate) { retainCaller(failure) }
                CanonicalFailure.propagate(failure)
            }
            val completed = try {
                taskReturned.await(preparationTimeout.toNanos(), TimeUnit.NANOSECONDS)
            } catch (failure: InterruptedException) {
                synchronized(gate) {
                    retainCaller(failure)
                    if (state == State.STARTING) state = State.RETIRING
                }
                runtime.operations.canonicalCancel(canonicalPermit, this)
                Thread.currentThread().interrupt()
                CanonicalFailure.propagate(failure)
            }
            if (!completed) {
                val failure = TimeoutException("Canonical runtime startup timed out")
                synchronized(gate) {
                    retainCaller(failure)
                    if (state == State.STARTING) state = State.RETIRING
                }
                runtime.operations.canonicalCancel(canonicalPermit, this)
                CanonicalFailure.propagate(failure)
            }
            val failure = publishStartOutcome(runtime, IllegalStateException("Runtime was retired during bootstrap"))
            if (failure != null) CanonicalFailure.propagate(failure)
            return runtime
        }

        private object CanonicalFailure {
            fun propagate(failure: Throwable): Nothing = throw failure
        }

        private fun publishStartOutcome(
            runtime: ManagedRuntime,
            retiredFailure: Throwable,
        ): Throwable? =
            synchronized(gate) {
            val problem = installFailure
            if (problem != null) {
                state = State.UNKNOWN
                if (problem is Error) markDelivered(problem)
                problem
            } else if (!finished || state != State.STARTING || !runtime.operations.canonicalActivate(canonicalPermit, this)) {
                state = State.RETIRING
                retiredFailure.also { retainCaller(it) }
            } else {
                state = State.ACTIVE
                null
            }
        }

        fun status(): ManagedRuntimeAcquisitionStatus = synchronized(gate) { snapshot() }

        private fun snapshot(): ManagedRuntimeAcquisitionStatus =
            when (state) {
            State.NEW -> ManagedRuntimeAcquisitionStatus.NotStarted
            State.STARTING -> ManagedRuntimeAcquisitionStatus.Starting
            State.ACTIVE -> ManagedRuntimeAcquisitionStatus.Active
            State.RETIRING -> ManagedRuntimeAcquisitionStatus.Pending(primary)
            State.COMPLETE -> ManagedRuntimeAcquisitionStatus.Complete(primary)
            State.UNKNOWN ->
                if (setupInFlight) {
                    ManagedRuntimeAcquisitionStatus.Pending(primary)
                } else {
                    ManagedRuntimeAcquisitionStatus.Unknown(checkNotNull(primary ?: installFailure))
                }
        }

        private fun recordCleanupFailure(failure: Throwable) =
            synchronized(gate) {
            if (cleanupFailure == null) cleanupFailure = failure
            selectPrimary(failure)
            cleanupUnknown = true
            state = State.UNKNOWN
        }

        private fun recordMetadataFailure(failure: Throwable) =
            synchronized(gate) {
            if (metadataFailure == null) metadataFailure = failure
            selectPrimary(failure)
            metadataUnknown = true
            state = State.UNKNOWN
        }

        private fun recordCleanupReceipt(report: DrainReport) {
            (report as? DrainReport.Incomplete)?.cleanupFailure?.let { recordCleanupFailure(it) }
        }

        private fun startupPhysicallyExited(terminated: Boolean): Boolean {
            return (finished || neverStarted || (returned && !entered)) && (neverStarted || returned || terminated)
        }

        private fun publishRetirement(
            report: DrainReport,
            terminated: Boolean,
        ) = synchronized(gate) {
            if (
                report == DrainReport.Complete &&
                startupPhysicallyExited(terminated) &&
                (helperManager == null || (!helperStarted && terminated))
            ) {
                physicalComplete = true
            }
            when {
                installFailure != null || installUnknown || cleanupUnknown || metadataUnknown -> state = State.UNKNOWN
                physicalComplete -> state = State.COMPLETE
                else -> state = State.RETIRING
            }
        }

        private fun claimRetirement(): Pair<ManagedRuntime?, ManagedRuntimeAcquisitionStatus?> =
            synchronized(gate) {
                if (state == State.NEW) state = State.COMPLETE
                if (state == State.COMPLETE || physicalComplete || cleanupUnknown || metadataUnknown) {
                    null to snapshot()
                } else {
                    if (state != State.UNKNOWN) state = State.RETIRING
                    if (setupInFlight || retirementInFlight) {
                        null to snapshot()
                    } else {
                        val claim = original to null
                        retirementInFlight = true
                        claim
                    }
                }
            }

        // Retirement records the original failure and publishes typed Unknown before returning.
        @Suppress("TooGenericExceptionCaught")
        fun retire(timeout: Duration): ManagedRuntimeAcquisitionStatus {
            requirePositive(timeout)
            val (runtime, immediate) = retireHistoryOnly()?.let { null to it } ?: claimRetirement()
            immediate?.let { return it }
            try {
                if (runtime == null) {
                    retireStartedHelper()
                    synchronized(gate) { if (!cleanupUnknown) state = State.COMPLETE }
                } else {
                    val report = try {
                        runtime.drain(timeout)
                    } catch (failure: Throwable) {
                        recordCleanupFailure(failure)
                        null
                    }
                    if (report == null) {
                        mergeHistory()
                        return synchronized(gate) { snapshot() }
                    }
                    recordCleanupReceipt(report)
                    val terminated = try {
                        runtime.operations.canonicalManagerTerminated(canonicalPermit, this)
                    } catch (failure: Throwable) {
                        recordMetadataFailure(failure)
                        null
                    }
                    if (terminated != null) {
                        mergeHistory()
                        if (report == DrainReport.Complete && terminated) retireStartedHelper()
                        publishRetirement(report, terminated)
                    }
                }
            } finally {
                synchronized(gate) { retirementInFlight = false }
            }
            return synchronized(gate) { snapshot() }
        }

        private fun retireHistoryOnly(): ManagedRuntimeAcquisitionStatus? {
            val historyRuntime = synchronized(gate) {
                if (cleanupUnknown && !metadataUnknown && !installUnknown && helperStarted && !setupInFlight && !retirementInFlight) {
                    original?.also { retirementInFlight = true }
                } else {
                    null
                }
            }
            if (historyRuntime != null) {
                try {
                    val context = synchronized(gate) {
                        val current = startedHelperContext
                        if (originalAssociation?.helperContext === current && current != null && startupPhysicallyExited(true)) current else null
                    }
                    if (context != null && historyRuntime.operations.canonicalTransferredHelperRetired(canonicalPermit, this, context)) {
                        retireStartedHelper()
                    }
                } finally {
                    synchronized(gate) { retirementInFlight = false }
                }
                return synchronized(gate) { snapshot() }
            }
            return null
        }

        // Stop failures remain unknown and fatal failures retain their original identity.
        @Suppress("TooGenericExceptionCaught")
        private fun retireStartedHelper() {
            val manager = synchronized(gate) {
                if (!helperStarted) null else helperManager.also { helperStarted = false }
            } ?: return
            try {
                manager.stop()
                manager.requireConfinedHelperRetired()
            } catch (failure: InterruptedException) {
                recordCleanupFailure(failure)
                Thread.currentThread().interrupt()
            } catch (failure: Error) {
                recordCleanupFailure(failure)
                throw failure
            } catch (failure: Throwable) {
                recordCleanupFailure(failure)
            }
        }

        private fun closeFailure(result: ManagedRuntimeAcquisitionStatus): Throwable? =
            when (result) {
            is ManagedRuntimeAcquisitionStatus.Complete -> null
            is ManagedRuntimeAcquisitionStatus.Pending -> RuntimeAcquisitionPendingException(result)
            is ManagedRuntimeAcquisitionStatus.Unknown -> RuntimeAcquisitionUnknownException(result)
            ManagedRuntimeAcquisitionStatus.NotStarted, ManagedRuntimeAcquisitionStatus.Starting,
            ManagedRuntimeAcquisitionStatus.Active,
            -> IllegalStateException("Retirement did not produce a final observation")
        }

        fun close() {
            val budget = synchronized(gate) {
                if (state == State.NEW) {
                    state = State.COMPLETE
                    return
                }
                closeBudget
            }
            val result = if (budget == null) status() else retire(budget)
            val undelivered = synchronized(gate) {
                nextError()?.also { markDelivered(it) }
            }
            if (undelivered != null) throw undelivered
            val failure = closeFailure(result)
            if (failure != null) throw failure
        }
    }

    public companion object {
        private val identities = AtomicLong()

        internal fun newCanonicalAcquisition(): CanonicalAcquisition = CanonicalAcquisition()

        internal fun newCanonicalAcquisition(request: io.mazewall.ffi.internal.CheckedBootstrapRequest?): CanonicalAcquisition = CanonicalAcquisition(request)

        /** Open during trusted platform-thread bootstrap, before entering a narrower worker domain. */
        @JvmStatic
        @Suppress("TooGenericExceptionCaught") // startup resource boundary drains before preserving original native/fatal failure
        public fun open(
            runtime: RuntimeProfile,
            baseline: Policy<PolicyScope.ProcessWideSafe, Uncompiled>,
            preparationTimeout: Duration,
            closeTimeout: Duration,
        ): ManagedRuntime {
            requirePlatformThread()
            requirePositive(preparationTimeout)
            requirePositive(closeTimeout)
            requireUnrestrictedBootstrap()
            require(!baseline.definition.hasWorkerFilesystemIntent()) { "The host baseline must have process-safe scope" }
            require(baseline.allowMmapExec == runtime.allowsExecutableMappings) { "Process mapping floor does not match the declared runtime" }
            val owner = ManagedRuntime(runtime, preparationTimeout, closeTimeout)
            try {
                owner.installOrdinaryBaseline(baseline)
            } catch (startupFailure: Exception) {
                owner.drain(closeTimeout).let { report -> if (report is DrainReport.Incomplete) startupFailure.addSuppressed(IncompleteDrainException(report)) }
                throw startupFailure
            } catch (startupFatal: Error) {
                owner.drain(closeTimeout).let { report -> if (report is DrainReport.Incomplete) startupFatal.addSuppressed(IncompleteDrainException(report)) }
                throw startupFatal
            }
            return owner
        }

        private fun requirePositive(value: Duration) {
            require(!value.isNegative && !value.isZero && value.toNanos() > 0L) { "Timeout must be positive and finite" }
        }

        private fun requireUnrestrictedBootstrap() {
            check(RuntimeWorkerContext.current == null) { "Cannot bootstrap from an owned worker" }
            val local = ContainmentStateRegistry.threadState
            check(local.filterDepth == 0 && local.landlockPolicy == null && local.installationFailure == null) { "Cannot bootstrap from a narrower or failed thread scope" }
        }
    }

    private fun installOrdinaryBaseline(baseline: Policy<PolicyScope.ProcessWideSafe, Uncompiled>) = operations.installInitialBaseline(operationsPermit, baseline)

    private fun matchesOriginalState(
        candidate: RuntimeState,
        candidateOps: RuntimeOperations?,
        permit: Any?,
    ): Boolean = candidate === shared && permit === operationsPermit && (candidateOps == null || candidateOps === operations)

    internal fun admitScopingPreparation(): ScopingPreparation {
        requireUnrestrictedBootstrap()
        val preparation = ScopingPreparation(environment)
        synchronized(lock) {
            requireRuntimeOpen(phase == Phase.OPEN)
            if (manager.isShutdown || manager.isTerminated || helperDrainMode || preparations != 0 || domains.isNotEmpty() || scopingPreparation != null) {
                throw RejectedExecutionException("Scoping requires one exclusive preparation")
            }
            scopingPreparation = preparation
            if (checkedOperations != null) {
                shared.checkedPreparation = preparation
                shared.checkedProducer = Thread.currentThread()
            }
            helperDrainMode = true
            preparations++
        }
        return preparation
    }

    internal sealed interface CheckedOperations {
        fun helperAdmission(): io.mazewall.ffi.internal.TrustedHelperAdmission?

        fun helperContext(): io.mazewall.enforcer.internal.supervisor.SupervisorContext?

        fun reserve(
            preparation: ScopingPreparation,
            request: io.mazewall.ffi.internal.CheckedBootstrapRequest,
            capacity: io.mazewall.platform.native.WorkerCapacity,
            beforeClaim: () -> Unit,
            afterClaim: () -> Unit,
        ): io.mazewall.platform.native.LiveSetup<io.mazewall.platform.native.InstallationOwner>

        fun abandon(
            preparation: ScopingPreparation,
            cause: Throwable?,
            beforeNotification: () -> Unit,
        )
    }

    private class CheckedOperationsImpl(
        private val owner: ManagedRuntime,
        private val shared: RuntimeState,
        private val association: io.mazewall.ffi.internal.CheckedBootstrapAssociation,
        private val permit: Any,
    ) : CheckedOperations {
        init {
            check(owner.matchesOriginalState(shared, null, permit))
        }

    override fun helperAdmission(): io.mazewall.ffi.internal.TrustedHelperAdmission? = association.helperAdmission

    override fun helperContext(): io.mazewall.enforcer.internal.supervisor.SupervisorContext? = association.helperContext

    private fun matchesCheckedActor(preparation: ScopingPreparation): Boolean =
        shared.checkedPreparation === preparation &&
            shared.scopingPreparation === preparation &&
            preparation.state is ScopingPreparation.State.Constructing &&
        shared.checkedProducer === Thread.currentThread()

    private fun checkedAwaiting(): Boolean = !shared.checkedUnknown && shared.checkedRawOwner == null && shared.checkedAttachPhase === CheckedAttachPhase.Awaiting

    private fun mayStartChecked(preparation: ScopingPreparation): Boolean = shared.phase == Phase.OPEN && matchesCheckedActor(preparation) && !shared.checkedClaimed && !shared.checkedUnknown

    private fun stillOwnsChecked(preparation: ScopingPreparation): Boolean =
            matchesCheckedActor(preparation) &&
            shared.preparations > 0 &&
            shared.checkedClaimed &&
        checkedAwaiting()

    override fun reserve(
        preparation: ScopingPreparation,
        request: io.mazewall.ffi.internal.CheckedBootstrapRequest,
        capacity: io.mazewall.platform.native.WorkerCapacity,
        beforeClaim: () -> Unit,
        afterClaim: () -> Unit,
    ): io.mazewall.platform.native.LiveSetup<io.mazewall.platform.native.InstallationOwner> {
        if (!synchronized(shared.lock) { mayStartChecked(preparation) }) return checkedShutdownRefusal()
        return when (val checked = association.revalidate(request, preparation.environment.native)) {
            is io.mazewall.platform.native.LiveSetup.Refused -> checked
            is io.mazewall.platform.native.LiveSetup.Ready -> {
                beforeClaim()
                val claimed = synchronized(shared.lock) {
                    if (mayStartChecked(preparation) && checkedAwaiting()) {
                        shared.checkedClaimed = true
                        true
                    } else {
                        false
                    }
                }
                if (claimed) {
                    afterClaim()
                    if (synchronized(shared.lock) { stillOwnsChecked(preparation) }) {
                        reserveCheckedOwner(preparation, checked.value, capacity)
                    } else {
                        checkedShutdownRefusal()
                    }
                } else {
                    checkedShutdownRefusal()
                }
            }
        }
    }

    private fun checkedShutdownRefusal(): io.mazewall.platform.native.LiveSetup.Refused =
        io.mazewall.platform.native.LiveSetup.Refused(
            io.mazewall.platform.native.LiveFailure(
                io.mazewall.platform.native.LiveFailureStatus.Stale,
                io.mazewall.platform.native.LiveFailureReason.Shutdown,
            ),
        )

    @Suppress("TooGenericExceptionCaught") // An ambiguous Reserve is retained and never replayed.
    private fun reserveCheckedOwner(
        preparation: ScopingPreparation,
        contexts: io.mazewall.platform.native.NativeStackContexts,
        capacity: io.mazewall.platform.native.WorkerCapacity,
    ): io.mazewall.platform.native.LiveSetup<io.mazewall.platform.native.InstallationOwner> {
        val state = shared
        val result = try {
            contexts.reserve(capacity)
        } catch (failure: InterruptedException) {
            synchronized(shared.lock) {
                if (state.checkedRawFailure == null) state.checkedRawFailure = failure
                state.checkedUnknown = true
            }
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Throwable) {
            synchronized(shared.lock) {
                if (state.checkedRawFailure == null) state.checkedRawFailure = failure
                state.checkedUnknown = true
            }
            throw failure
        }
        if (result is io.mazewall.platform.native.LiveSetup.Ready) {
            val owner = result.value
            state.checkedRawOwner = owner
            validatePublishedOwner(preparation, owner, contexts)
        }
        return result
    }

    @Suppress("TooGenericExceptionCaught") // Preserve the exact post-publication attachment failure.
    private fun validatePublishedOwner(preparation: ScopingPreparation, owner: io.mazewall.platform.native.InstallationOwner, contexts: io.mazewall.platform.native.NativeStackContexts) {
        synchronized(shared.lock) {
            shared.checkedAttachPhase = CheckedAttachPhase.RawRetained
            try {
                check(shared.checkedPreparation === preparation && shared.scopingPreparation === preparation && shared.checkedProducer === Thread.currentThread())
                check(shared.checkedClaimed && !shared.checkedUnknown && owner.capability === contexts)
            } catch (failure: Throwable) {
                if (shared.checkedAttachmentFailure == null) shared.checkedAttachmentFailure = failure
                shared.checkedUnknown = true
                throw failure
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Preserve the original failure and keep uncertain construction counted.
    override fun abandon(
        preparation: ScopingPreparation,
        cause: Throwable?,
        beforeNotification: () -> Unit,
    ) {
        synchronized(shared.lock) {
            check(shared.checkedPreparation === preparation && shared.scopingPreparation === preparation && shared.checkedProducer === Thread.currentThread())
            if (shared.checkedRawFailure == null) shared.checkedRawFailure = cause
            if (shared.checkedRawOwner != null || shared.checkedAttachPhase === CheckedAttachPhase.RawRetained) {
                shared.checkedUnknown = true
                return
            }
            if (shared.checkedUnknown) return
            if (preparation.state is ScopingPreparation.State.PreparedSettled && preparation.constructionFinished.isDone) return
            shared.checkedUnknown = true
            try {
                val effects = transitionScoping(shared.scopingPreparation, preparation, ScopingPreparation.Event.ConstructionFailed(cause))
                check(effects.size == 1 && effects[0] === ScopingPreparation.Effect.Notify)
                shared.preparations--
            } catch (failure: Throwable) {
                if (shared.checkedAttachmentFailure == null) shared.checkedAttachmentFailure = failure
                throw failure
            }
        }
        try {
            beforeNotification()
            check(preparation.constructionFinished.complete(Unit))
            synchronized(shared.lock) { shared.checkedUnknown = false }
        } catch (failure: Throwable) {
            synchronized(shared.lock) { if (shared.checkedNotificationFailure == null) shared.checkedNotificationFailure = failure }
            throw failure
        }
    }
    }

    @Suppress("TooGenericExceptionCaught") // Retain uncertain attachment failures before rethrowing.
    internal fun attachScopingOwner(
        preparation: ScopingPreparation,
        owner: io.mazewall.platform.native.InstallationOwner,
    ) {
        synchronized(lock) {
            if (shared.checkedPreparation !== preparation) {
                transitionScoping(scopingPreparation, preparation, ScopingPreparation.Event.Reserved(owner))
            } else {
                try {
                    check(shared.checkedAttachPhase === CheckedAttachPhase.RawRetained)
                    check(scopingPreparation === preparation && shared.checkedClaimed && shared.checkedProducer === Thread.currentThread())
                    check(!shared.checkedUnknown && shared.checkedRawOwner === owner)
                    transitionScoping(scopingPreparation, preparation, ScopingPreparation.Event.Reserved(owner))
                    shared.checkedAttachPhase = CheckedAttachPhase.ReducerAttached
                    shared.checkedRawOwner = null
                } catch (failure: Throwable) {
                    if (shared.checkedAttachmentFailure == null) shared.checkedAttachmentFailure = failure
                    shared.checkedUnknown = true
                    throw failure
                }
            }
        }
    }

    internal val liveWorkerAdmission: LiveWorkerAdmission = BoundLiveWorkerAdmission(shared)

    internal interface LiveWorkerAdmission {
        fun reserve(
            preparation: ScopingPreparation,
            bootstrap: io.mazewall.ffi.internal.OwnedStackScopingBootstrap,
            domain: OwnedDomain,
            slot: io.mazewall.enforcer.internal.execution.LiveWorkerSlot,
        )
    }

    private class BoundLiveWorkerAdmission(
        private val state: RuntimeState,
    ) : LiveWorkerAdmission {
        override fun reserve(
            preparation: ScopingPreparation,
            bootstrap: io.mazewall.ffi.internal.OwnedStackScopingBootstrap,
            domain: OwnedDomain,
            slot: io.mazewall.enforcer.internal.execution.LiveWorkerSlot,
        ) {
            synchronized(state.lock) {
                requireLiveWorkerAdmission(preparation, bootstrap, domain)
                // Live startup cannot reach READY before the unconditional B6 refusal. Audit lock ordering if that guard moves.
                domain.liveWorkerAdmission.reserve(slot)
            }
        }

        private fun requireLiveWorkerAdmission(
            preparation: ScopingPreparation,
            bootstrap: io.mazewall.ffi.internal.OwnedStackScopingBootstrap,
            domain: OwnedDomain,
        ) {
            check(state.phase == Phase.OPEN && state.scopingPreparation === preparation) { "Live worker registration was not admitted" }
            val current = preparation.state
            check(current is ScopingPreparation.State.DomainOwned && current.domain === domain) { "Live worker registration was not admitted" }
            check((state.domains as Map<*, *>)[domain.registrationKey] === domain) { "Live worker registration was not admitted" }
            val live = domain.scoping
            check(live is io.mazewall.enforcer.internal.execution.FrozenStackScoping.Live && live.bootstrap === bootstrap) {
                "Live worker registration was not admitted"
            }
        }
    }

    internal fun attachScopingEnrollment(
        preparation: ScopingPreparation,
        enrollment: io.mazewall.platform.native.Enrollment,
    ) {
        synchronized(lock) { transitionScoping(scopingPreparation, preparation, ScopingPreparation.Event.Enrolled(enrollment)) }
    }

    internal fun attachSealedScopingOwner(
        preparation: ScopingPreparation,
        owner: io.mazewall.platform.native.InstallationOwner,
    ) {
        synchronized(lock) { transitionScoping(scopingPreparation, preparation, ScopingPreparation.Event.Sealed(owner)) }
    }

    internal fun attachScopingCapture(
        preparation: ScopingPreparation,
        owner: io.mazewall.platform.native.InstallationOwner,
        frozen: io.mazewall.enforcer.internal.execution.FrozenStackScoping.Live,
        result: io.mazewall.platform.native.LiveSetup.Ready<PreparedStackScoping>,
    ) {
        synchronized(lock) { transitionScoping(scopingPreparation, preparation, ScopingPreparation.Event.Sealed(owner, frozen, result)) }
    }

    internal fun publishScopingPreparation(
        preparation: ScopingPreparation,
        ready: ScopingPreparation.State.Ready,
    ): Boolean {
        val event = ScopingPreparation.Event.Publish(ready)
        synchronized(lock) {
            if (phase != Phase.OPEN) return false
            transitionScoping(scopingPreparation, preparation, event)
            preparations--
            return true
        }
    }

    internal fun abandonScopingPreparation(
        preparation: ScopingPreparation,
        cause: Throwable? = null,
    ) {
        checkedOperations?.let {
            it.abandon(preparation, cause) { }
            return
        }
        val effects = synchronized(lock) {
            val emitted = transitionScoping(scopingPreparation, preparation, ScopingPreparation.Event.ConstructionFailed(cause))
            preparations--
            emitted
        }
        check(effects.size == 1 && effects[0] === ScopingPreparation.Effect.Notify)
        preparation.constructionFinished.complete(Unit)
    }

    internal fun closeScopingPreparation(preparation: ScopingPreparation) = operations.closeScopingPreparation(operationsPermit, preparation)

    /**
     * Claims matching prepared scoping once, including when the current Live guard refuses preparation.
     * A mismatched runtime, policy, limits, or native engine leaves the preparation ready for its owner.
     * Live dispatch is currently unsupported and is refused before helper or worker effects begin.
     * Closing the wrapper after transfer does not revoke the domain; use [drain] to observe physical
     * retirement and retained failures.
     */
    public fun prepare(
        policy: Policy<*, Uncompiled>,
        limits: DomainLimits,
        scoping: PreparedStackScoping,
    ): InProcessDomain {
        operations.requireAdmission(operationsPermit)
        return prepareScoping(policy, limits, scoping, defaultScopingEffects)
    }

    @Suppress("TooGenericExceptionCaught") // A failed wait must retain its original cause without cancelling accepted work.
    private fun prepareScoping(
        policy: Policy<*, Uncompiled>,
        limits: DomainLimits,
        scoping: PreparedStackScoping,
        effects: ScopingTestEffects,
    ): InProcessDomain {
        requireUnrestrictedBootstrap()
        val preparation: ScopingPreparation
        val generation: Long
        val ready = synchronized(lock) {
            requireRuntimeOpen(phase == Phase.OPEN)
            val current = scopingPreparation
            val observed = current?.state as? ScopingPreparation.State.Ready
            require(observed != null && observed.result.value === scoping) { "Prepared scoping does not belong to this runtime" }
            require(limits == observed.frozen.bootstrap.limits) { "Prepared scoping limits do not match" }
            preparation = checkNotNull(current)
            generation = Math.addExact(nextGeneration, 1L)
            observed
        }
        val definition = policy.definition.frozenCopy()
        require(definition == ready.frozen.bootstrap.artifact.document.policy.definition) { "Prepared scoping policy does not match" }
        check(
            !ready.frozen.bootstrap.resources
            .isDirectResult(),
        ) { "Direct result Live scoping is not available" }
        require(ready.frozen.bootstrap.matchesClaim(this, definition, limits, preparation.environment.native)) { "Prepared scoping provenance does not match" }
        if (ready.frozen.bootstrap.helperAdmission == null) {
            synchronized(lock) {
                requireRuntimeOpen(phase == Phase.OPEN)
                check(scopingPreparation === preparation && preparation.state === ready) { "Prepared scoping is no longer ready" }
                check(nextGeneration + 1L == generation) { "Prepared scoping generation changed" }
                check(!manager.isShutdown && !manager.isTerminated) { "Scoping manager is not available" }
                scopingFailureBoundary.refuseMissingHelperSupport()
            }
        }
        val shell = io.mazewall.landlock.PreparedLandlock
            .allocateRetained(ready.frozen.bootstrap.resources.resources, preparation.environment)
        val carrier = ScopingPreparation.ClaimProgress(ready, shell, ready.frozen)
        val event = ScopingPreparation.Event.ClaimRequested(this, definition, limits, preparation.environment.native, true, generation, shell, ready.frozen, carrier)
        val task = FutureTask { scopingFailureBoundary.executeScopingClaim(preparation, carrier, definition, limits, effects) }
        synchronized(lock) {
            requireRuntimeOpen(phase == Phase.OPEN)
            check(scopingPreparation === preparation && preparation.state === ready) { "Prepared scoping is no longer ready" }
            check(nextGeneration + 1L == generation) { "Prepared scoping generation changed" }
            check(!manager.isShutdown && !manager.isTerminated) { "Scoping manager is not available" }
            nextGeneration = generation
            carrier.bindGeneration(generation)
            val decisionEffects = transitionScoping(scopingPreparation, preparation, event)
            check(decisionEffects.size == 1 && decisionEffects[0] === ScopingPreparation.Effect.SubmitClaim)
            preparations++
        }
        try {
            manager.execute(task)
        } catch (rejected: RejectedExecutionException) {
            synchronized(lock) {
                val decisionEffects = transitionScoping(scopingPreparation, preparation, ScopingPreparation.Event.ClaimFailed(rejected))
                check(decisionEffects.size == 1 && decisionEffects[0] === ScopingPreparation.Effect.Notify)
                preparations--
            }
            preparation.claimFinished.complete(Unit)
            throw rejected
        }
        val cause = try {
            effects.beforeWait()
            return task.get(preparationTimeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            interrupted
        } catch (failed: ExecutionException) {
            failed.cause ?: failed
        } catch (failure: Throwable) {
            failure
        }
        throw scopingFailureBoundary.recordScopingWaitFailure(preparation, cause, effects.waitFailureObserver)
    }

    private class ScopingFailureBoundary(
        private val owner: ManagedRuntime,
        private val shared: RuntimeState,
        private val opsPermit: Any,
    ) {
        init {
            check(owner.matchesOriginalState(shared, null, opsPermit))
        }

        @Suppress("TooGenericExceptionCaught") // Every claim failure retains acquired originals before quiescence and rethrows.
        fun executeScopingClaim(
            preparation: ScopingPreparation,
            carrier: ScopingPreparation.ClaimProgress,
            definition: PolicyDefinition<*>,
            limits: DomainLimits,
            effects: ScopingTestEffects,
        ): InProcessDomain {
            var committed: OwnedDomain? = null
            var claimed = false
            var claimRecorded = false
            var domainPreparationStarted = false
            try {
                val bootstrap = carrier.scoping.bootstrap
                val artifact = bootstrap.artifact
                val worker = artifact.document.policy as CanonicalPolicyArtifact.Worker
                synchronized(shared.lock) { checkClaimMayProceed(preparation, carrier, shared.phase == Phase.OPEN) }
                bootstrap.resources.claim(artifact.binding, worker, preparation.environment.native, preparation.environment.platform.capabilities.matrix.landlockAbiVersion)
                claimed = true
                synchronized(shared.lock) {
                    transitionScoping(shared.scopingPreparation, preparation, ScopingPreparation.Event.ResourcesClaimed)
                    claimRecorded = true
                }
                captureLiveHelper(preparation, carrier, bootstrap, worker)
                val helperCapture = checkNotNull(carrier.helperCapture)
                carrier.scoping.bootstrap.planScoping(definition)
                synchronized(shared.lock) { checkClaimMayProceed(preparation, carrier, shared.phase == Phase.OPEN) }
                val landlock = io.mazewall.landlock.PreparedLandlock
                    .prepareRetainedWithPostStore(definition, preparation.environment, carrier.landlock, effects.afterTypedStore)
                synchronized(shared.lock) { transitionScoping(shared.scopingPreparation, preparation, ScopingPreparation.Event.LandlockPrepared(carrier.landlock)) }
                synchronized(shared.lock) { checkClaimMayProceed(preparation, carrier, shared.phase == Phase.OPEN) }
                val domain =
                    OwnedDomain(
                        carrier.generation,
                        limits,
                        definition,
                        preparation.environment,
                        landlock,
                        bootstrap.resources,
                        "mazewall-domain-${owner.id}-${carrier.generation}-",
                        carrier.scoping,
                        liveCarrier = carrier,
                    )
                helperCapture.prepareTransfer(carrier, domain)
                val deadline = Deadline(owner.preparationTimeout)
                val commit = ScopingPreparation.Event.DomainCommit(domain, true)
                synchronized(shared.lock) {
                    requireRuntimeOpen(shared.phase == Phase.OPEN)
                    val current = preparation.state as? ScopingPreparation.State.Claiming
                    check(current != null && current.acquisition === carrier && current.request is ScopingPreparation.PrepareRequest.Awaiting) { "Scoping claim cannot commit" }
                    check(shared.domains.values.none { it.failedRetirementPending() || it.cleanupFailure.get() != null }) { "Prior failed generation still has live execution or cleanup" }
                    shared.domains[carrier.generation] = domain
                    try {
                        val decisionEffects = transitionScoping(shared.scopingPreparation, preparation, commit)
                        check(decisionEffects.size == 1 && decisionEffects[0] === ScopingPreparation.Effect.Notify)
                    } finally {
                        val actual = preparation.state as? ScopingPreparation.State.DomainOwned
                        if (actual != null && actual.domain === domain) {
                            committed = domain
                            shared.preparations--
                        } else {
                            shared.domains.remove(carrier.generation)
                        }
                    }
                }
                domainPreparationStarted = true
                domain.prepare(deadline, effects.afterDomainCommit)
                return InProcessDomain(owner, domain, artifact.binding)
            } catch (failure: Throwable) {
                if (claimed && !claimRecorded) {
                    try {
                        synchronized(shared.lock) { transitionScoping(shared.scopingPreparation, preparation, ScopingPreparation.Event.ResourcesClaimed) }
                    } catch (recordFailure: Throwable) {
                        throw CleanupFailures.merge(failure, recordFailure)
                    }
                }
                var retainedFailure = failure
                val committedDomain = committed
                if (committedDomain != null && !domainPreparationStarted) {
                    try {
                        committedDomain.liveHelperRetirement.abortBeforePrepare()
                    } catch (cleanupFailure: Throwable) {
                        retainedFailure = CleanupFailures.merge(retainedFailure, cleanupFailure)
                    }
                }
                throw finishFailedScopingClaim(preparation, committed, retainedFailure)
            } finally {
                preparation.claimFinished.complete(Unit)
            }
        }

        fun refuseMissingHelperSupport(): Nothing = throw UnsupportedOperationException("Reviewed helper support is unavailable")

        private fun captureLiveHelper(
            preparation: ScopingPreparation,
            carrier: ScopingPreparation.ClaimProgress,
            bootstrap: io.mazewall.ffi.internal.OwnedStackScopingBootstrap,
            worker: CanonicalPolicyArtifact.Worker,
        ) {
                val helperAdmission = checkNotNull(bootstrap.helperAdmission) { "Reviewed helper support is unavailable" }
                val nativeProcess = bootstrap.sealed.process
                val baseline = helperAdmission.baseline
                check(nativeProcess.pid == baseline.identity.pid && nativeProcess.startTicks.value == baseline.identity.startTimeTicks.toULong()) {
                    "Live helper process differs from checked bootstrap"
                }
                val parent = helperAdmission.observations.observeAncestry(
                    nativeProcess.pid,
                    preparation.environment.native.process
                    .gettid(),
                        HelperSecurityObservation.Lineage.Tier1,
                )
                check(parent.identity == baseline.identity) { "Live helper process incarnation changed" }
                parent.requireLineage(baseline, HelperSecurityObservation.Lineage.Tier1)
                synchronized(shared.lock) { checkClaimMayProceed(preparation, carrier, shared.phase == Phase.OPEN) }
                val helperCapture = io.mazewall.enforcer.internal.execution
                    .HelperSupportCapture(carrier, bootstrap.resources.native)
                carrier.helperCapture = helperCapture
                val helperBinding = io.mazewall.enforcer.internal.execution.HelperBinding(
                    nativeProcess.pid,
                    baseline.identity.startTimeTicks,
                    carrier.generation,
                    bootstrap.resources.resources.generation,
                    java.util.HexFormat
                        .of()
                        .formatHex(
                            java.security.MessageDigest
                            .getInstance("SHA-256")
                            .digest(bootstrap.canonicalJson.toByteArray(Charsets.UTF_8)),
                        ),
                )
                carrier.helperPlan = io.mazewall.enforcer.internal.execution.PreparedHelperReadPlan.captureOwned(
                    worker,
                    bootstrap.resources.resources,
                    helperBinding,
                    helperAdmission.support.recipe.entries,
                    io.mazewall.enforcer.internal.execution.HelperSupportLifetime.NoWriters,
                    bootstrap.resources.native,
                    parent.ancestry,
                    helperCapture,
                )
        }

        @Suppress("TooGenericExceptionCaught") // Preserve original claim failure while retaining cleanup failures.
        private fun finishFailedScopingClaim(preparation: ScopingPreparation, domain: OwnedDomain?, failure: Throwable): Throwable {
            try {
                if (domain != null) {
                    synchronized(shared.lock) {
                        val state = preparation.state
                        check(
                            (state is ScopingPreparation.State.DomainOwned && state.domain === domain) ||
                            (state is ScopingPreparation.State.TransferredSettled && state.domain === domain),
                        ) { "Scoping domain provenance changed" }
                    }
                    return recordScopingWaitFailure(preparation, failure, noWaitFailureObserver)
                } else {
                    val event = ScopingPreparation.Event.ClaimFailed(failure)
                    synchronized(shared.lock) {
                        val effects = transitionScoping(shared.scopingPreparation, preparation, event)
                        check(effects.size == 1 && effects[0] === ScopingPreparation.Effect.Notify)
                        shared.preparations--
                    }
                    owner.operations.retireFailedScopingClaim(opsPermit, preparation)
                }
            } catch (problem: Throwable) {
                return CleanupFailures.merge(failure, problem)
            }
            return failure
        }

        @Suppress("TooGenericExceptionCaught") // A reserved exact-domain failure must preserve any handler failure.
        fun recordScopingWaitFailure(
            preparation: ScopingPreparation,
            failure: Throwable,
            observer: () -> Unit,
        ): Throwable {
            var result = invokeWaitFailureObserver(observer, failure)
            val (origin, effects) = synchronized(shared.lock) {
                val captured = ScopingPreparation.observationOrigin(preparation.state)
                val emitted = transitionScoping(shared.scopingPreparation, preparation, ScopingPreparation.Event.PrepareWaitFailed(ScopingPreparation.WaitObservation.Initial(failure)))
                if (result !== failure) {
                    transitionScoping(
                        shared.scopingPreparation,
                        preparation,
                        ScopingPreparation.Event.PrepareWaitFailed(ScopingPreparation.WaitObservation.HistoryObservation(failure, result, captured)),
                    )
                }
                captured to emitted
            }
            effects.filterIsInstance<ScopingPreparation.Effect.FailDomain>().forEach { effect ->
                result = invokeWaitFailureObserver(observer, result)
                try {
                    effect.domain.fail(effect.cause)
                } catch (problem: Throwable) {
                    result = CleanupFailures.merge(result, problem)
                }
            }
            if (result !== failure) {
                synchronized(shared.lock) {
                    transitionScoping(
                        shared.scopingPreparation,
                        preparation,
                        ScopingPreparation.Event.PrepareWaitFailed(ScopingPreparation.WaitObservation.HistoryObservation(failure, result, origin)),
                    )
                }
            }
            return result
        }

        @Suppress("TooGenericExceptionCaught") // Observer failure cannot replace or prevent the original failure transaction.
        private fun invokeWaitFailureObserver(observer: () -> Unit, prior: Throwable): Throwable = try {
            observer()
            prior
        } catch (failure: Throwable) {
            CleanupFailures.merge(prior, failure)
        }
    }

    /** Prepare configured review metadata. Actual implementation identity requires the resource-owner overload. */
    public fun prepare(
        artifact: ReviewedOperationArtifact,
        limits: DomainLimits,
    ): InProcessDomain {
        operations.requireAdmission(operationsPermit)
        val worker = artifact.document.policy as? CanonicalPolicyArtifact.Worker
            ?: throw IllegalArgumentException("In-process domains require worker policy artifacts")
        require(worker.runtime == runtime) { "Reviewed operation runtime does not match owner" }
        val domain = prepare(worker.policy, limits)
        return InProcessDomain(this, domain.resource, artifact.binding)
    }

    /** Prepare the exact externally reviewed implementation and retained resource acquisitions. */
    public fun prepare(
        artifact: ReviewedOperationArtifact,
        limits: DomainLimits,
        resources: PreparedOperationResources,
    ): InProcessDomain {
        operations.requireAdmission(operationsPermit)
        val worker = artifact.document.policy as? CanonicalPolicyArtifact.Worker
            ?: throw IllegalArgumentException("In-process domains require worker policy artifacts")
        require(worker.runtime == runtime) { "Reviewed operation runtime does not match owner" }
        val domain = prepareDefinition(worker.policy.definition.frozenCopy(), limits, artifact, resources)
        return InProcessDomain(this, domain.resource, artifact.binding)
    }

    internal fun prepareConfinedRead(
        artifact: ReviewedOperationArtifact,
        limits: DomainLimits,
        resources: PreparedOperationResources,
        context: SupervisorContext,
        support: List<ReviewedHelperSupport>,
        lifetime: HelperSupportLifetime,
        observations: HelperSecurityObservation = HelperSecurityObservation(),
    ): InProcessDomain {
        operations.requireAdmission(operationsPermit)
        check(!resources.isDirectResult()) { "Direct result confined preparation is not available" }
        val worker = artifact.document.policy as? CanonicalPolicyArtifact.Worker
            ?: throw IllegalArgumentException("Confined domains require a worker artifact")
        require(worker.runtime == runtime && lifetime == HelperSupportLifetime.NoWriters)
        requireNotNull(context.confinedBootstrap)
        requireNotNull(context.confinedHelper)
        val configuration = ConfinedHelperConfiguration(worker, context, java.util.List.copyOf(support), lifetime, observations)
        val domain = prepareDefinition(worker.policy.definition.frozenCopy(), limits, artifact, resources, confined = configuration)
        return InProcessDomain(this, domain.resource, artifact.binding)
    }

    @Suppress("TooGenericExceptionCaught") // preparation boundary retires its generation and preserves original failure
    public fun prepare(
        policy: Policy<*, Uncompiled>,
        limits: DomainLimits,
    ): InProcessDomain {
        operations.requireAdmission(operationsPermit)
        return prepareDefinition(policy.definition.frozenCopy(), limits)
    }

    /**
     * Capture custom supervision handlers once on this trusted caller, before readiness.
     * Handler membership is immutable; callbacks remain trusted application collaborators.
     * Prepare the external supervisor explicitly before open installs the process baseline.
     */
    public fun prepare(
        policy: Policy<*, Uncompiled>,
        limits: DomainLimits,
        scopingPolicy: StacktraceScopingPolicy,
    ): InProcessDomain {
        operations.requireAdmission(operationsPermit)
        requireUnrestrictedBootstrap()
        val captured = io.mazewall.enforcer.internal.execution.FrozenStackScoping
            .Legacy(scopingPolicy)
        return prepareDefinition(policy.definition.frozenCopy(), limits, frozenScoping = captured)
    }

    @Suppress("TooGenericExceptionCaught") // preparation boundary retains manager-owned resources until physical retirement
    private fun prepareDefinition(
        definition: PolicyDefinition<*>,
        limits: DomainLimits,
        artifact: ReviewedOperationArtifact? = null,
        resources: PreparedOperationResources? = null,
        frozenScoping: io.mazewall.enforcer.internal.execution.FrozenStackScoping.Legacy = io.mazewall.enforcer.internal.execution.FrozenStackScoping
            .Legacy(DefaultStacktraceScopingPolicy),
        confined: ConfinedHelperConfiguration? = null,
    ): InProcessDomain {
        requireUnrestrictedBootstrap()
        val resourceRef = AtomicReference<OwnedDomain?>()
        val request = LegacyAcquisitionRequest(definition, limits, artifact, resources, frozenScoping, confined, resourceRef, "mazewall-domain-$id-")
        val acquisition = LegacyAcquisition(this, legacyControl, legacyOwnershipKey, lock, manager, environment, legacyAcquisitionEffects, request)
        val prepared = synchronized(lock) {
            requireRuntimeOpen(phase == Phase.OPEN)
            if (confined == null) {
                check(!helperDrainMode) { "Confined runtime cannot mix legacy domains" }
            } else {
                check(!helperDrainMode && preparations == 0 && domains.isEmpty()) { "Confined runtime requires one exclusive preparation" }
                helperDrainMode = true
            }
            legacyControl.beginJob()
            preparations++
            var submitted = false
            try {
                val action = Callable { prepareOnManager(acquisition) }
                val task = LegacyPreparationTask(shared, legacyControl, lock, managerThread, action)
                manager.execute(task)
                submitted = true
                task
            } finally {
                if (!submitted) {
                    preparations--
                    legacyControl.jobFinished()
                }
            }
        }
        return try {
            awaitLegacyPreparation(prepared, preparationTimeout, acquisition)
        } catch (preparationFailure: Exception) {
            resourceRef.get()?.fail(preparationFailure)
            throw preparationFailure
        } catch (preparationFatal: Error) {
            resourceRef.get()?.fail(preparationFatal)
            throw preparationFatal
        }
    }

    private class LegacyPreparationTask(
        private val shared: RuntimeState,
        private val jobControl: RuntimeLegacyControl,
        private val gate: Any,
        private val physicalManager: AtomicReference<Thread?>,
        action: Callable<InProcessDomain>,
    ) : FutureTask<InProcessDomain>(action) {
        private val entered = java.util.concurrent.atomic
            .AtomicBoolean()

        init {
            check(jobControl.matchesState(shared) && jobControl.matchesTaskContext(gate, physicalManager))
        }

        override fun run() {
            check(jobControl.matchesTaskContext(gate, physicalManager) && Thread.currentThread() === physicalManager.get())
            if (!entered.compareAndSet(false, true)) return
            try {
                super.run()
            } finally {
                // Canceled queued tasks skip their Callable but still exit this manager boundary once.
                synchronized(gate) {
                    shared.preparations--
                    jobControl.jobFinished()
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // manager resource boundary retires preparation on every original failure
    private fun prepareOnManager(
        acquisition: LegacyAcquisition,
    ): InProcessDomain {
        synchronized(lock) {
            requireRuntimeOpen(phase == Phase.OPEN)
            legacyControl.attach(acquisition)
        }
        val resource = acquisition.acquireAndCommit()
        return try {
            resource.prepare(Deadline(preparationTimeout))
            synchronized(lock) { requireRuntimeOpen(phase == Phase.OPEN) }
            InProcessDomain(this, resource)
        } catch (preparationFailure: Throwable) {
            resource.fail(preparationFailure)
            throw preparationFailure
        }
    }

    /**
     * Capture declared metadata now, outside lifecycle locks and before submission/admission.
     * Feed the returned Callable to submit or Mazewall.callContained; existing calls transfer nothing.
     * Application bridges are explicit trusted collaborators, not permission authority.
     */
    public fun <T> captureInvocation(
        domain: InProcessDomain,
        contexts: InvocationContextBridge,
        action: Callable<T>,
    ): Callable<T> {
        operations.requireAdmission(operationsPermit)
        require(domain.owner === this) { "Domain belongs to a different runtime" }
        val snapshot = contexts.capture()
        return Callable {
            check(RuntimeWorkerContext.current === domain.resource) { "Captured context requires its exact owned domain" }
            val invocation = checkNotNull(RuntimeWorkerContext.invocation) { "Context requires an active owned invocation" }
            invocation.withContext(snapshot, action)
        }
    }

    @Suppress("TooGenericExceptionCaught") // failed resource revalidation quarantines this generation and rethrows original failure
    public fun <T> submit(
        domain: InProcessDomain,
        action: Callable<T>,
    ): InvocationHandle<T> {
        val validation = synchronized(lock) {
        require(domain.owner === this && domains[domain.generation] === domain.resource) { "Domain belongs to a different runtime" }
        requireRuntimeOpen(phase == Phase.OPEN, domain.resource)
        if (RuntimeWorkerContext.current != null) {
            domain.resource.recordRejection()
            throw RejectedExecutionException("Nested dispatch requires an approved execution transition")
        }
        if (!domain.resource.needsResourceValidation) return domain.resource.submit(action)
        manager.submit(Callable { domain.resource.validateResources() })
        }
        try {
            awaitPreparation(validation, preparationTimeout)
        } catch (failure: Throwable) {
            domain.resource.fail(failure)
            throw failure
        }
        return synchronized(lock) {
            requireRuntimeOpen(phase == Phase.OPEN, domain.resource)
            domain.resource.submit(action)
        }
    }

    internal fun diagnostics(domain: InProcessDomain): ExecutionDiagnostics =
        synchronized(lock) {
        require(domain.owner === this && domains[domain.generation] === domain.resource) { "Domain belongs to a different runtime" }
        domain.resource.diagnostics(phase == Phase.OPEN)
    }

    internal fun <T> callContained(
        domain: InProcessDomain,
        action: Callable<T>,
    ): T {
        synchronized(lock) {
            require(domain.owner === this && domains[domain.generation] === domain.resource) { "Domain belongs to a different runtime" }
        }
        val current = RuntimeWorkerContext.current
        val local = ContainmentStateRegistry.threadState
        return when (ExecutionTransitionGuard.evaluate(current != null, current === domain.resource, RuntimeWorkerContext.invocation != null, local.filterDepth > 0 || local.landlockPolicy != null)) {
            ExecutionTransition.Dispatch -> awaitInvocation(submit(domain, action))
            ExecutionTransition.InlineCurrentWorker -> {
                if (!domain.resource.permitsInlineContinuation()) rejectTransition()
                callInline(action)
            }
            ExecutionTransition.Reject -> rejectTransition()
        }
    }

    @Suppress("TooGenericExceptionCaught") // nested body boundary uses canonical classification and retains observed enforcement failure
    private fun <T> callInline(action: Callable<T>): T = try {
        action.call()
    } catch (bodyFailure: Throwable) {
        val classified = containmentFailureForCaller(bodyFailure)
        if (classified is ContainmentViolationException) RuntimeWorkerContext.invocation?.recordObservedFailure(classified)
        throw classified
    }

    public fun drain(timeout: Duration): DrainReport {
        check(RuntimeWorkerContext.current == null) { "An owned worker cannot drain itself" }
        val deadline = Deadline(timeout)
        val helperMode = synchronized(lock) {
            if (phase == Phase.CLOSED) {
                if (shared.checkedUnknown) return DrainReport.Incomplete(0, 0, shared.checkedRawFailure)
                val history = transferredScopingHistory(scopingPreparation?.state)
                return if (history == null) DrainReport.Complete else DrainReport.Incomplete(0, 0, history)
            }
            phase = Phase.DRAINING
            legacyControl.beginDrain()
            helperDrainMode
        }
        return try {
            val outcome = if (helperMode) legacyDrainBoundary.drainConfined(deadline) else operations.drainLegacy(operationsPermit, deadline)
            if (outcome != DrainReport.Complete) {
                outcome
            } else {
                synchronized(lock) {
                if (shared.checkedUnknown) return@synchronized DrainReport.Incomplete(0, 0, shared.checkedRawFailure)
                val history = transferredScopingHistory(scopingPreparation?.state)
                if (history != null) {
                    DrainReport.Incomplete(0, 0, history)
                } else {
                    phase = Phase.CLOSED
                    DrainReport.Complete
                }
            }
            }
        } finally {
            synchronized(lock) { legacyControl.endDrain() }
        }
    }

    private class DrainBoundary(
        private val owner: ManagedRuntime,
        private val shared: RuntimeState,
        private val control: RuntimeLegacyControl,
        private val physicalManager: AtomicReference<Thread?>,
        private val opsPermit: Any,
    ) {
        init {
            check(owner.matchesOriginalState(shared, null, opsPermit) && control.matchesState(shared) && control.matchesTaskContext(shared.lock, physicalManager))
        }

        private fun awaitAcquisition(deadline: Deadline): Boolean = control.awaitCurrent(deadline)

        private fun scopingSnapshot(): DomainDrainSnapshot? =
            synchronized(shared.lock) {
            shared.scopingPreparation?.let { preparation ->
                if (shared.checkedUnknown) return@let DomainDrainSnapshot(false, 0, 1, shared.checkedRawFailure)
                when (val state = preparation.state) {
                    is ScopingPreparation.State.Constructing, is ScopingPreparation.State.Ready -> DomainDrainSnapshot(false, 0, 1, null)
                    is ScopingPreparation.State.Claiming -> DomainDrainSnapshot(false, 0, 1, (state.request as? ScopingPreparation.PrepareRequest.Failed)?.cause)
                    is ScopingPreparation.State.DomainOwned -> DomainDrainSnapshot(true, 0, 0, state.history)
                    is ScopingPreparation.State.TransferredSettled -> DomainDrainSnapshot(true, 0, 0, state.history)
                    is ScopingPreparation.State.Retiring -> DomainDrainSnapshot(false, 0, 1, state.retained.failure)
                    is ScopingPreparation.State.PreparedSettled -> DomainDrainSnapshot(true, 0, 0, state.failure)
                }
            }
        }

        fun drainConfined(deadline: Deadline): DrainReport {
        if (awaitScopingDrain(deadline) && awaitAcquisition(deadline)) owner.operations.awaitConfinedSweep(opsPermit, control, deadline)
        val resources = synchronized(shared.lock) { shared.domains.values.toList() }
        val managerLive = physicalManager.get()?.isAlive == true
        val snapshots = resources.map { it.drainSnapshot() }
        val failure = synchronized(shared.lock) {
            var merged: Throwable? = null
            (snapshots.mapNotNull { it.cleanupFailure } + listOfNotNull(shared.helperDrainFailure.get())).forEach {
                merged = CleanupFailures.merge(merged, it)
            }
            merged
        }
        val scoping = scopingSnapshot()
        val acquisition = control.acquisitionSnapshot()
        val observations = listOfNotNull(failure?.let { DomainDrainSnapshot(false, 0, 0, it) }, scoping, acquisition) + snapshots
        val outcome = DrainLifecycle.evaluate(owner.operations.managerTerminated(opsPermit) && !managerLive, managerLive, observations)
        if (outcome != DrainReport.Complete) return outcome
        return synchronized(shared.lock) {
            if (shared.checkedUnknown) return@synchronized DrainReport.Incomplete(0, 0, shared.checkedRawFailure)
            val history = transferredScopingHistory(shared.scopingPreparation?.state)
            if (history == null) {
                DrainReport.Complete
            } else {
                DrainReport.Incomplete(0, 0, history)
            }
        }
    }

        private fun awaitScopingDrain(deadline: Deadline): Boolean {
        val preparation = synchronized(shared.lock) { shared.scopingPreparation }
        if (preparation == null) return true

        fun observeLocked(): ScopingPreparation.State {
            val before = preparation.state
            val effects = transitionScoping(shared.scopingPreparation, preparation, ScopingPreparation.Event.DrainRequested)
            checkScopingDrainEffects(before, effects)
            return before
        }
        var lastObserved: ScopingPreparation.State? = null
        val before = synchronized(shared.lock) {
            val observed = observeLocked()
            lastObserved = preparation.state
            observed
        }
        try {
            val pending = scopingDrainPending(preparation, before)
            pending?.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS)
            synchronized(shared.lock) {
                if (preparation.state !== lastObserved) observeLocked()
                lastObserved = preparation.state
            }
            owner.operations.awaitScopingDrainRelease(opsPermit, control, preparation, deadline)
        } catch (_: TimeoutException) {
            // Caller budget expires without cancelling construction or release.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: ExecutionException) {
            // The release records the original failure before completion.
        } catch (_: CancellationException) {
            // Rejected submission is an incomplete observation.
        }
        return synchronized(shared.lock) {
            if (preparation.state !== lastObserved) observeLocked()
            !shared.checkedUnknown && preparation.state !is ScopingPreparation.State.Constructing && preparation.state !is ScopingPreparation.State.Claiming
        }
    }
    }

    internal fun matchesLegacyControl(
        key: Any,
        candidate: Any,
    ): Boolean = key === legacyOwnershipKey && candidate === legacyControl

    private class RuntimeLegacyControl(
        private val owner: ManagedRuntime,
        private val shared: RuntimeState,
        private val key: Any,
        private val gate: Any,
        private val worker: ThreadPoolExecutor,
        private val physicalManager: AtomicReference<Thread?>,
        private val capturedEnvironment: InstallationEnvironment,
        private val capturedEffects: LegacyAcquisitionEffects,
        private val opsPermit: Any,
    ) : LegacyControlOps {
        private var active: LegacyAcquisition? = null
        private var quiescence = java.util.concurrent.CountDownLatch(0)
        private var participants = 0
        private var epoch = 0L
        private var drainPermit: Any? = null

        init {
            check(owner.matchesOriginalState(shared, null, opsPermit) && gate === shared.lock)
        }

        fun matchesState(candidate: RuntimeState): Boolean = candidate === shared && owner.matchesLegacyControl(key, this)

        private fun authentic(candidateKey: Any): Boolean = candidateKey === key && owner.matchesLegacyControl(candidateKey, this)

        private fun current(
            candidate: Any,
            candidateKey: Any,
        ): Boolean = authentic(candidateKey) && active === candidate

        private fun managerCurrent(
            candidate: Any,
            candidateKey: Any,
        ): Boolean = current(candidate, candidateKey) && Thread.currentThread() === physicalManager.get()

        fun onPhysicalManager(): Boolean = Thread.currentThread() === physicalManager.get()

        fun matchesTaskContext(
            candidateGate: Any,
            candidateManagerThread: Any,
        ): Boolean = owner.matchesLegacyControl(key, this) && candidateGate === gate && candidateManagerThread === physicalManager

        fun beginJob() {
            check(Thread.holdsLock(gate) && owner.matchesLegacyControl(key, this))
            if (shared.preparations == 0) quiescence = java.util.concurrent.CountDownLatch(1)
        }

        fun jobFinished() {
            check(Thread.holdsLock(gate) && owner.matchesLegacyControl(key, this))
            if (shared.preparations == 0) quiescence.countDown()
        }

        fun attach(candidate: LegacyAcquisition) {
            check(Thread.holdsLock(gate) && owner.matchesLegacyControl(key, this) && Thread.currentThread() === physicalManager.get())
            requireRuntimeOpen(shared.phase == Phase.OPEN)
            check(active == null) { "Earlier acquisition has not retired" }
            active = candidate
        }

        fun beginDrain() {
            check(Thread.holdsLock(gate) && owner.matchesLegacyControl(key, this) && shared.phase == Phase.DRAINING)
            if (participants == 0) {
                epoch++
                drainPermit = Any()
            }
            participants++
        }

        fun endDrain() {
            check(Thread.holdsLock(gate) && owner.matchesLegacyControl(key, this) && participants > 0)
            participants--
            if (participants == 0) drainPermit = null
        }

        fun failureObservation(): Throwable? = synchronized(gate) { active?.failureObservation() }

        fun acquisitionSnapshot(): DomainDrainSnapshot? = synchronized(gate) { active?.retirementSnapshot() }

        fun awaitCurrent(deadline: Deadline): Boolean {
            val latch = synchronized(gate) { quiescence }
            val finished = try {
                latch.await(deadline.remainingNanos(), TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!finished) return false
            val candidate = synchronized(gate) { active }
            val permit = synchronized(gate) { drainPermit }
            return if (candidate == null) {
                synchronized(gate) { shared.preparations == 0 }
            } else if (permit == null) {
                false
            } else {
                candidate.awaitCleanup(deadline, permit)
            }
        }

        override fun contextMatches(
            candidateKey: Any,
            candidateGate: Any,
            candidateManager: Any,
            candidateEnvironment: Any,
            candidateEffects: Any,
        ): Boolean = authentic(candidateKey) && candidateGate === gate && candidateManager === worker && candidateEnvironment === capturedEnvironment && candidateEffects === capturedEffects

        override fun isCurrent(
            candidate: Any,
            candidateKey: Any,
        ): Boolean = synchronized(gate) { current(candidate, candidateKey) }

        override fun isOnManager(
            candidate: Any,
            candidateKey: Any,
        ): Boolean = synchronized(gate) { managerCurrent(candidate, candidateKey) }

        override fun matchesDirectCommitted(
            candidate: Any,
            key: Any,
            permit: Any,
            domain: OwnedDomain,
            action: DirectClaimAction,
        ): Boolean =
            Thread.holdsLock(gate) &&
                authentic(key) &&
                candidate is LegacyAcquisition &&
                candidate.isDirectAssociation(permit) &&
                shared.domains[domain.generation] === domain &&
                when (action) {
                    DirectClaimAction.PREPARE_LANDLOCK -> onPhysicalManager() && domain.allowsDirectValidation
                    DirectClaimAction.APPLY_WORKER -> domain.isDirectWorkerActor
                    DirectClaimAction.RETIRE_DOMAIN -> domain.isDirectRetirementActor
                    DirectClaimAction.CLAIM, DirectClaimAction.ALLOCATE_LANDLOCK, DirectClaimAction.ROLLBACK_RULESET -> false
                }

        override fun boundaryQuiescent(
            candidate: Any,
            candidateKey: Any,
            permit: Any,
        ): Boolean =
            synchronized(gate) {
            current(candidate, candidateKey) && permit === drainPermit && shared.phase == Phase.DRAINING && participants > 0 && shared.preparations == 0 && quiescence.count == 0L
        }

        override fun drainEpoch(
            candidate: Any,
            candidateKey: Any,
            permit: Any,
        ): Long =
            synchronized(gate) {
            check(current(candidate, candidateKey) && permit === drainPermit && shared.phase == Phase.DRAINING && participants > 0)
            epoch
        }

        override fun canReserve(
            candidate: Any,
            candidateKey: Any,
            expectedEpoch: Long,
            permit: Any,
        ): Boolean =
            synchronized(gate) {
            current(candidate, candidateKey) &&
                permit === drainPermit &&
                shared.phase == Phase.DRAINING &&
                participants > 0 &&
                epoch == expectedEpoch &&
                shared.preparations == 0 &&
                quiescence.count == 0L
        }

        override fun releaseClean(
            candidate: Any,
            candidateKey: Any,
        ) {
            synchronized(gate) {
                check(managerCurrent(candidate, candidateKey) && (candidate as LegacyAcquisition).cleanForRelease())
                active = null
            }
        }

        override fun acknowledgeClean(
            candidate: Any,
            candidateKey: Any,
        ) {
            check(Thread.holdsLock(gate))
            if (current(candidate, candidateKey) && (candidate as LegacyAcquisition).cleanForAcknowledgment()) active = null
        }

        override fun commitDomain(
            candidate: Any,
            candidateKey: Any,
            domain: OwnedDomain,
            ref: AtomicReference<OwnedDomain?>,
        ) {
            owner.operations.legacyGenerationOperation(opsPermit, candidate, candidateKey, LegacyGenerationAction.Commit(domain, ref))
        }

        fun transfer(
            candidate: Any,
            candidateKey: Any,
        ) {
            check(Thread.holdsLock(gate) && managerCurrent(candidate, candidateKey))
            active = null
        }

        override fun nextGeneration(
            candidate: Any,
            candidateKey: Any,
        ): Long = owner.operations.legacyGenerationOperation(opsPermit, candidate, candidateKey, LegacyGenerationAction.Reserve)

        override fun retireStopped(
            candidate: Any,
            candidateKey: Any,
        ) {
            val previous = synchronized(gate) {
                check(managerCurrent(candidate, candidateKey))
                shared.domains.values.toList()
            }
            previous.forEach { it.retireIfStopped() }
        }
    }

    @Suppress("TooGenericExceptionCaught") // The manager must retain a detached sweep's fatal failure before its future completes.
    private fun awaitHelperSweep(control: RuntimeLegacyControl, deadline: Deadline): Future<*>? {
        if (!operations.mayAwaitHelperSweep(operationsPermit, control)) return null
        val liveRetryEligible = synchronized(lock) { domains.values.toList() }.filter { it.initialLiveRetirementEnded() }.toSet()
        var launch: FutureTask<Unit>? = null
        var prior: Future<*>? = null
        val sweep = synchronized(lock) {
            if (!operations.mayAwaitHelperSweep(operationsPermit, control)) return null
            if (!manager.isShutdown && !manager.isTerminated && deadline.remainingNanos() > 0) {
                val previous = helperDrainSweep
                if (previous == null || previous.isDone) {
                    val task = FutureTask {
                        try {
                            sweepConfined(deadline, retry = previous != null, liveRetryEligible = liveRetryEligible)
                        } catch (failure: Throwable) {
                            retainHelperDrainFailure(lock, helperDrainFailure, failure)
                            throw failure
                        }
                    }
                    prior = previous
                    helperDrainSweep = task
                    launch = task
                }
            }
            helperDrainSweep
        }
        launch?.let { task ->
            try {
                manager.execute(task)
            } catch (_: RejectedExecutionException) {
                synchronized(lock) { if (helperDrainSweep === task) helperDrainSweep = prior }
                task.cancel(false)
            }
        }
        return sweep
    }

    @Suppress("TooGenericExceptionCaught") // The manager retains failures and the same domain owners instead of replacing cleanup execution.
    private fun sweepConfined(
        deadline: Deadline,
        retry: Boolean,
        liveRetryEligible: Set<OwnedDomain>,
    ) {
        val resources = synchronized(lock) { domains.values.toList() }
        for (resource in resources) {
            if (resource.retired()) continue
            val canRetry = canRetryConfined(resource, retry, liveRetryEligible)
            resource.beginDrain()
            resource.awaitRetirement(deadline)
            if (canRetry && !resource.retired() && deadline.remainingNanos() > 0) {
                try {
                    resource.retryConfinedRetirement(ConfinedDeadline.mint(environment.native, deadline::remainingNanos))
                } catch (failure: Exception) {
                    retainHelperDrainFailure(lock, helperDrainFailure, failure)
                }
            }
        }
        val settled = resources.filter { it.retired() }.map { ScopingPreparation.Event.DomainSettled(it, it.drainSnapshot().cleanupFailure) }
        synchronized(lock) {
            val preparation = scopingPreparation
            val current = preparation?.state as? ScopingPreparation.State.DomainOwned
            val event = settled.firstOrNull { it.domain === current?.domain }
            if (preparation != null && event != null) transitionScoping(scopingPreparation, preparation, event)
            val scoping = scopingPreparation
            if (preparations == 0 && !shared.checkedUnknown && mayStopConfinedManager(domains.values, scoping, manager)) {
                    manager.shutdown()
                }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun releaseScopingOnManager(preparation: ScopingPreparation) {
        var progress = synchronized(lock) {
            check(operations.mayReleaseScoping(operationsPermit, preparation))
            val state = preparation.state as ScopingPreparation.State.Retiring
            check(state.retained.inFlight)
            state.retained.helperCapture?.authorizeCarrierCleanup(preparation)
            state.retained
        }
        val owner = checkNotNull(progress.owner)
        var fatalFailure: Error? = null
        try {
            if (progress.step == ScopingPreparation.ReleaseStage.INVALIDATE) {
                progress = observeScopingInvalidation(progress, owner.capability.invalidate(owner))
            }
            if (progress.step == ScopingPreparation.ReleaseStage.RETIRE) {
                progress = observeScopingRetirement(progress, owner.capability.retireInstallation(owner))
            }
            val helperResult = releaseCarrierHelper(progress)
            progress = helperResult.first
            fatalFailure = helperResult.second
            if (canReleaseScopingLandlock(progress)) {
                try {
                    progress.landlock?.close()
                    progress = progress.copy(landlockStep = ScopingPreparation.ReleaseStep.Complete)
                } catch (fatal: Error) {
                    progress = progress.copy(
                        step = ScopingPreparation.ReleaseStage.RESOURCES_UNKNOWN,
                        landlockStep = ScopingPreparation.ReleaseStep.Unknown,
                        failure = CleanupFailures.merge(progress.failure, fatal),
                    )
                    fatalFailure = fatal
                } catch (failure: Throwable) {
                    progress = progress.copy(
                        step = ScopingPreparation.ReleaseStage.RESOURCES_UNKNOWN,
                        landlockStep = ScopingPreparation.ReleaseStep.Unknown,
                        failure = CleanupFailures.merge(progress.failure, failure),
                    )
                }
            }
            if (progress.step == ScopingPreparation.ReleaseStage.RESOURCES) {
                try {
                    when (progress.disposition) {
                        ScopingPreparation.ResourceDisposition.Borrowed -> Unit
                        ScopingPreparation.ResourceDisposition.AvailableOwned -> progress.resources?.close()
                        ScopingPreparation.ResourceDisposition.ClaimedOwned -> progress.resources?.retire()
                    }
                    progress = progress.copy(step = ScopingPreparation.ReleaseStage.SETTLED, resourceStep = ScopingPreparation.ReleaseStep.Complete)
                } catch (fatal: Error) {
                    progress = progress.copy(
                        step = ScopingPreparation.ReleaseStage.RESOURCES_UNKNOWN,
                        resourceStep = ScopingPreparation.ReleaseStep.Unknown,
                        failure = CleanupFailures.merge(progress.failure, fatal),
                    )
                    fatalFailure = fatal
                } catch (failure: Throwable) {
                    progress = progress.copy(
                        step = ScopingPreparation.ReleaseStage.RESOURCES_UNKNOWN,
                        resourceStep = ScopingPreparation.ReleaseStep.Unknown,
                        failure = CleanupFailures.merge(progress.failure, failure),
                    )
                }
            }
        } catch (fatal: Error) {
            if (progress.step != ScopingPreparation.ReleaseStage.RESOURCES_UNKNOWN) {
                progress = progress.copy(step = ScopingPreparation.ReleaseStage.NATIVE_UNKNOWN, failure = CleanupFailures.merge(progress.failure, fatal))
            }
            fatalFailure = fatal
        } catch (failure: Throwable) {
            progress = progress.copy(step = ScopingPreparation.ReleaseStage.NATIVE_UNKNOWN, failure = CleanupFailures.merge(progress.failure, failure))
        } finally {
            val effects = synchronized(lock) { transitionScoping(scopingPreparation, preparation, ScopingPreparation.Event.ReleaseCompleted(progress)) }
            check(ScopingPreparation.Effect.Notify in effects)
            // Return completes the shared FutureTask and wakes all close/drain waiters.
        }
        rethrowScopingFailure(fatalFailure)
    }

    override fun close() {
        when (val report = drain(closeTimeout)) {
            DrainReport.Complete -> Unit
            is DrainReport.Incomplete -> throw IncompleteDrainException(report)
        }
    }
}

private fun rejectTransition(): Nothing {
    val failure = ExecutionTransitionException("Requested domain transition is not approved for this active scope")
    RuntimeWorkerContext.invocation?.recordObservedFailure(failure)
    throw failure
}

private fun <T> awaitInvocation(handle: InvocationHandle<T>): T =
    try {
    handle.result().toCompletableFuture().get()
} catch (failed: ExecutionException) {
    throw failed.cause ?: failed
} catch (interrupted: InterruptedException) {
    handle.requestCancellation()
    Thread.currentThread().interrupt()
    throw interrupted
}

private fun <T> awaitLegacyPreparation(
    future: Future<T>,
    timeout: Duration,
    acquisition: LegacyAcquisition,
): T =
    try {
    val value = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
    acquisition.markWaiter(true)
    value
} catch (failure: ExecutionException) {
    val cause = failure.cause ?: failure
    acquisition.markWaiter(true)
    throw cause
} catch (timeoutFailure: TimeoutException) {
    acquisition.markWaiter(false)
    future.cancel(true)
    throw timeoutFailure
} catch (interrupted: InterruptedException) {
    acquisition.markWaiter(false)
    future.cancel(true)
    Thread.currentThread().interrupt()
    throw interrupted
}

private fun <T> awaitPreparation(
    future: Future<T>,
    timeout: Duration,
): T =
    try {
    future.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
} catch (failure: ExecutionException) {
    throw failure.cause ?: failure
} catch (timeout: TimeoutException) {
    future.cancel(true)
    throw timeout
} catch (interrupted: InterruptedException) {
    future.cancel(true)
    Thread.currentThread().interrupt()
    throw interrupted
}

private fun completeScopingClose(
    state: ScopingPreparation.State,
    waitFailure: Throwable?,
) {
    if (state is ScopingPreparation.State.DomainOwned || state is ScopingPreparation.State.TransferredSettled) return
    if (state is ScopingPreparation.State.PreparedSettled && waitFailure == null) {
        rethrowScopingFailure(state.failure)
        return
    }
    val retained = when (state) {
        is ScopingPreparation.State.PreparedSettled -> state.failure
        is ScopingPreparation.State.Retiring -> state.retained.failure
        is ScopingPreparation.State.Claiming -> (state.request as? ScopingPreparation.PrepareRequest.Failed)?.cause
        is ScopingPreparation.State.DomainOwned -> state.history
        is ScopingPreparation.State.TransferredSettled -> state.history
        is ScopingPreparation.State.Constructing, is ScopingPreparation.State.Ready -> null
    }
    val combined = if (waitFailure == null) retained else CleanupFailures.merge(retained, waitFailure)
    if (waitFailure is Error && combined is Error) throw combined
    throw IllegalStateException("Prepared stack scoping cleanup is incomplete", combined)
}

private fun transitionScoping(
    active: ScopingPreparation?,
    preparation: ScopingPreparation,
    event: ScopingPreparation.Event,
): List<ScopingPreparation.Effect> {
    check(active === preparation)
    val decision = ScopingPreparation.evaluate(preparation.state, event)
    decision.effects
        .filterIsInstance<ScopingPreparation.Effect.Reject>()
        .firstOrNull()
        ?.let { throw it.cause }
    preparation.state = decision.state
    return decision.effects
}

private fun requireRuntimeOpen(
    open: Boolean,
    rejectedDomain: OwnedDomain? = null,
) {
    if (!open) {
        rejectedDomain?.recordRejection()
        throw RejectedExecutionException("Runtime is not admitting")
    }
}

private fun rethrowScopingFailure(failure: Throwable?) {
    if (failure != null) throw failure
}

private fun awaitScopingClose(
    task: Future<*>?,
    deadline: Deadline,
): Throwable? =
    try {
    task?.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS)
    null
} catch (interrupted: InterruptedException) {
    Thread.currentThread().interrupt()
    interrupted
} catch (timeout: TimeoutException) {
    timeout
} catch (failed: ExecutionException) {
    failed.cause ?: failed
} catch (_: CancellationException) {
    null
}

private fun transferredScopingHistory(state: ScopingPreparation.State?): Throwable? =
    when (state) {
    is ScopingPreparation.State.DomainOwned -> state.history
    is ScopingPreparation.State.TransferredSettled -> state.history
    is ScopingPreparation.State.Constructing, is ScopingPreparation.State.Ready,
    is ScopingPreparation.State.Claiming, is ScopingPreparation.State.Retiring,
    is ScopingPreparation.State.PreparedSettled, null,
    -> null
}

private val noWaitFailureObserver: () -> Unit = {}

private class ScopingTestEffects(
    val afterTypedStore: () -> Unit,
    val waitFailureObserver: () -> Unit,
    val afterDomainCommit: () -> Unit,
    val beforeWait: () -> Unit,
)

private val defaultScopingEffects = ScopingTestEffects(noWaitFailureObserver, noWaitFailureObserver, noWaitFailureObserver, noWaitFailureObserver)

private class LegacyAcquisitionEffects(
    val afterTypedStore: () -> Unit,
    val beforeRollbackDispatch: () -> Unit,
    val afterGenerationReserved: () -> Unit,
    val afterPathStore: () -> Unit,
) {
    constructor(
        afterTypedStore: () -> Unit,
        beforeRollbackDispatch: () -> Unit,
        afterGenerationReserved: () -> Unit,
    ) : this(afterTypedStore, beforeRollbackDispatch, afterGenerationReserved, noWaitFailureObserver)
    constructor(afterTypedStore: () -> Unit, beforeRollbackDispatch: () -> Unit) : this(afterTypedStore, beforeRollbackDispatch, noWaitFailureObserver, noWaitFailureObserver)
}

private val defaultLegacyAcquisitionEffects = LegacyAcquisitionEffects(noWaitFailureObserver, noWaitFailureObserver)

private sealed interface LegacyGenerationAction {
    data object Reserve : LegacyGenerationAction

    class Commit(
        val domain: OwnedDomain,
        val ref: AtomicReference<OwnedDomain?>,
    ) : LegacyGenerationAction
}

private interface LegacyControlOps {
    fun contextMatches(
        key: Any,
        gate: Any,
        manager: Any,
        environment: Any,
        effects: Any,
    ): Boolean

    fun isCurrent(
        candidate: Any,
        key: Any,
    ): Boolean

    fun isOnManager(
        candidate: Any,
        key: Any,
    ): Boolean

    fun matchesDirectCommitted(
        candidate: Any,
        key: Any,
        permit: Any,
        domain: OwnedDomain,
        action: DirectClaimAction,
    ): Boolean

    fun boundaryQuiescent(
        candidate: Any,
        key: Any,
        permit: Any,
    ): Boolean

    fun drainEpoch(
        candidate: Any,
        key: Any,
        permit: Any,
    ): Long

    fun canReserve(
        candidate: Any,
        key: Any,
        epoch: Long,
        permit: Any,
    ): Boolean

    fun releaseClean(
        candidate: Any,
        key: Any,
    )

    fun acknowledgeClean(
        candidate: Any,
        key: Any,
    )

    fun commitDomain(
        candidate: Any,
        key: Any,
        domain: OwnedDomain,
        ref: AtomicReference<OwnedDomain?>,
    )

    fun nextGeneration(
        candidate: Any,
        key: Any,
    ): Long

    fun retireStopped(
        candidate: Any,
        key: Any,
    )
}

internal enum class DirectClaimAction { CLAIM, ALLOCATE_LANDLOCK, PREPARE_LANDLOCK, APPLY_WORKER, ROLLBACK_RULESET, RETIRE_DOMAIN }

private sealed interface DirectPermitProof

private class DirectClaimPermit(
    private val runtime: ManagedRuntime,
    private val acquisition: LegacyAcquisition,
    private val operation: PreparedOperationResources,
    private val environment: InstallationEnvironment,
    private val abi: Int,
    private val gate: Any,
) : DirectPermitProof {
    fun matchesLocked(
        candidateRuntime: ManagedRuntime,
        candidateOperation: Any,
        action: DirectClaimAction,
    ): Boolean = Thread.holdsLock(gate) && candidateRuntime === runtime && candidateOperation === operation && acquisition.matchesDirectPermit(this, action)

    fun matches(
        candidateRuntime: ManagedRuntime,
        candidateOperation: Any,
        action: DirectClaimAction,
    ): Boolean = synchronized(gate) { matchesLocked(candidateRuntime, candidateOperation, action) }

    fun attach(
        candidateRuntime: ManagedRuntime,
        candidateOperation: PreparedOperationResources,
    ): Boolean =
        synchronized(gate) {
            matchesLocked(candidateRuntime, candidateOperation, DirectClaimAction.CLAIM) && acquisition.commitDirectClaim(this, candidateOperation)
        }

    fun matchesResources(
        candidateRuntime: ManagedRuntime,
        candidateOperation: PreparedOperationResources,
        candidateEnvironment: InstallationEnvironment,
        resources: Any,
    ): Boolean =
        synchronized(gate) {
            candidateEnvironment === environment &&
                matchesLocked(candidateRuntime, candidateOperation, DirectClaimAction.ALLOCATE_LANDLOCK) &&
                candidateOperation.matchesDirectResources(this, resources)
        }

    fun matchesClaim(
        candidateRuntime: ManagedRuntime,
        candidateOperation: PreparedOperationResources,
        binding: OperationBinding,
        policy: CanonicalPolicyArtifact.Worker,
        engine: io.mazewall.platform.native.RuntimeSnapshot,
        candidateAbi: Int,
    ): Boolean =
        synchronized(gate) {
            matchesLocked(candidateRuntime, candidateOperation, DirectClaimAction.CLAIM) &&
                environment.native.engineIdentity === engine.engineIdentity &&
                abi == candidateAbi &&
                acquisition.matchesDirectBinding(binding, policy)
        }

    fun beginValidation(
        candidateRuntime: ManagedRuntime,
        candidateOperation: PreparedOperationResources,
        binding: OperationBinding,
        policy: CanonicalPolicyArtifact.Worker,
        engine: io.mazewall.platform.native.RuntimeSnapshot,
        candidateAbi: Int,
    ): Boolean =
        synchronized(gate) {
            matchesClaim(candidateRuntime, candidateOperation, binding, policy, engine, candidateAbi) && candidateOperation.beginDirectValidation(candidateRuntime, this, engine)
        }

    fun matchesShell(
        candidateRuntime: ManagedRuntime,
        candidateOperation: PreparedOperationResources,
        shell: Any,
        action: DirectClaimAction,
    ): Boolean = synchronized(gate) { matchesLocked(candidateRuntime, candidateOperation, action) && acquisition.matchesDirectShell(shell) }

    fun matchesCarrierRetirement(
        candidateRuntime: ManagedRuntime,
        candidateOperation: PreparedOperationResources,
        action: DirectClaimAction,
    ): Boolean =
        synchronized(gate) {
            matchesLocked(candidateRuntime, candidateOperation, action) &&
                when (action) {
                    DirectClaimAction.ROLLBACK_RULESET -> acquisition.mayRetireDirectCarrier()
                    DirectClaimAction.RETIRE_DOMAIN -> acquisition.hasDirectRulesetReceipt
                    DirectClaimAction.CLAIM, DirectClaimAction.ALLOCATE_LANDLOCK,
                    DirectClaimAction.PREPARE_LANDLOCK, DirectClaimAction.APPLY_WORKER,
                    -> false
                }
        }

    fun rulesetStored(
        candidateRuntime: ManagedRuntime,
        candidateOperation: PreparedOperationResources,
        shell: Any,
    ) {
        check(candidateRuntime === runtime && candidateOperation === operation)
        acquisition.directRulesetStored(this, shell)
    }
}

internal fun ManagedRuntime.matchesDirectPermit(
    permit: Any,
    operation: Any,
    action: DirectClaimAction,
): Boolean = permit is DirectClaimPermit && permit.matches(this, operation, action)

internal fun ManagedRuntime.matchesDirectPermitLocked(
    permit: Any,
    operation: Any,
    action: DirectClaimAction,
): Boolean = permit is DirectClaimPermit && permit.matchesLocked(this, operation, action)

internal fun ManagedRuntime.attachDirectPermit(
    permit: Any,
    operation: PreparedOperationResources,
): Boolean = permit is DirectClaimPermit && permit.attach(this, operation)

internal fun ManagedRuntime.matchesDirectResources(
    permit: Any,
    operation: PreparedOperationResources,
    environment: InstallationEnvironment,
    resources: Any,
): Boolean = permit is DirectClaimPermit && permit.matchesResources(this, operation, environment, resources)

internal fun ManagedRuntime.matchesDirectClaim(
    permit: Any,
    operation: PreparedOperationResources,
    binding: OperationBinding,
    policy: CanonicalPolicyArtifact.Worker,
    engine: io.mazewall.platform.native.RuntimeSnapshot,
    abi: Int,
): Boolean = permit is DirectClaimPermit && permit.matchesClaim(this, operation, binding, policy, engine, abi)

internal fun ManagedRuntime.beginDirectValidation(
    permit: Any,
    operation: PreparedOperationResources,
    binding: OperationBinding,
    policy: CanonicalPolicyArtifact.Worker,
    engine: io.mazewall.platform.native.RuntimeSnapshot,
    abi: Int,
): Boolean = permit is DirectClaimPermit && permit.beginValidation(this, operation, binding, policy, engine, abi)

internal fun ManagedRuntime.matchesDirectShell(
    permit: Any,
    operation: PreparedOperationResources,
    shell: Any,
    action: DirectClaimAction,
): Boolean = permit is DirectClaimPermit && permit.matchesShell(this, operation, shell, action)

internal fun ManagedRuntime.matchesDirectCarrierRetirement(
    permit: Any,
    operation: PreparedOperationResources,
    action: DirectClaimAction,
): Boolean = permit is DirectClaimPermit && permit.matchesCarrierRetirement(this, operation, action)

internal fun ManagedRuntime.directRulesetStored(
    permit: Any,
    operation: PreparedOperationResources,
    shell: Any,
) {
    check(permit is DirectClaimPermit)
    permit.rulesetStored(this, operation, shell)
}

private class LegacyAcquisitionRequest(
    val definition: PolicyDefinition<*>,
    val limits: DomainLimits,
    val artifact: ReviewedOperationArtifact?,
    val operation: PreparedOperationResources?,
    val frozenScoping: io.mazewall.enforcer.internal.execution.FrozenStackScoping.Legacy,
    val confined: ConfinedHelperConfiguration?,
    val resourceRef: AtomicReference<OwnedDomain?>,
    val namePrefix: String,
)

internal fun matchesManagedPath(
    runtime: ManagedRuntime,
    key: Any,
    record: Any,
    capture: Any,
    slot: Any?,
    native: io.mazewall.platform.native.NativeEngine,
    cleanup: Boolean,
): Boolean =
    record is LegacyAcquisition &&
        record.matchesCapture(runtime, key, capture, native, cleanup) &&
        (slot == null || capture is io.mazewall.enforcer.internal.execution.PreparedResourceSet.ManagedCapture && capture.permitsSlot(slot, cleanup))

private class LegacyAcquisition(
    private val runtime: ManagedRuntime,
    private val control: LegacyControlOps,
    private val key: Any,
    private val gate: Any,
    private val manager: ThreadPoolExecutor,
    private val environment: InstallationEnvironment,
    private val effects: LegacyAcquisitionEffects,
    private val request: LegacyAcquisitionRequest,
) {
    private enum class Step { NOT_ATTEMPTED, IN_FLIGHT, COMPLETE, UNKNOWN }

    private enum class Waiter { WAITING, DELIVERED, ABANDONED }

    private val waiterThread = Thread.currentThread()
    private var waiter = Waiter.WAITING

    private var landlock: io.mazewall.landlock.PreparedLandlock? = null
    private var hasRuleset = false
    private var claimed = false
    private var cleanupFailed = false
    private var localResources: io.mazewall.enforcer.internal.execution.PreparedResourceSet? = null
    private var managedCapture: io.mazewall.enforcer.internal.execution.PreparedResourceSet.ManagedCapture? = null
    private var rulesetStep = Step.NOT_ATTEMPTED
    private var resourcesStep = Step.NOT_ATTEMPTED
    private var failure: Throwable? = null
    private var initialFailure: Throwable? = null
    private var rawCleanupFailure: Throwable? = null
    private var rawProofFailure: Throwable? = null
    private var rawMetadataFailure: Throwable? = null
    private var historyBlocked = false
    private var pendingDomain: OwnedDomain? = null
    private var reservedGeneration = 0L
    private var sweep: FutureTask<Unit>? = null
    private var sweepEpoch = 0L
    private var initialCleanup = true
    private var started = false
    private var rollbackRequested = false
    private var directPermit: DirectClaimPermit? = null
    private var directDomain: OwnedDomain? = null

    fun matchesDirectPermit(
        candidate: Any,
        action: DirectClaimAction,
    ): Boolean {
        if (!Thread.holdsLock(gate) || directPermit !== candidate) return false
        val domain = directDomain
        return if (domain != null) {
            control.matchesDirectCommitted(this, key, candidate, domain, action)
        } else if (!control.isOnManager(this, key)) {
            false
        } else {
            val preparing = started && waiter != Waiter.ABANDONED && !rollbackRequested
            when (action) {
                DirectClaimAction.CLAIM -> preparing
                DirectClaimAction.ALLOCATE_LANDLOCK, DirectClaimAction.PREPARE_LANDLOCK -> claimed && preparing
                DirectClaimAction.ROLLBACK_RULESET -> claimed && rollbackRequested && (rulesetStep == Step.IN_FLIGHT || resourcesStep == Step.IN_FLIGHT)
                DirectClaimAction.APPLY_WORKER, DirectClaimAction.RETIRE_DOMAIN -> false
            }
        }
    }

    fun matchesDirectShell(shell: Any): Boolean = Thread.holdsLock(gate) && landlock === shell

    fun mayRetireDirectCarrier(): Boolean = Thread.holdsLock(gate) && rulesetStep == Step.COMPLETE && resourcesStep == Step.IN_FLIGHT

    val hasDirectRulesetReceipt: Boolean
        get() {
            if (!Thread.holdsLock(gate)) return false
            val domain = directDomain ?: return false
            return domain.hasDirectRulesetReceipt
        }

    fun commitDirectClaim(
        permit: Any,
        operation: PreparedOperationResources,
    ): Boolean {
        check(Thread.holdsLock(gate) && directPermit === permit && request.operation === operation && matchesDirectPermit(permit, DirectClaimAction.CLAIM))
        val attached = operation.attachDirectPermit(runtime, permit)
        if (attached) claimed = true
        return attached
    }

    fun matchesDirectBinding(
        binding: OperationBinding,
        policy: CanonicalPolicyArtifact.Worker,
    ): Boolean = Thread.holdsLock(gate) && request.artifact?.binding === binding && request.artifact.document.policy === policy

    fun isDirectAssociation(candidate: Any): Boolean =
        Thread.holdsLock(gate) &&
            directPermit === candidate &&
            runtime.matchesLegacyControl(key, control) &&
            control.contextMatches(key, gate, manager, environment, effects)

    fun commitDirectDomain(domain: OwnedDomain) {
        check(Thread.holdsLock(gate) && pendingDomain === domain && runtime.matchesLegacyControl(key, control))
        if (directPermit != null) directDomain = domain
    }

    fun directRulesetStored(
        permit: Any,
        shell: Any,
    ) {
        requireManager()
        synchronized(gate) {
            check(landlock === shell && directPermit === permit && started && !hasRuleset)
            hasRuleset = true
        }
        effects.afterTypedStore()
    }

    init {
        check(runtime.matchesLegacyControl(key, control) && control.contextMatches(key, gate, manager, environment, effects))
    }

    private fun requireManager() {
        check(control.isOnManager(this, key)) { "Foreign or inactive acquisition manager" }
    }

    fun matchesCapture(
        candidateRuntime: ManagedRuntime,
        candidateKey: Any,
        candidate: Any,
        native: io.mazewall.platform.native.NativeEngine,
        cleanup: Boolean,
    ): Boolean =
        candidateRuntime === runtime &&
            candidateKey === key &&
            control.isOnManager(this, key) &&
            managedCapture === candidate &&
            native === environment.native &&
            if (cleanup) rollbackRequested && resourcesStep == Step.IN_FLIGHT else started && !rollbackRequested && resourcesStep == Step.NOT_ATTEMPTED

    fun isPendingDomain(
        domain: OwnedDomain,
        ref: AtomicReference<OwnedDomain?>,
    ): Boolean = control.isOnManager(this, key) && pendingDomain === domain && request.resourceRef === ref && reservedGeneration == domain.generation

    fun cleanForRelease(): Boolean = control.isOnManager(this, key) && waiter == Waiter.DELIVERED && !cleanupFailed && rulesetStep == Step.COMPLETE && resourcesStep == Step.COMPLETE

    fun cleanForAcknowledgment(): Boolean =
        Thread.holdsLock(gate) && Thread.currentThread() === waiterThread && waiter == Waiter.DELIVERED && !cleanupFailed && rulesetStep == Step.COMPLETE && resourcesStep == Step.COMPLETE

    fun canCommit(): Boolean = Thread.holdsLock(gate) && waiter != Waiter.ABANDONED

    fun markWaiter(delivered: Boolean) {
        check(Thread.currentThread() === waiterThread && runtime.matchesLegacyControl(key, control) && control.contextMatches(key, gate, manager, environment, effects))
        synchronized(gate) {
            check(waiter == Waiter.WAITING)
            waiter = if (delivered) Waiter.DELIVERED else Waiter.ABANDONED
            if (delivered) control.acknowledgeClean(this, key)
        }
    }

    fun failureObservation(): Throwable? = if (control.isCurrent(this, key)) failure else null

    fun retirementSnapshot(): DomainDrainSnapshot =
        synchronized(gate) {
        check(control.isCurrent(this, key))
        DomainDrainSnapshot(rulesetStep == Step.COMPLETE && resourcesStep == Step.COMPLETE, 0, 0, failure)
    }

    @Suppress("TooGenericExceptionCaught") // The original carrier retains every acquisition before rethrowing its failure.
    fun acquireAndCommit(): OwnedDomain {
        requireManager()
        synchronized(gate) {
            check(!started)
            started = true
        }
        try {
            control.retireStopped(this, key)
            request.operation?.let {
                val reviewed = checkNotNull(request.artifact)
                if (it.isDirectResult()) {
                    val abi = environment.platform.capabilities.matrix.landlockAbiVersion
                    val permit = DirectClaimPermit(runtime, this, it, environment, abi, gate)
                    synchronized(gate) {
                        check(directPermit == null)
                        directPermit = permit
                    }
                    it.claimDirect(runtime, permit, reviewed.binding, reviewed.document.policy as CanonicalPolicyArtifact.Worker, environment.native, abi)
                } else {
                    it.claim(reviewed.binding, reviewed.document.policy as CanonicalPolicyArtifact.Worker, environment.native, environment.platform.capabilities.matrix.landlockAbiVersion)
                }
                claimed = true
            }
            if (request.definition.enforceLandlock) {
                if (request.operation == null) {
                    val capture = io.mazewall.enforcer.internal.execution.PreparedResourceSet.ManagedCapture(
                        runtime,
                        key,
                        this,
                        request.definition,
                        environment.native,
                        effects.afterPathStore,
                    )
                    synchronized(gate) { managedCapture = capture }
                    val resources = capture.capture()
                    synchronized(gate) {
                        localResources = resources
                        managedCapture = null
                    }
                }
                landlock = if (request.operation == null) {
                    io.mazewall.landlock.PreparedLandlock
                        .allocateOwnedAcquisition(checkNotNull(localResources), environment)
                } else if (request.operation.isDirectResult()) {
                    request.operation.allocateDirectLandlock(runtime, checkNotNull(directPermit), environment)
                } else {
                    io.mazewall.landlock.PreparedLandlock
                        .allocateOwnedAcquisition(request.operation, environment)
                }
                val afterStore: () -> Unit = {
                    hasRuleset = true
                    effects.afterTypedStore()
                }
                if (request.operation?.isDirectResult() == true) {
                    checkNotNull(landlock).prepareDirect()
                } else {
                    io.mazewall.landlock.PreparedLandlock
                        .prepareOwnedAcquisitionWithPostStore(request.definition, environment, checkNotNull(landlock), afterStore)
                }
            }
            check(!Thread.currentThread().isInterrupted) { "Domain preparation was cancelled" }
            reservedGeneration = control.nextGeneration(this, key)
            effects.afterGenerationReserved()
            val domain =
                OwnedDomain(
                    reservedGeneration,
                    request.limits,
                    request.definition,
                    environment,
                    landlock,
                    request.operation,
                    "${request.namePrefix}$reservedGeneration-",
                    request.frozenScoping,
                    request.confined,
                )
            pendingDomain = domain
            control.commitDomain(this, key, domain, request.resourceRef)
            return domain
        } catch (problem: Throwable) {
            synchronized(gate) {
                failure = problem
                initialFailure = problem
                rollbackRequested = true
            }
            cleanup()
            if (cleanForRelease()) control.releaseClean(this, key)
            throw checkNotNull(synchronized(gate) { failure })
        }
    }

    fun awaitCleanup(
        deadline: Deadline,
        permit: Any,
    ): Boolean {
        if (deadline.remainingNanos() == 0L || !control.boundaryQuiescent(this, key, permit)) return false
        val reservation = reserveSweep(control.drainEpoch(this, key, permit), permit) ?: return false
        return (!reservation.second || submitSweep(checkNotNull(reservation.first))) && awaitSweep(reservation.first, deadline) && settled()
    }

    private fun reserveSweep(
        epoch: Long,
        permit: Any,
    ): Pair<FutureTask<Unit>?, Boolean>? =
        synchronized(gate) {
        val existing = sweep
        when {
            !control.canReserve(this, key, epoch, permit) -> null
            existing != null && !existing.isDone -> existing to false
            (rulesetStep == Step.NOT_ATTEMPTED || rulesetStep == Step.COMPLETE && resourcesStep == Step.NOT_ATTEMPTED) &&
                !manager.isShutdown &&
                sweepEpoch != epoch -> {
                val task = FutureTask { cleanup() }
                sweep = task
                sweepEpoch = epoch
                task to true
            }
            else -> existing to false
        }
    }

    private fun submitSweep(task: FutureTask<Unit>): Boolean =
        try {
        manager.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        synchronized(gate) { if (sweep === task) sweep = null }
        false
    }

    private fun awaitSweep(
        task: FutureTask<Unit>?,
        deadline: Deadline,
    ): Boolean =
        try {
        task?.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS)
        true
    } catch (_: TimeoutException) {
        false
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    } catch (_: ExecutionException) {
        true // The owner records the failure before its sweep completes.
    } catch (_: CancellationException) {
        false
    }

    private fun settled(): Boolean =
        synchronized(gate) {
        control.isCurrent(this, key) &&
            rulesetStep != Step.NOT_ATTEMPTED &&
            rulesetStep != Step.IN_FLIGHT &&
            (rulesetStep == Step.UNKNOWN || resourcesStep != Step.NOT_ATTEMPTED && resourcesStep != Step.IN_FLIGHT)
    }

    private fun reserveCleanup(ruleset: Boolean): Boolean =
        synchronized(gate) {
        check(rollbackRequested)
        val ready = !historyBlocked && rawCleanupFailure == null && rawProofFailure == null && rawMetadataFailure == null
        val allowed = if (ruleset) {
            rulesetStep == Step.NOT_ATTEMPTED && (initialCleanup || sweep != null)
        } else {
            rulesetStep == Step.COMPLETE && resourcesStep == Step.NOT_ATTEMPTED
        }
        if (ready && allowed) {
            if (ruleset) {
                rulesetStep = Step.IN_FLIGHT
                initialCleanup = false
            } else {
                resourcesStep = Step.IN_FLIGHT
            }
            true
        } else {
            false
        }
    }

    private fun updateCleanup(
        ruleset: Boolean,
        disposition: Step,
        problem: Throwable?,
        metadata: Boolean,
    ) = synchronized(gate) {
        if (problem != null) {
            if (metadata) {
                rawMetadataFailure = problem
            } else {
                rawCleanupFailure = problem
                cleanupFailed = true
            }
        }
        if (metadata) historyBlocked = true
        if (ruleset) rulesetStep = disposition else resourcesStep = disposition
    }

    private fun reconcileFailure(
        next: Throwable?,
        ruleset: Boolean,
        retryPending: Boolean,
    ): Boolean {
        if (next == null) {
            synchronized(gate) {
                if (!historyBlocked) {
                    rawCleanupFailure = null
                    rawProofFailure = null
                    if (!ruleset && retryPending) resourcesStep = Step.NOT_ATTEMPTED
                }
            }
            return true
        }
        val previous = synchronized(gate) { failure }
        val primary = if (next is Error && previous !is Error) next else previous ?: next
        synchronized(gate) { failure = primary }
        val merged = CleanupFailures.merge(previous, next)
        val represented =
            previous == null ||
                previous === next ||
                (merged === previous && merged.suppressed.any { it === next }) ||
                (merged === next && merged.suppressed.any { it === previous })
        if (!represented) updateCleanup(ruleset, Step.UNKNOWN, null, true)
        return represented
    }

    private fun beforeCleanupDispatch(ruleset: Boolean) {
        if (ruleset) {
            if (claimed && request.operation?.isDirectResult() == true) {
                checkNotNull(request.operation).awaitDirectValidators(DirectClaimAction.ROLLBACK_RULESET)
            }
            if (hasRuleset) effects.beforeRollbackDispatch()
        } else {
            if (!claimed && localResources == null && managedCapture != null) effects.beforeRollbackDispatch()
            if (claimed && request.operation?.isDirectResult() == true) {
                checkNotNull(request.operation).requireDirectRetirementReady(DirectClaimAction.ROLLBACK_RULESET)
            }
        }
    }

    private fun dispatchCleanup(ruleset: Boolean) {
        if (ruleset) {
            if (claimed && request.operation?.isDirectResult() == true) {
                landlock?.closeDirect(DirectClaimAction.ROLLBACK_RULESET)
            } else {
                landlock?.closeOwnedRuleset()
            }
        } else {
            if (claimed) {
                if (request.operation?.isDirectResult() == true) request.operation.retireDirect(DirectClaimAction.ROLLBACK_RULESET) else request.operation?.retire()
            } else {
                val resources = localResources
                if (resources != null) resources.close() else managedCapture?.closeOwned()
            }
        }
    }

    private object CleanupDecisions {
        fun proofRequired(
            pending: Boolean,
            dispatched: Boolean,
        ): Boolean = pending && dispatched

        fun retryPending(
            pending: Boolean,
            dispatched: Boolean,
            retained: Boolean,
            proof: Throwable?,
        ): Boolean = pending && dispatched && retained && proof == null

        fun firstFatal(
            previous: Throwable?,
            problem: Throwable,
            proof: Throwable?,
        ): Error? = (previous as? Error) ?: (problem as? Error) ?: (proof as? Error)
    }

    private fun cleanup() {
        requireManager()
        val first = runCleanupStage(true)
        val second = runCleanupStage(false)
        if (first != null) throw first
        if (second != null) throw second
    }

    @Suppress("TooGenericExceptionCaught") // Preserve the original failure and disposition before fallible reconciliation.
    private fun runCleanupStage(ruleset: Boolean): Error? {
        if (!reserveCleanup(ruleset)) return null
        var dispatched = false
        var problem: Throwable? = null
        var directPending = false
        try {
            beforeCleanupDispatch(ruleset)
            dispatched = true
            dispatchCleanup(ruleset)
            updateCleanup(ruleset, Step.COMPLETE, null, false)
        } catch (pending: DirectPendingException) {
            updateCleanup(ruleset, if (dispatched) Step.UNKNOWN else Step.NOT_ATTEMPTED, pending, false)
            problem = pending
            directPending = !ruleset
        } catch (other: Throwable) {
            updateCleanup(ruleset, if (dispatched) Step.UNKNOWN else Step.NOT_ATTEMPTED, other, false)
            problem = other
        }
        if (problem == null) return null
        var retainedPending = false
        var proofFailure: Throwable? = null
        try {
            if (CleanupDecisions.proofRequired(directPending, dispatched)) retainedPending = request.operation?.directRetirementPending() == true
        } catch (proof: Throwable) {
            synchronized(gate) { rawProofFailure = proof }
            proofFailure = proof
        }
        val previous = synchronized(gate) { failure }
        var fatal = CleanupDecisions.firstFatal(previous, problem, proofFailure)
        try {
            if (reconcileFailure(problem, ruleset, false) && proofFailure != null) reconcileFailure(proofFailure, ruleset, false)
            reconcileFailure(null, ruleset, CleanupDecisions.retryPending(directPending, dispatched, retainedPending, proofFailure))
        } catch (metadata: Throwable) {
            updateCleanup(ruleset, Step.UNKNOWN, metadata, true)
            if (fatal == null) fatal = metadata as? Error
        }
        return fatal
    }
}

private fun checkScopingDrainEffects(
    state: ScopingPreparation.State,
    effects: List<ScopingPreparation.Effect>,
) {
    when (state) {
        is ScopingPreparation.State.Claiming -> check(effects.size == 1 && effects[0] === ScopingPreparation.Effect.ObserveClaim)
        is ScopingPreparation.State.DomainOwned -> check(effects.size == 1 && effects[0] === ScopingPreparation.Effect.ObserveDomain)
        is ScopingPreparation.State.Constructing, is ScopingPreparation.State.Ready,
        is ScopingPreparation.State.Retiring, is ScopingPreparation.State.PreparedSettled,
        is ScopingPreparation.State.TransferredSettled,
        -> check(effects.isEmpty())
    }
}

private fun scopingDrainPending(
    preparation: ScopingPreparation,
    state: ScopingPreparation.State,
): Future<*>? =
    when (state) {
        is ScopingPreparation.State.Constructing -> preparation.constructionFinished
        is ScopingPreparation.State.Claiming -> preparation.claimFinished
        is ScopingPreparation.State.Ready, is ScopingPreparation.State.Retiring,
        is ScopingPreparation.State.PreparedSettled, is ScopingPreparation.State.DomainOwned,
        is ScopingPreparation.State.TransferredSettled,
        -> null
    }

private fun checkClaimMayProceed(
    preparation: ScopingPreparation,
    carrier: ScopingPreparation.ClaimProgress,
    runtimeOpen: Boolean,
) {
    val state = preparation.state as? ScopingPreparation.State.Claiming
    check(state != null && state.acquisition === carrier) { "Scoping claim is no longer active" }
    val failed = state.request as? ScopingPreparation.PrepareRequest.Failed
    if (failed != null) throw failed.cause
    requireRuntimeOpen(runtimeOpen)
}

private fun retainHelperDrainFailure(
    lock: Any,
    retained: AtomicReference<Throwable?>,
    failure: Throwable,
) {
    synchronized(lock) {
        retained.set(CleanupFailures.merge(retained.get(), failure))
    }
}

private fun canRetryConfined(
    resource: OwnedDomain,
    retry: Boolean,
    liveRetryEligible: Set<OwnedDomain>,
): Boolean =
    if (resource.isLiveScoping) {
        resource in liveRetryEligible && resource.initialLiveRetirementEnded()
    } else {
        retry && resource.initialConfinedRetirementEnded()
    }

private fun mayStopConfinedManager(
    domains: Collection<OwnedDomain>,
    scoping: ScopingPreparation?,
    manager: java.util.concurrent.ExecutorService,
): Boolean =
    domains.all { it.retired() } &&
        (
            scoping == null ||
            scoping.state is ScopingPreparation.State.PreparedSettled ||
            scoping.state is ScopingPreparation.State.TransferredSettled
        ) &&
        !manager.isShutdown

private fun observeScopingInvalidation(
    progress: ScopingPreparation.Retained,
    outcome: io.mazewall.platform.native.LiveCompletion,
): ScopingPreparation.Retained =
    when (outcome) {
        io.mazewall.platform.native.LiveCompletion.Complete -> progress.copy(step = ScopingPreparation.ReleaseStage.RETIRE, nativeOutcome = null)
        is io.mazewall.platform.native.LiveCompletion.Pending -> progress.copy(nativeOutcome = outcome)
        is io.mazewall.platform.native.LiveCompletion.Refused -> progress.copy(
            step = ScopingPreparation.ReleaseStage.NATIVE_REFUSED,
            nativeOutcome = outcome,
            failure = CleanupFailures.merge(progress.failure, IllegalStateException("Native invalidation refused: ${outcome.failure}", outcome.failure.cause)),
        )
    }

private fun observeScopingRetirement(
    progress: ScopingPreparation.Retained,
    outcome: io.mazewall.platform.native.LiveCompletion,
): ScopingPreparation.Retained =
    when (outcome) {
        io.mazewall.platform.native.LiveCompletion.Complete -> progress.copy(step = ScopingPreparation.ReleaseStage.RESOURCES, nativeOutcome = null)
        is io.mazewall.platform.native.LiveCompletion.Pending -> progress.copy(nativeOutcome = outcome)
        is io.mazewall.platform.native.LiveCompletion.Refused -> progress.copy(
            step = ScopingPreparation.ReleaseStage.NATIVE_REFUSED,
            nativeOutcome = outcome,
            failure = CleanupFailures.merge(progress.failure, IllegalStateException("Native retirement refused: ${outcome.failure}", outcome.failure.cause)),
        )
    }

@Suppress("TooGenericExceptionCaught") // Preserve original fatal, Unknown, cause and interruption history.
private fun releaseCarrierHelper(initial: ScopingPreparation.Retained): Pair<ScopingPreparation.Retained, Error?> {
    if (initial.step != ScopingPreparation.ReleaseStage.RESOURCES || initial.helperStep != ScopingPreparation.ReleaseStep.NotAttempted) return initial to null
    return try {
        initial.helperCapture?.close()
        initial.copy(helperStep = ScopingPreparation.ReleaseStep.Complete) to null
    } catch (fatal: Error) {
        initial.copy(
            step = ScopingPreparation.ReleaseStage.RESOURCES_UNKNOWN,
            helperStep = ScopingPreparation.ReleaseStep.Unknown,
            failure = CleanupFailures.merge(initial.failure, fatal),
        ) to fatal
    } catch (failure: Throwable) {
        initial.copy(
            step = ScopingPreparation.ReleaseStage.RESOURCES_UNKNOWN,
            helperStep = ScopingPreparation.ReleaseStep.Unknown,
            failure = CleanupFailures.merge(initial.failure, failure),
        ) to null
    }
}

private fun canReleaseScopingLandlock(progress: ScopingPreparation.Retained): Boolean =
    progress.step == ScopingPreparation.ReleaseStage.RESOURCES &&
        progress.landlockStep == ScopingPreparation.ReleaseStep.NotAttempted
