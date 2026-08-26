@file:JvmName("Mazewall")
package io.mazewall

import io.mazewall.core.Syscall
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.SandboxDispatcher
import io.mazewall.enforcer.supervisor.DefaultStacktraceScopingPolicy
import io.mazewall.enforcer.supervisor.StacktraceScopingPolicy
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Supplier

/**
 * Public Java and Kotlin entry point facade for Mazewall security policies,
 * sandboxing, contained execution, and thread-pool wrappers.
 *
 * <h3>Irreversibility and Ownership</h3>
 * Once a seccomp or Landlock filter is installed on a thread or process, it <b>cannot be removed or modified</b>.
 * The sandbox remains in effect for the lifetime of the thread (thread-scoped) or process (process-wide).
 * Callers are responsible for ensuring the correct scope is chosen at installation time.
 *
 * <h3>Fail-Closed Behavior</h3>
 * By default, Mazewall operates in a fail-closed mode. If sandbox installation fails and the configured
 * [Platform.FallbackBehavior] is [Platform.FallbackBehavior.FAIL], the operation will throw an exception
 * rather than silently bypassing security restrictions. This ensures that security policies are never
 * silently ignored.
 *
 * <h3>Thread Virtualization Safety</h3>
 * Seccomp filters are permanently bound to OS threads (LWP). Installing a sandbox from a virtual thread
 * (Project Loom) will contaminate the carrier thread, affecting all future virtual threads scheduled on it.
 * All installation methods in this facade validate that the current thread is not virtual and will throw
 * an [IllegalStateException] if called from a virtual thread.
 *
 * <h3>Usage Examples</h3>
 * <pre>{@code
 * // Simple thread-local sandbox
 * Policy<PolicyScope.ThreadLocalOnly, Uncompiled> policy = Mazewall.pureCompute();
 * Mazewall.installOnCurrentThread(policy);
 *
 * // Process-wide sandbox with builder
 * Policy<PolicyScope.ProcessWideSafe, Uncompiled> processPolicy = Mazewall.builder()
 *     .base(Mazewall.noExec())
 *     .allow(Syscall.WRITE)
 *     .buildProcessWide();
 * Mazewall.installOnProcess(processPolicy);
 *
 * // Contained execution with automatic cleanup
 * String result = Mazewall.runContained(Mazewall.pureComputeUnsafe(), () -> "Hello, Sandbox!");
 *
 * // Wrap an existing executor
 * ExecutorService executor = Mazewall.newContainedFixedThreadPool(4, Mazewall.pureComputeUnsafe());
 * }</pre>
 */

// Presets
@JvmField
public val PURE_COMPUTE: Policy<PolicyScope.ThreadLocalOnly, Uncompiled> = Policy(PolicyPresets.PURE_COMPUTE)

@JvmField
public val PURE_COMPUTE_UNSAFE: Policy<PolicyScope.ProcessWideSafe, Uncompiled> = Policy(PolicyPresets.PURE_COMPUTE_UNSAFE)

@JvmField
public val NO_EXEC: Policy<PolicyScope.ProcessWideSafe, Uncompiled> = Policy(PolicyPresets.NO_EXEC)

@JvmField
public val NO_EXEC_HOTSPOT: Policy<PolicyScope.ProcessWideSafe, Uncompiled> = Policy(PolicyPresets.NO_EXEC_HOTSPOT)

@JvmField
public val NO_EXEC_NATIVE_IMAGE: Policy<PolicyScope.ProcessWideSafe, Uncompiled> = Policy(PolicyPresets.NO_EXEC_NATIVE_IMAGE)

@JvmField
public val NO_NETWORK: Policy<PolicyScope.ProcessWideSafe, Uncompiled> = Policy(PolicyPresets.NO_NETWORK)

@JvmField
public val NO_EXEC_NO_FS_WRITE: Policy<PolicyScope.ProcessWideSafe, Uncompiled> = Policy(PolicyPresets.NO_EXEC_NO_FS_WRITE)

