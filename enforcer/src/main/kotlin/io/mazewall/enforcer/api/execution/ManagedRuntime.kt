package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy
import io.mazewall.enforcer.api.InstallationEnvironment
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.ReviewedOperationArtifact
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.api.Uncompiled
import io.mazewall.enforcer.api.frozenCopy
import io.mazewall.enforcer.diagnostics.requirePlatformThread
import io.mazewall.enforcer.internal.containmentFailureForCaller
import io.mazewall.enforcer.internal.execution.Deadline
import io.mazewall.enforcer.internal.execution.ExecutionTransition
import io.mazewall.enforcer.internal.execution.ExecutionTransitionGuard
import io.mazewall.enforcer.internal.execution.OwnedDomain
import io.mazewall.enforcer.internal.execution.RuntimeWorkerContext
import io.mazewall.enforcer.state.ContainmentStateRegistry
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
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
) : AutoCloseable {
    private enum class Phase { OPEN, DRAINING, CLOSED }

    private val lock = Any()
    private var phase = Phase.OPEN
    private val id = identities.incrementAndGet()
    private var nextGeneration = 0L
    private val domains = LinkedHashMap<Long, OwnedDomain>()
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

    public companion object {
        private val identities = AtomicLong()

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
            require(!baseline.definition.enforceLandlock) { "The host baseline must have process-safe scope" }
            require(baseline.allowMmapExec == runtime.allowsExecutableMappings) { "Process mapping floor does not match the declared runtime" }
            val owner = ManagedRuntime(runtime, InstallationEnvironment.capture(Platform.FallbackBehavior.FAIL), preparationTimeout, closeTimeout)
            try {
                owner.await(
                    owner.manager.submit {
                    owner.environment.platform.capabilities
                    ContainedExecutors.installOnProcess(baseline.definition.frozenCopy(), owner.environment).use { it.requireInstalled() }
                },
                )
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

    /** Prepare configured review metadata. Actual implementation identity requires the resource-owner overload. */
    public fun prepare(
        artifact: ReviewedOperationArtifact,
        limits: DomainLimits,
    ): InProcessDomain {
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
        val worker = artifact.document.policy as? CanonicalPolicyArtifact.Worker
            ?: throw IllegalArgumentException("In-process domains require worker policy artifacts")
        require(worker.runtime == runtime) { "Reviewed operation runtime does not match owner" }
        val domain = prepareDefinition(worker.policy.definition.frozenCopy(), limits, artifact, resources)
        return InProcessDomain(this, domain.resource, artifact.binding)
    }

    @Suppress("TooGenericExceptionCaught") // preparation boundary retires its generation and preserves original failure
    public fun prepare(
        policy: Policy<*, Uncompiled>,
        limits: DomainLimits,
    ): InProcessDomain = prepareDefinition(policy.definition.frozenCopy(), limits)

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
        requireUnrestrictedBootstrap()
        val handlers = java.util.Map.copyOf(scopingPolicy.handlers)
        val captured = object : StacktraceScopingPolicy {
            override val handlers = handlers
        }
        return prepareDefinition(policy.definition.frozenCopy(), limits, scopingPolicy = captured)
    }

    @Suppress("TooGenericExceptionCaught") // preparation boundary retains manager-owned resources until physical retirement
    private fun prepareDefinition(
        definition: PolicyDefinition<*>,
        limits: DomainLimits,
        artifact: ReviewedOperationArtifact? = null,
        resources: PreparedOperationResources? = null,
        scopingPolicy: StacktraceScopingPolicy = DefaultStacktraceScopingPolicy,
    ): InProcessDomain {
        requireUnrestrictedBootstrap()
        val resourceRef = AtomicReference<OwnedDomain?>()
        val prepared = synchronized(lock) {
            requireOpen()
            manager.submit(Callable { prepareOnManager(definition, limits, resourceRef, artifact, resources, scopingPolicy) })
        }
        return try {
            await(prepared)
        } catch (preparationFailure: Exception) {
            resourceRef.get()?.fail(preparationFailure)
            throw preparationFailure
        } catch (preparationFatal: Error) {
            resourceRef.get()?.fail(preparationFatal)
            throw preparationFatal
        }
    }

    @Suppress("TooGenericExceptionCaught") // manager resource boundary retires preparation on every original failure
    private fun prepareOnManager(
        definition: PolicyDefinition<*>,
        limits: DomainLimits,
        resourceRef: AtomicReference<OwnedDomain?>,
        artifact: ReviewedOperationArtifact?,
        resources: PreparedOperationResources?,
        scopingPolicy: StacktraceScopingPolicy,
    ): InProcessDomain {
        var claimed = false
        var preparedLandlock: io.mazewall.landlock.PreparedLandlock? = null
        val resource = try {
            synchronized(lock) { domains.values.toList() }.forEach { it.retireIfStopped() }
            resources?.let {
                val reviewed = checkNotNull(artifact)
                it.claim(reviewed.binding, reviewed.document.policy as CanonicalPolicyArtifact.Worker, environment.native, environment.platform.capabilities.matrix.landlockAbiVersion)
                claimed = true
            }
            preparedLandlock = io.mazewall.landlock.PreparedLandlock
                .prepare(definition, environment, resources?.resources)
            check(!Thread.currentThread().isInterrupted) { "Domain preparation was cancelled" }
            synchronized(lock) {
            requireOpen()
            check(domains.values.none { it.failedRetirementPending() || it.cleanupFailure.get() != null }) { "Prior failed generation still has live execution or cleanup" }
            val generation = ++nextGeneration
            OwnedDomain(generation, limits, definition, environment, preparedLandlock, resources, "mazewall-domain-$id-$generation-", scopingPolicy).also {
                domains[generation] = it
                resourceRef.set(it)
            }
        }
        } catch (failure: Throwable) {
            var primary = failure
            try {
                preparedLandlock?.close()
            } catch (cleanup: Throwable) {
                primary = io.mazewall.platform.fd.CleanupFailures
                    .merge(primary, cleanup)
            }
            if (claimed) {
                try {
                resources?.retire()
            } catch (cleanup: Throwable) {
                    primary = io.mazewall.platform.fd.CleanupFailures
                        .merge(primary, cleanup)
            }
            }
            throw primary
        }
        return try {
            resource.prepare(Deadline(preparationTimeout))
            synchronized(lock) { requireOpen() }
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
        requireOpen(domain.resource)
        if (RuntimeWorkerContext.current != null) {
            domain.resource.recordRejection()
            throw RejectedExecutionException("Nested dispatch requires an approved execution transition")
        }
        if (!domain.resource.needsResourceValidation()) return domain.resource.submit(action)
        manager.submit(Callable { domain.resource.validateResources() })
        }
        try {
            await(validation)
        } catch (failure: Throwable) {
            domain.resource.fail(failure)
            throw failure
        }
        return synchronized(lock) {
            requireOpen(domain.resource)
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

    private fun rejectTransition(): Nothing {
        val failure = ExecutionTransitionException("Requested domain transition is not approved for this active scope")
        RuntimeWorkerContext.invocation?.recordObservedFailure(failure)
        throw failure
    }

    @Suppress("TooGenericExceptionCaught") // nested body boundary uses canonical classification and retains observed enforcement failure
    private fun <T> callInline(action: Callable<T>): T = try {
        action.call()
    } catch (bodyFailure: Throwable) {
        val classified = containmentFailureForCaller(bodyFailure)
        if (classified is ContainmentViolationException) RuntimeWorkerContext.invocation?.recordObservedFailure(classified)
        throw classified
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

    public fun drain(timeout: Duration): DrainReport {
        check(RuntimeWorkerContext.current == null) { "An owned worker cannot drain itself" }
        val deadline = Deadline(timeout)
        val resources = synchronized(lock) {
            if (phase == Phase.CLOSED) return DrainReport.Complete
            phase = Phase.DRAINING
            domains.values.toList()
        }
        manager.shutdownNow()
        resources.forEach { it.beginDrain() }
        try {
            resources.forEach { it.awaitRetirement(deadline) }
            manager.awaitTermination(deadline.remainingNanos(), TimeUnit.NANOSECONDS)
            managerThread.get()?.let { deadline.join(it) }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // The manager can publish rollback outcomes too: establish its physical end
        // before collecting terminal domain/failure observations. Never reuse an
        // earlier null failure alongside a later successful retirement observation.
        val managerLive = managerThread.get()?.isAlive == true
        val managerStopped = !managerLive && manager.isTerminated
        val outcome = io.mazewall.enforcer.internal.execution.DrainLifecycle
            .evaluate(managerStopped, managerLive, resources.map { it.drainSnapshot() })
        if (outcome == DrainReport.Complete) {
            synchronized(lock) { phase = Phase.CLOSED }
        }
        return outcome
    }

    override fun close() {
        when (val report = drain(closeTimeout)) {
            DrainReport.Complete -> Unit
            is DrainReport.Incomplete -> throw IncompleteDrainException(report)
        }
    }

    private fun requireOpen(rejectedDomain: OwnedDomain? = null) {
        if (phase != Phase.OPEN) {
            rejectedDomain?.recordRejection()
            throw RejectedExecutionException("Runtime is not admitting")
        }
    }

    private fun <T> await(future: Future<T>): T =
        try {
        future.get(preparationTimeout.toNanos(), TimeUnit.NANOSECONDS)
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
}
