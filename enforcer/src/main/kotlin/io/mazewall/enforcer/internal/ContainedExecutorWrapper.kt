package io.mazewall.enforcer.internal

import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import io.mazewall.enforcer.state.ContainmentStateRegistry
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Internal wrapper that applies containment policies before task execution.
 *
 * ### Graceful Shutdown
 * Users of this wrapper should prefer [shutdown] and [awaitTermination] over [shutdownNow].
 * Aggressive interruption via `shutdownNow()` can occur during the delicate FFM seccomp
 * installation sequence. Although the implementation ensures that the [ContainmentStateRegistry]
 * remains synchronized with the kernel's filter state even upon interruption, it is better
 * to allow the handshake to complete naturally.
 *
 * Concurrently modifying global process-wide security policies (e.g., calling `ContainedExecutors.installOnProcess`)
 * while tasks are executing on this wrapped executor or during its shutdown phase is unsupported and
 * can lead to unpredictable sandbox states.
 *
 * The seccomp filter is installed per-task immediately before [task.call] (or [task.run]).
 * This design is required for correctness because BPF filters are thread-scoped. In a
 * multi-thread executor (e.g. [java.util.concurrent.ThreadPoolExecutor]), each platform thread
 * that runs a task must have the filter installed on itself. Submitting a single install
 * task at construction only covers the one thread that happens to run it; other pool
 * threads remain unfiltered.
 *
 * ### Permanent installation and task registration
 *
 * Installation state survives both task completion and partial installation failures.
 * Each task borrows registration in the installed filter's runtime; nested tasks release
 * only their own lease. The installing platform thread owns listener retirement.
 * Classloading frames do not grant arbitrary path authority: the supervisor's bounded
 * JVM-path rules and explicit scoping decisions remain authoritative on every task.
 */
internal class ContainedExecutorWrapper(
    private val delegate: ExecutorService,
    private val policy: PolicyDefinition<*>,
    private val scopingPolicy: StacktraceScopingPolicy = io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy,
) : ExecutorService by delegate {
    private fun <T> wrapCallable(task: Callable<T>): Callable<T> = Callable { runContained(task::call) }

    private fun wrapRunnable(task: Runnable): Runnable = Runnable { runContained(task::run) }

    private fun <T> runContained(task: () -> T): T {
        var receipt: io.mazewall.enforcer.api.InstallationReceipt? = null
        try {
            receipt = ContainedExecutors.installOnCurrentThread(policy, scopingPolicy)
            receipt.requireInstalled()
            return runTask(task)
        } finally {
            receipt?.close()
        }
    }

    private fun <T> runTask(task: () -> T): T = runCatching(task).getOrElse { throw failureForCaller(it) }

    private fun failureForCaller(failure: Throwable): Throwable = (failure as? Exception)?.asContainmentViolation() ?: failure

    private fun Exception.asContainmentViolation(): Exception {
        val diagnostic = ContainmentViolationDetector.diagnose(this) ?: return this
        val structured = diagnostic.violation
        if (this === structured) return structured
        return ContainmentViolationException(
            message = "Task reported a containment policy violation",
            cause = this,
            errno = structured?.errno,
            syscallNr = structured?.syscallNr,
            evidence = diagnostic.evidence,
        )
    }

    override fun execute(command: Runnable) {
        delegate.execute(wrapRunnable(command))
    }

    override fun <T> submit(task: Callable<T>): Future<T> = delegate.submit(wrapCallable(task))

    override fun <T> submit(
        task: Runnable,
        result: T,
    ): Future<T> = delegate.submit(wrapRunnable(task), result)

    override fun submit(task: Runnable): Future<*> = delegate.submit(wrapRunnable(task))

    override fun <T> invokeAll(tasks: Collection<Callable<T>>): List<Future<T>> = delegate.invokeAll(tasks.map { wrapCallable(it) })

    override fun <T> invokeAll(
        tasks: Collection<Callable<T>>,
        timeout: Long,
        unit: TimeUnit,
    ): List<Future<T>> = delegate.invokeAll(tasks.map { wrapCallable(it) }, timeout, unit)

    override fun <T> invokeAny(tasks: Collection<Callable<T>>): T = delegate.invokeAny(tasks.map { wrapCallable(it) })

    override fun <T> invokeAny(
        tasks: Collection<Callable<T>>,
        timeout: Long,
        unit: TimeUnit,
    ): T = delegate.invokeAny(tasks.map { wrapCallable(it) }, timeout, unit)

    override fun close() {
        delegate.close()
    }
}