@JvmField
public val DEFAULT_SAFE: Policy<PolicyScope.ProcessWideSafe, Uncompiled> = Policy(PolicyPresets.DEFAULT_SAFE)

public fun pureCompute(): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> = PURE_COMPUTE

public fun pureComputeUnsafe(): Policy<PolicyScope.ProcessWideSafe, Uncompiled> = PURE_COMPUTE_UNSAFE

public fun noExec(): Policy<PolicyScope.ProcessWideSafe, Uncompiled> = NO_EXEC

public fun noExecHotspot(): Policy<PolicyScope.ProcessWideSafe, Uncompiled> = NO_EXEC_HOTSPOT

public fun noExecNativeImage(): Policy<PolicyScope.ProcessWideSafe, Uncompiled> = NO_EXEC_NATIVE_IMAGE

public fun noNetwork(): Policy<PolicyScope.ProcessWideSafe, Uncompiled> = NO_NETWORK

public fun noExecNoFsWrite(): Policy<PolicyScope.ProcessWideSafe, Uncompiled> = NO_EXEC_NO_FS_WRITE

public fun defaultSafe(): Policy<PolicyScope.ProcessWideSafe, Uncompiled> = DEFAULT_SAFE

// Builders
public fun builder(): JavaPolicyBuilder = JavaPolicyBuilder()

public fun builder(runtime: RuntimeProfile): JavaPolicyBuilder = JavaPolicyBuilder(runtime = runtime)

public fun threadLocalBuilder(): JavaPolicyBuilder = JavaPolicyBuilder()

public fun threadLocalBuilder(runtime: RuntimeProfile): JavaPolicyBuilder = JavaPolicyBuilder(runtime = runtime)

// Combination
public fun combine(vararg policies: Policy<*, Uncompiled>): Policy<*, Uncompiled> {
    val defs = policies.map { it.definition }.toTypedArray()
    return Policy(PolicyDefinition.combine(*defs))
}

@JvmName("combineThreadLocal")
public fun combine(vararg policies: Policy<PolicyScope.ThreadLocalOnly, Uncompiled>): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
    val defs = policies.map { it.definition }.toTypedArray()
    return Policy(PolicyDefinition.combine(*defs))
}

@JvmName("combineProcessWide")
public fun combine(vararg policies: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): Policy<PolicyScope.ProcessWideSafe, Uncompiled> {
    val defs = policies.map { it.definition }.toTypedArray()
    return Policy(PolicyDefinition.combine(*defs))
}

// Installation & Assessment
/**
 * Installs the given policy on the current thread.
 *
 * <p>This operation is <b>irreversible</b>. Once installed, the seccomp filter cannot be removed
 * or modified for the lifetime of this thread.
 *
 * <p><b>Fail-Closed:</b> If installation fails and [Platform.configuredFallback] is [Platform.FallbackBehavior.FAIL],
 * this method will throw an exception. The filter is only applied if installation succeeds.
 *
 * @param policy The policy to install on the current thread
 * @return An [InstallationReceipt] describing the installation result
 * @throws IllegalStateException if called from a virtual thread (Project Loom)
 * @throws RuntimeException if installation fails and fail-closed behavior is configured
 */
public fun installOnCurrentThread(policy: Policy<*, Uncompiled>): InstallationReceipt {
    return ContainedExecutors.installOnCurrentThread(policy)
}

/**
 * Installs the given policy on the current thread with a custom stacktrace scoping policy.
 *
 * <p>This operation is <b>irreversible</b>. Once installed, the seccomp filter cannot be removed
 * or modified for the lifetime of this thread.
 *
 * @param policy The policy to install on the current thread
 * @param scopingPolicy Custom policy for determining which stack frames to consider for scoping
 * @return An [InstallationReceipt] describing the installation result
 * @throws IllegalStateException if called from a virtual thread
 */
public fun installOnCurrentThread(policy: Policy<*, Uncompiled>, scopingPolicy: StacktraceScopingPolicy): InstallationReceipt {
    return ContainedExecutors.installOnCurrentThread(policy, scopingPolicy)
}

