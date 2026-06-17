package io.mazewall.enforcer

import io.mazewall.Platform
import io.mazewall.Policy
import io.mazewall.PolicyDefinition
import io.mazewall.PolicyScope
import io.mazewall.PolicyPresets
import io.mazewall.Uncompiled
import io.mazewall.compile
import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.enforcer.internal.ContainedExecutorWrapper
import io.mazewall.landlock.Landlock
import io.mazewall.seccomp.PureJavaBpfEngine
import java.util.concurrent.ExecutorService
import java.util.logging.Logger

/**
 * Public API for wrapping an existing [java.util.concurrent.ExecutorService] to enforce seccomp containment.
 */
object ContainedExecutors {
    private val logger = Logger.getLogger(ContainedExecutors::class.java.name)
    private val processLock = Any()

    /**
     * Installs the given policies onto the current thread immediately.
     */
    fun installOnCurrentThread(vararg policies: Policy<*, Uncompiled>) {
        val combined = PolicyDefinition.combine(*policies.map { it.definition }.toTypedArray())
        installOnCurrentThread(combined)
    }

    internal fun installOnCurrentThread(policy: PolicyDefinition<*>) {
        installInternal(false, policy)
    }

    /**
     * Installs the given policies onto the entire process (all threads) immediately.
     */
    fun installOnProcess(vararg policies: Policy<PolicyScope.ProcessWideSafe, Uncompiled>) {
        val combined = PolicyDefinition.combine(*policies.map { it.definition }.toTypedArray())
        installInternal(true, combined)
    }

    /**
     * Wraps an [java.util.concurrent.ExecutorService] so that any task submitted to it will have the given
     * [policies] applied before execution.
     */
    fun wrap(
        delegate: ExecutorService,
        vararg policies: Policy<*, Uncompiled>,
    ): ExecutorService {
        val combinedPolicy = PolicyDefinition.combine(*policies.map { it.definition }.toTypedArray())
        return ContainedExecutorWrapper(delegate, combinedPolicy)
    }

    private fun installInternal(
        processWide: Boolean,
        policy: PolicyDefinition<*>,
    ) {
        validateLinuxAndNotVirtual()

        applyLandlockIfNecessary(processWide, policy)

        if (!Platform.isSupported()) {
            handleUnsupportedPlatform()
            return
        }

        installSeccompFilter(processWide, policy)
    }

    private fun installSeccompFilter(
        processWide: Boolean,
        combinedPolicy: PolicyDefinition<*>,
    ) {
        synchronized(processLock) {
            val state = resolveCurrentState()
            val plan = FilterInstallationPlanner.calculateNewFilter(combinedPolicy, state)

            if (plan.needsNewFilter) {
                FilterInstallationPlanner.verifyFilterDepth(state.filterDepth)
                applyBpfFilter(processWide, plan.toInstall, plan.newBlocks, plan.newDefaultAction)
            }
        }
    }

    private fun applyLandlockIfNecessary(
        processWide: Boolean,
        policy: PolicyDefinition<*>,
    ) {
        if (!needsLandlock(policy)) return

        val state = if (processWide) ProcessStateRegistry.state else ThreadStateRegistry.state
        val appliedReads = state.landlockAppliedReads
        val appliedWrites = state.landlockAppliedWrites

        if (appliedReads != null && appliedWrites != null) {
            // Assert that we are not trying to expand Landlock filesystem permissions on nested containment
            val readsSubset = isPathSubset(appliedReads, policy.allowedFsReadPaths)
            val writesSubset = isPathSubset(appliedWrites, policy.allowedFsWritePaths)
            if (!readsSubset || !writesSubset) {
                throw IllegalStateException("Cannot expand Landlock filesystem permissions on an already restricted thread.")
            }
        }

        if (appliedReads != policy.allowedFsReadPaths || appliedWrites != policy.allowedFsWritePaths) {
            Landlock.applyRuleset(policy, processWide)
            if (processWide) {
                ProcessStateRegistry.update { it.withLandlockPaths(policy.allowedFsReadPaths, policy.allowedFsWritePaths) }
            } else {
                ThreadStateRegistry.state = ThreadStateRegistry.state.withLandlockPaths(policy.allowedFsReadPaths, policy.allowedFsWritePaths)
            }
        }
    }

