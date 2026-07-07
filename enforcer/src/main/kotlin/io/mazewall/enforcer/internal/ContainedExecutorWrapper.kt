package io.mazewall.enforcer.internal

import io.mazewall.PolicyDefinition
import io.mazewall.enforcer.ContainedExecutors
import io.mazewall.enforcer.ContainmentViolationDetector
import io.mazewall.enforcer.ContainmentViolationException
import io.mazewall.enforcer.supervisor.StacktraceScopingPolicy
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
 * installation sequence. Although the implementation ensures that the [ThreadStateRegistry]
 * remains synchronized with the kernel's filter state even upon interruption, it is better
 * to allow the handshake to complete naturally.
 *
 * The seccomp filter is installed per-task immediately before [task.call] (or [task.run]).
 * This design is required for correctness because BPF filters are thread-scoped. In a
 * multi-thread executor (e.g. [java.util.concurrent.ThreadPoolExecutor]), each platform thread
 * that runs a task must have the filter installed on itself. Submitting a single install
 * task at construction only covers the one thread that happens to run it; other pool
 * threads remain unfiltered.
 *
 * ### Classloader bypass and per-task install
 *
 * The supervisor's [io.mazewall.enforcer.supervisor.JvmStackInspector] unconditionally allows
 * any intercepted syscall while the JVM ClassLoader lock is held by the sandboxed thread. This
 * prevents the well-known deadlock where the supervisor thread cannot load classes (the lock
 * is held) while the sandboxed thread is blocked waiting for the supervisor response.
 *
 * As a consequence, the *first* execution of user code on a given thread may trigger lazy
 * class loading, during which any `openat` (including one to a user file) that coincides with
 * an active classloader frame is allowed without consulting the scoping policy. This is the
 * correct and intended behavior: the bypass breaks the deadlock, and the file access is
 * allowed. The scoping policy is correctly consulted for **all subsequent calls** on that
 * thread once the relevant classes are loaded.
 *
 * **Security implication**: the library guarantees that unauthorized syscalls are DENIED.
 * It does not guarantee that every authorized syscall is routed through the scoping policy —
 * those that coincide with class loading are bypassed. Tests must reflect this contract:
 * verify that evil access is denied (reliable, class-load-independent), not that an internal
 * tracking structure is populated for allowed access (unreliable on first execution).
 */
internal class ContainedExecutorWrapper(
    private val delegate: ExecutorService,
    private val policy: PolicyDefinition<*>,
    private val scopingPolicy: StacktraceScopingPolicy = io.mazewall.enforcer.supervisor.DefaultStacktraceScopingPolicy,
) : ExecutorService by delegate {
    private fun <T> wrapCallable(task: Callable<T>): Callable<T> =
        Callable {
            ContainedExecutors.installOnCurrentThread(policy, scopingPolicy).use {
                val result = runCatching { task.call() }
                result.getOrElse { e ->
                    System.err.println("Task execution failed with exception:")
                    e.printStackTrace()
                    if (e is Exception && ContainmentViolationDetector.isContainmentViolation(e)) {
                        throw ContainmentViolationException("Task violated containment policy", e)
                    }
                    throw e
                }
            }
        }

    private fun wrapRunnable(task: Runnable): Runnable =
        Runnable {
            ContainedExecutors.installOnCurrentThread(policy, scopingPolicy).use {
                val result = runCatching { task.run() }
                result.onFailure { e ->
                    if (e is Exception && ContainmentViolationDetector.isContainmentViolation(e)) {
                        throw ContainmentViolationException("Task violated containment policy", e)
                    }
                    throw e
                }
            }
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