/**
 * Installs multiple policies on the current thread, combining them into a single filter.
 *
 * <p>This operation is <b>irreversible</b>.
 *
 * @param policies The policies to combine and install
 * @return An [InstallationReceipt] describing the installation result
 * @throws IllegalStateException if called from a virtual thread
 */
public fun installOnCurrentThread(vararg policies: Policy<*, Uncompiled>): InstallationReceipt {
    return ContainedExecutors.installOnCurrentThread(*policies)
}

/**
 * Installs the given policy process-wide, affecting all current and future threads.
 *
 * <p><b>Critical:</b> This operation is <b>irreversible</b> for the entire process. Once installed,
 * the seccomp filter applies to all threads in the process and cannot be removed.
 *
 * <p>Process-wide policies cannot have filesystem rules (Landlock) as Landlock must be applied
 * before seccomp, and a process-wide seccomp filter would block the Landlock system calls.
 *
 * <p><b>Fail-Closed:</b> If installation fails, this method will throw an exception if fail-closed
 * behavior is configured.
 *
 * @param policy The process-wide policy to install
 * @return An [InstallationReceipt] describing the installation result
 * @throws IllegalArgumentException if the policy has filesystem rules
 */
public fun installOnProcess(policy: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): InstallationReceipt {
    return ContainedExecutors.installOnProcess(policy)
}

/**
 * Installs multiple policies process-wide, combining them into a single filter.
 *
 * <p><b>Critical:</b> This operation is <b>irreversible</b> for the entire process.
 *
 * @param policies The process-wide policies to combine and install
 * @return An [InstallationReceipt] describing the installation result
 * @throws IllegalArgumentException if any policy has filesystem rules
 */
public fun installOnProcess(vararg policies: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): InstallationReceipt {
    return ContainedExecutors.installOnProcess(*policies)
}

/**
 * Assesses whether the given policy can be installed on the current thread without actually installing it.
 *
 * <p>This is useful for validating policies before attempting installation.
 *
 * @param policy The policy to assess
 * @return An [InstallationAssessment] describing whether installation would succeed
 */
public fun assessOnCurrentThread(policy: Policy<*, Uncompiled>): InstallationAssessment {
    return ContainedExecutors.assessOnCurrentThread(policy)
}

/**
 * Assesses whether the given policy can be installed process-wide without actually installing it.
 *
 * @param policy The process-wide policy to assess
 * @return An [InstallationAssessment] describing whether installation would succeed
 */
public fun assessOnProcess(policy: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): InstallationAssessment {
    return ContainedExecutors.assessOnProcess(policy)
}

// Contained execution
/**
 * Executes the given task in a contained sandbox with the specified policy.
 *
 * <p>The sandbox is automatically installed on the current thread for the duration of the task
 * execution and cleaned up afterward. This is the recommended way to run short-lived contained
 * operations without permanently affecting the thread.
 *
 * <p><b>Fail-Closed:</b> If the task throws an exception due to a containment violation, it will
 * be propagated to the caller. The sandbox ensures that blocked syscalls result in exceptions
 * rather than silent failures.
 *
 * @param policy The policy to enforce during task execution
 * @param task The task to execute
 * @return The result of the task
 * @throws RuntimeException if the task throws an exception or violates the containment policy
 */
public fun <T> runContained(policy: Policy<*, *>, task: Callable<T>): T {
    return try {
        SandboxDispatcher.execute(policy, task)
    } catch (e: java.util.concurrent.ExecutionException) {
        val cause = e.cause
        if (cause is RuntimeException) throw cause
        if (cause is Error) throw cause
        if (cause is Exception) throw cause
        throw e
    }
}

/**
 * Executes the given task in a contained sandbox with the specified policy.
 *
 * @param policy The policy to enforce during task execution
 * @param task The task to execute
 * @return The result of the task
 * @throws RuntimeException if the task throws an exception or violates the containment policy
 */
public fun <T> runContained(policy: Policy<*, *>, task: Supplier<T>): T {
    return runContained(policy, Callable { task.get() })
}

