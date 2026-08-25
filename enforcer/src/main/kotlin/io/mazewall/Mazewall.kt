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
public fun installOnCurrentThread(policy: Policy<*, Uncompiled>): InstallationReceipt {
    return ContainedExecutors.installOnCurrentThread(policy)
}

public fun installOnCurrentThread(policy: Policy<*, Uncompiled>, scopingPolicy: StacktraceScopingPolicy): InstallationReceipt {
    return ContainedExecutors.installOnCurrentThread(policy, scopingPolicy)
}

public fun installOnCurrentThread(vararg policies: Policy<*, Uncompiled>): InstallationReceipt {
    return ContainedExecutors.installOnCurrentThread(*policies)
}

public fun installOnProcess(policy: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): InstallationReceipt {
    return ContainedExecutors.installOnProcess(policy)
}

public fun installOnProcess(vararg policies: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): InstallationReceipt {
    return ContainedExecutors.installOnProcess(*policies)
}

public fun assessOnCurrentThread(policy: Policy<*, Uncompiled>): InstallationAssessment {
    return ContainedExecutors.assessOnCurrentThread(policy)
}

public fun assessOnProcess(policy: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): InstallationAssessment {
    return ContainedExecutors.assessOnProcess(policy)
}

// Contained execution
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

public fun <T> runContained(policy: Policy<*, *>, task: Supplier<T>): T {
    return runContained(policy, Callable { task.get() })
}

public fun runContained(policy: Policy<*, *>, task: Runnable) {
    runContained(policy, Callable {
        task.run()
        null
    })
}

// Contained Executor Wrappers & Factories
/** Java-friendly single-policy alias of [wrapExecutor]. */
public fun wrapContainedExecutor(
    delegate: ExecutorService,
    policy: Policy<*, Uncompiled>,
): ExecutorService = wrapExecutor(delegate, policy)

public fun wrapExecutor(delegate: ExecutorService, vararg policies: Policy<*, Uncompiled>): ExecutorService {
    return ContainedExecutors.wrap(delegate, *policies)
}

public fun wrap(delegate: ExecutorService, vararg policies: Policy<*, Uncompiled>): ExecutorService {
    return ContainedExecutors.wrap(delegate, *policies)
}

public fun newContainedSingleThreadExecutor(policy: Policy<*, Uncompiled>): ExecutorService =
    ContainedExecutors.newSingleThreadExecutor(policy)

public fun newContainedFixedThreadPool(nThreads: Int, policy: Policy<*, Uncompiled>): ExecutorService =
    ContainedExecutors.newFixedThreadPool(nThreads, policy)

public fun newContainedCachedThreadPool(policy: Policy<*, Uncompiled>): ExecutorService =
    ContainedExecutors.newCachedThreadPool(policy)
