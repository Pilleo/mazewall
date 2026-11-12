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
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.fd.ResourceRetirement
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

/** Fixed physical generation; only its trusted bootstrap thread can construct workers. */
internal class OwnedDomain(
    val generation: Long,
    val limits: DomainLimits,
    private val policy: PolicyDefinition<*>,
    private val environment: InstallationEnvironment,
    private val preparedLandlock: io.mazewall.landlock.PreparedLandlock?,
    private val preparedOperation: io.mazewall.enforcer.api.execution.PreparedOperationResources?,
    name: String,
    private val scoping: FrozenStackScoping,
    private val confined: ConfinedHelperConfiguration? = null,
) {
    private enum class HelperRelease { Retained, Releasing, Released }

    private val lock = Any()
    private var liveRelease: LiveRelease? = if (scoping is FrozenStackScoping.Live) LiveRelease() else null
    private var phase = GenerationPhase.PREPARING
    private var failure: Throwable? = null
    private val bootstrap = Thread.currentThread()
    private val helperPreparationEnded = CountDownLatch(1)
    private var helperPlan: PreparedHelperReadPlan? = null
    private var helperSession: ConfinedHelperSession? = null
    private var helperRelease = if (confined == null) HelperRelease.Released else HelperRelease.Retained
    private var helperPinsClosed = false
    private var operationClosed = false
    private var landlockClosed = false
    private var directRetiring = false
    private var directReleased = false
    private var directRulesetComplete = false
    private val startup = CountDownLatch(limits.workers)
    private val threads = CopyOnWriteArrayList<Thread>()
    private val pending = ConcurrentHashMap.newKeySet<OwnedInvocation<*>>()
    private val admission = Semaphore(limits.maxAdmissions)
    private val admitted = AtomicLong()
    private val rejected = AtomicLong()
    private val cancellationRequests = AtomicLong()
    private val delivered = AtomicLong()
    private val ended = AtomicLong()
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
            threadBuilder.unstarted { runWorker(runnable) }.also { threads.add(it) }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val retirement = if (preparedLandlock != null || preparedOperation != null || confined != null) {
        PreparedRetirement(name + "resource-retirement", {
            awaitPhysicalWorkers(confined, helperPreparationEnded, executor, threads)
        }, ::releasePreparedResources, { recordCleanupFailure(cleanupFailure, it) })
    } else {
        null
    }

    internal fun ownsScoping(expected: FrozenStackScoping.Live): Boolean = scoping === expected

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
        afterDomainCommit()
        requireSupportedScopingStart(scoping)
        prepareHelper(deadline)
        validateResources()
        val started = executor.prestartAllCoreThreads()
        // Early failure can stop the executor before the remaining workers are created.
        repeat((limits.workers - started).coerceAtLeast(0)) { startup.countDown() }
        if (!startup.await(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) fail(TimeoutException("Required worker preparation did not finish"))
        val problem = synchronized(lock) {
            phase = GenerationLifecycle.evaluate(phase, GenerationEvent.PREPARED)
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
    private fun runWorker(runnable: Runnable) {
        var receipt: InstallationReceipt? = null
        var terminal: Throwable? = null
        RuntimeWorkerContext.enter(this, scoping)
        try {
            try {
                requireSupportedScopingStart(scoping)
                if (confined != null) requireHelperReady(ConfinedDeadline.mint(environment.native) { HELPER_TIMEOUT_NANOS })
                receipt = ContainedExecutors.installOnCurrentThread(policy, scoping, environment, preparedLandlock)
                receipt.requireInstalled()
            } catch (installationFailure: Throwable) {
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
        synchronized(lock) {
            if (phase != GenerationPhase.READY) {
                recordRejection()
                throw RejectedExecutionException("Domain generation is not admitting", failure)
            }
            if (!admission.tryAcquire()) {
                recordRejection()
                throw RejectedExecutionException("Domain admission limit reached")
            }
            task = OwnedInvocation(this, action)
            pending.add(task)
            admitted.incrementAndGet()
            try {
                executor.execute(task)
            } catch (rejected: RejectedExecutionException) {
                recordRejection()
                task.generationFailed(rejected)
                fail(rejected)
            } catch (replacementRefused: IllegalStateException) {
                task.generationFailed(replacementRefused)
                fail(replacementRefused)
            }
        }
        return task
    }

    fun fail(problem: Throwable) {
        val first = synchronized(lock) {
            val recorded = failure ?: problem
            failure = recorded
            phase = GenerationLifecycle.evaluate(phase, GenerationEvent.FAILED)
            recorded
        }
        executor.shutdownNow()
        retirement?.request()
        pending.toList().forEach { it.generationFailed(first) }
    }

    fun beginDrain() {
        synchronized(lock) {
            phase = GenerationLifecycle.evaluate(phase, GenerationEvent.DRAIN)
        }
        executor.shutdown()
        retirement?.request()
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

    private fun prepareHelper(deadline: Deadline) {
        try {
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
            val plan = PreparedHelperReadPlan.capture(configuration.policy, resources, binding, configuration.support, configuration.lifetime, operation.native, parent.ancestry)
            synchronized(lock) {
                helperPlan = plan
                check(phase == GenerationPhase.PREPARING) { "Confined preparation was stopped before setup" }
            }
            val result = SupervisorInstaller.prepareConfinedHelper(
                plan,
                configuration.context,
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
                observations = configuration.observations,
            )
            when (result) {
                is ConfinedHelperPreparation.Ready -> check(result.binding == plan.manifest.binding && result.session === helperSession)
                is ConfinedHelperPreparation.Rejected -> throw result.cause
            }
        } finally {
            helperPreparationEnded.countDown()
        }
    }

    private fun requireHelperReady(deadline: ConfinedDeadline) {
        if (confined == null) return
        val owners = synchronized(lock) { requireNotNull(helperPlan) to requireNotNull(helperSession) }
        owners.second.requireReady(owners.first.manifest.binding, deadline)
    }

    fun initialConfinedRetirementEnded(): Boolean = (confined != null || scoping is FrozenStackScoping.Live) && physicalWorkersStopped(executor, threads) && retirement?.finished() == true

    internal val isLiveScoping: Boolean get() = scoping is FrozenStackScoping.Live

    fun initialLiveRetirementEnded(): Boolean = scoping is FrozenStackScoping.Live && initialConfinedRetirementEnded()

    fun retryConfinedRetirement(deadline: ConfinedDeadline) {
        check(initialConfinedRetirementEnded()) { "Initial helper retirement still owns cleanup" }
        if (scoping is FrozenStackScoping.Live) releaseLiveResources() else releaseConfinedResources(deadline)
        retireIfStopped()
    }

    @Suppress("TooGenericExceptionCaught") // Commit exact Unknown and history before propagating any live cleanup failure.
    private fun releaseLiveResources() {
        val live = scoping as FrozenStackScoping.Live
        var progress = synchronized(lock) {
            val current = checkNotNull(liveRelease)
            if (current.pass == ScopingPreparation.ReleasePass.Releasing ||
                current.failure != null ||
                current.nativeOutcome is io.mazewall.platform.native.LiveCompletion.Refused ||
                listOf(current.invalidate, current.installation, current.landlock, current.resources).contains(ScopingPreparation.ReleaseStep.Unknown)
            ) {
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
            if (progress.installation == ScopingPreparation.ReleaseStep.NotAttempted) {
                progress = progress.copy(installation = ScopingPreparation.ReleaseStep.Unknown)
                val outcome = owner.capability.retireInstallation(owner)
                progress = observeLiveRetirement(progress, outcome)
                if (outcome !is io.mazewall.platform.native.LiveCompletion.Complete) return
            }
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
                    helperRelease = HelperRelease.Releasing
                    true
                }
            }
        }
        if (!claimed) return
        try {
            helperSession?.retire(deadline)
            if (!helperPinsClosed) {
                helperPlan?.close()
                helperPinsClosed = true
            }
            if (!operationClosed) {
                preparedOperation?.retire()
                operationClosed = true
            }
            if (!landlockClosed) {
                preparedLandlock?.close()
                landlockClosed = true
            }
            synchronized(lock) { helperRelease = HelperRelease.Released }
        } catch (failure: Throwable) {
            recordCleanupFailure(cleanupFailure, failure)
            synchronized(lock) { helperRelease = HelperRelease.Retained }
            throw failure
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

    fun recordCancellation() {
        cancellationRequests.incrementAndGet()
    }

    fun recordDelivery() {
        delivered.incrementAndGet()
    }

    fun recordEnd() {
        ended.incrementAndGet()
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
            InvocationDiagnostics(admitted.get(), rejected.get(), cancellationRequests.get(), delivered.get(), ended.get()),
            liveWorkers(),
            pending.size,
            phase == GenerationPhase.CLOSED && retired() && !cleanupFailed && pending.isEmpty(),
        )
    }

    fun removeQueued(task: OwnedInvocation<*>) {
        executor.remove(task)
    }

    fun finished(task: OwnedInvocation<*>) {
        check(pending.remove(task)) { "Invocation admission already released" }
        admission.release()
    }
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
    confined: ConfinedHelperConfiguration?,
    helperPreparationEnded: CountDownLatch,
    executor: ThreadPoolExecutor,
    threads: List<Thread>,
) {
    var interrupted = Thread.interrupted()
    try {
        while (confined != null && helperPreparationEnded.count != 0L) {
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
)

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