/**
 * Executes the given task in a contained sandbox with the specified policy.
 *
 * @param policy The policy to enforce during task execution
 * @param task The task to execute
 * @throws RuntimeException if the task throws an exception or violates the containment policy
 */
public fun runContained(policy: Policy<*, *>, task: Runnable) {
    runContained(policy, Callable {
        task.run()
        null
    })
}

// Contained Executor Wrappers & Factories
/**
 * Wraps an existing executor service so that all tasks submitted to it run under the specified policy.
 *
 * <p><b>Ownership:</b> The returned executor is a wrapper that applies the sandbox policy to each task.
 * The original executor remains unchanged. The wrapper manages the lifecycle of the sandbox for each task.
 *
 * <p><b>Fail-Closed:</b> If a task violates the containment policy, it will throw an exception
 * that can be caught from the Future returned by submit().
 *
 * @param delegate The underlying executor service to wrap
 * @param policy The policy to apply to all tasks
 * @return A new ExecutorService that enforces the policy on all submitted tasks
 */
public fun wrapContainedExecutor(
    delegate: ExecutorService,
    policy: Policy<*, Uncompiled>,
): ExecutorService = wrapExecutor(delegate, policy)

/**
 * Wraps an existing executor service with multiple policies.
 *
 * <p>The policies are combined into a single filter. Each task submitted to the wrapped executor
 * will run under the combined policy.
 *
 * @param delegate The underlying executor service to wrap
 * @param policies The policies to combine and apply to all tasks
 * @return A new ExecutorService that enforces the combined policy on all submitted tasks
 */
public fun wrapExecutor(delegate: ExecutorService, vararg policies: Policy<*, Uncompiled>): ExecutorService {
    return ContainedExecutors.wrap(delegate, *policies)
}

/**
 * Alias for [wrapExecutor].
 *
 * @param delegate The underlying executor service to wrap
 * @param policies The policies to combine and apply to all tasks
 * @return A new ExecutorService that enforces the combined policy on all submitted tasks
 */
public fun wrap(delegate: ExecutorService, vararg policies: Policy<*, Uncompiled>): ExecutorService {
    return ContainedExecutors.wrap(delegate, *policies)
}

/**
 * Creates a new single-thread executor where all tasks run under the specified policy.
 *
 * <p><b>Ownership:</b> The returned executor owns its thread and manages the sandbox lifecycle.
 * Shutting down the executor will stop the thread. The sandbox is installed on the thread
 * when it starts and remains for the thread's lifetime.
 *
 * <p><b>Fail-Closed:</b> The sandbox is installed before any tasks are executed. If installation
 * fails, the executor creation will throw an exception.
 *
 * @param policy The policy to apply to all tasks
 * @return A new single-thread ExecutorService with the policy installed
 */
public fun newContainedSingleThreadExecutor(policy: Policy<*, Uncompiled>): ExecutorService =
    ContainedExecutors.newSingleThreadExecutor(policy)

/**
 * Creates a new fixed thread pool where all tasks run under the specified policy.
 *
 * <p><b>Ownership:</b> The returned executor owns its threads and manages their sandbox lifecycle.
 * Each thread in the pool has the policy installed when it starts.
 *
 * @param nThreads The number of threads in the pool
 * @param policy The policy to apply to all tasks
 * @return A new fixed-thread-pool ExecutorService with the policy installed on each thread
 */
public fun newContainedFixedThreadPool(nThreads: Int, policy: Policy<*, Uncompiled>): ExecutorService =
    ContainedExecutors.newFixedThreadPool(nThreads, policy)

/**
 * Creates a new cached thread pool where all tasks run under the specified policy.
 *
 * <p><b>Ownership:</b> The returned executor owns its threads. Each thread created by the pool
 * has the policy installed when it starts. Idle threads may be terminated and new threads
 * created as needed, each with the policy installed.
 *
 * @param policy The policy to apply to all tasks
 * @return A new cached-thread-pool ExecutorService with the policy installed on each thread
 */
public fun newContainedCachedThreadPool(policy: Policy<*, Uncompiled>): ExecutorService =
    ContainedExecutors.newCachedThreadPool(policy)