    private fun needsLandlock(policy: PolicyDefinition<*>) =
        policy.allowedFsReadPaths.isNotEmpty() ||
            policy.allowedFsWritePaths.isNotEmpty() ||
            (policy.isSyscallAllowed(Syscall.IO_URING_SETUP) && (!policy.isSyscallAllowed(Syscall.OPEN) || !policy.isSyscallAllowed(Syscall.OPENAT)))

    private fun isPathSubset(
        parentPaths: Set<SandboxedPath>,
        childPaths: Set<SandboxedPath>,
    ): Boolean {
        if (childPaths.isEmpty()) return true
        val parents = parentPaths.map { java.nio.file.Paths.get(it.value) }
        return parents.isNotEmpty() &&
            childPaths.all { childPath ->
                val child = java.nio.file.Paths.get(childPath.value)
                parents.any { parent -> child.startsWith(parent) }
            }
    }

    private fun handleUnsupportedPlatform() {
        val fallback = Platform.configuredFallback()
        when (fallback) {
            Platform.FallbackBehavior.FAIL ->
                throw UnsupportedOperationException("Platform does not support seccomp")

            Platform.FallbackBehavior.WARN_AND_BYPASS ->
                logger.warning("Platform does not support seccomp. Code will run uncontained.")

            Platform.FallbackBehavior.SILENT_BYPASS -> {}
        }
    }

    private fun resolveCurrentState(): ContainerState {
        val ts = ThreadStateRegistry.state
        val ps = ProcessStateRegistry.state

        val mergedActions = ts.syscallActions.toMutableMap()
        for ((sys, action) in ps.syscallActions) {
            val current = mergedActions[sys]
            if (current == null || action.priority > current.priority) {
                mergedActions[sys] = action
            }
        }

        val mergedDefault = if (ts.defaultAction.priority > ps.defaultAction.priority) ts.defaultAction else ps.defaultAction

        val mergedAllowed = if (ts.allowedSyscalls == null) {
            ps.allowedSyscalls
        } else if (ps.allowedSyscalls == null) {
            ts.allowedSyscalls
        } else {
            ts.allowedSyscalls.intersect(ps.allowedSyscalls)
        }

        return ContainerState(
            filterDepth = ts.filterDepth + ps.filterDepth,
            syscallActions = mergedActions,
            defaultAction = mergedDefault,
            allowedSyscalls = mergedAllowed,
            allowsMmapExec = ts.allowsMmapExec && ps.allowsMmapExec,
            allowsNonThreadClone = ts.allowsNonThreadClone && ps.allowsNonThreadClone,
            allowsUnsafePrctl = ts.allowsUnsafePrctl && ps.allowsUnsafePrctl,
            landlockAppliedReads = ts.landlockAppliedReads,
            landlockAppliedWrites = ts.landlockAppliedWrites
        )
    }

    private fun applyBpfFilter(
        processWide: Boolean,
        toInstall: PolicyDefinition<*>,
        newBlocks: Map<Syscall, SeccompAction>,
        newDefaultAction: SeccompAction,
    ) {
        val arch = io.mazewall.core.Arch.current()
        val compiledSandbox = toInstall.compile(arch)
        if (processWide) {
            PureJavaBpfEngine.installOnProcess(compiledSandbox)
            updateProcessState(newBlocks, newDefaultAction, toInstall)
        } else {
            PureJavaBpfEngine.install(compiledSandbox)
            updateThreadState(newBlocks, newDefaultAction, toInstall)
        }
    }

    private fun updateProcessState(
        newBlocks: Map<Syscall, SeccompAction>,
        newDefaultAction: SeccompAction,
        toInstall: PolicyDefinition<*>,
    ) {
        ProcessStateRegistry.update { current ->
            current.withNewSeccompPolicy(toInstall, newBlocks, newDefaultAction)
        }
    }

    private fun updateThreadState(
        newBlocks: Map<Syscall, SeccompAction>,
        newDefaultAction: SeccompAction,
        toInstall: PolicyDefinition<*>,
    ) {
        ThreadStateRegistry.state = ThreadStateRegistry.state.withNewSeccompPolicy(toInstall, newBlocks, newDefaultAction)
    }
}
