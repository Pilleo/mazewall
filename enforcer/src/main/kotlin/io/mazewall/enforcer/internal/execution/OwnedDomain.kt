package io.mazewall.enforcer.internal.execution

import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy
import io.mazewall.enforcer.api.InstallationEnvironment
import io.mazewall.enforcer.api.InstallationReceipt
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.InvocationHandle
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
import java.util.concurrent.atomic.AtomicReference

/** Fixed physical generation; only its trusted bootstrap thread can construct workers. */
internal class OwnedDomain(
    val generation: Long,
    val limits: DomainLimits,
    private val policy: PolicyDefinition<*>,
    private val environment: InstallationEnvironment,
    name: String,
) {
    private val lock = Any()
    private var phase = GenerationPhase.PREPARING
    private var failure: Throwable? = null
    private val bootstrap = Thread.currentThread()
    private val startup = CountDownLatch(limits.workers)
    private val threads = CopyOnWriteArrayList<Thread>()
    private val pending = ConcurrentHashMap.newKeySet<OwnedInvocation<*>>()
    private val admission = Semaphore(limits.maxAdmissions)
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

    fun prepare(deadline: Deadline) {
        executor.prestartAllCoreThreads()
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
    }

    @Suppress("TooGenericExceptionCaught") // startup/retirement boundary preserves fatal identity and owns executor bookkeeping
    private fun runWorker(runnable: Runnable) {
        var receipt: InstallationReceipt? = null
        var terminal: Throwable? = null
        RuntimeWorkerContext.enter(this)
        try {
            try {
                receipt = ContainedExecutors.installOnCurrentThread(policy, DefaultStacktraceScopingPolicy, environment)
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
            if (phase != GenerationPhase.READY) throw RejectedExecutionException("Domain generation is not admitting", failure)
            if (!admission.tryAcquire()) throw RejectedExecutionException("Domain admission limit reached")
            task = OwnedInvocation(this, action)
            pending.add(task)
            try {
                executor.execute(task)
            } catch (rejected: RejectedExecutionException) {
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
        pending.toList().forEach { it.generationFailed(first) }
    }

    fun beginDrain() {
        synchronized(lock) {
            phase = GenerationLifecycle.evaluate(phase, GenerationEvent.DRAIN)
        }
        executor.shutdown()
    }

    fun awaitRetirement(deadline: Deadline) {
        executor.awaitTermination(deadline.remainingNanos(), TimeUnit.NANOSECONDS)
        threads.forEach { deadline.join(it) }
        if (retired()) synchronized(lock) { phase = GenerationLifecycle.evaluate(phase, GenerationEvent.RETIRED) }
    }

    fun retired(): Boolean = executor.isTerminated && threads.none { it.isAlive }

    fun permitsInlineContinuation(): Boolean =
        synchronized(lock) {
        failure == null && (phase == GenerationPhase.READY || phase == GenerationPhase.DRAINING)
    }

    fun failedRetirementPending(): Boolean = synchronized(lock) { failure != null && !retired() }

    fun liveWorkers(): Int = threads.count { it.isAlive }

    fun unfinished(): Int = pending.size

    fun removeQueued(task: OwnedInvocation<*>) {
        executor.remove(task)
    }

    fun finished(task: OwnedInvocation<*>) {
        check(pending.remove(task)) { "Invocation admission already released" }
        admission.release()
    }
}
