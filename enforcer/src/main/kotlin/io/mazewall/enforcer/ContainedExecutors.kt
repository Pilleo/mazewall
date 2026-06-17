package io.mazewall.enforcer

import io.mazewall.Platform
import io.mazewall.Policy
import io.mazewall.PolicyScope
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
 *
 * ### Security & Scope
 * Seccomp is a "blast radius" mitigator for I/O and execution. It is **not** a replacement
 * for internal Java security boundaries (like module exports) or data isolation. A contained
 * thread can still read the heap and any static variables it has access to. For resource
 * isolation (CPU, memory), use Linux cgroups.
 *
 * ### Filesystem Containment and Class Loading
 * [io.mazewall.Policy.Companion.PURE_COMPUTE] uses Landlock for filesystem enforcement and does
 * NOT block `openat`/`open` at the seccomp level. The JVM can load classes lazily from the
 * classpath without interference, as long as classpath paths are included via
 * [io.mazewall.Policy.Builder.allowJvmClasspath].
 *
 * [io.mazewall.Policy.Companion.PURE_COMPUTE_UNSAFE] is deprecated. It blocks `openat`/`open`
 * at the seccomp level, which prevents lazy class loading on the sandboxed thread. Only use it
 * when ALL classes needed by the task are guaranteed to be loaded before containment is applied.
 */
object ContainedExecutors {
    private val logger = Logger.getLogger(ContainedExecutors::class.java.name)
    private val processLock = Any()

    /**
     * Installs the given policies onto the current thread immediately.
     *
     * ### Virtual Thread Warning
     * Seccomp filters are per-thread. Virtual threads multiplex onto a small pool of OS
     * "carrier" threads. Installing a filter from within a virtual thread would sandbox
     * the carrier, inadvertently affecting all other virtual threads scheduled on it.
     * To prevent this "carrier contamination", this method throws [IllegalStateException]
     * if called from a virtual thread.
     */
    fun installOnCurrentThread(vararg policies: Policy<*, Uncompiled>) {
        installInternal(false, *policies)
    }

    /**
     * Installs the given policies onto the entire process (all threads) immediately.
     * This acts as a global security lockdown and cannot be undone. All future threads
     * created by this process will inherit these restrictions.
     */
    fun installOnProcess(vararg policies: Policy<PolicyScope.ProcessWideSafe, Uncompiled>) {
        installInternal(true, *policies)
    }

    /**
     * Wraps an [java.util.concurrent.ExecutorService] so that any task submitted to it will have the given
     * [policies] applied before execution.
     */
    fun wrap(
        delegate: ExecutorService,
        vararg policies: Policy<*, Uncompiled>,
    ): ExecutorService {
        val combinedPolicy = Policy.combine(*policies)
        return ContainedExecutorWrapper(delegate, combinedPolicy)
    }

    private fun installInternal(
        processWide: Boolean,
        vararg policies: Policy<*, Uncompiled>,
    ) {
        validateLinuxAndNotVirtual()

        val combinedPolicy = Policy.combine(*policies)
        applyLandlockIfNecessary(processWide, combinedPolicy)

        if (!Platform.isSupported()) {
            handleUnsupportedPlatform()
            return
        }

        installSeccompFilter(processWide, combinedPolicy)
    }

    private fun installSeccompFilter(
        processWide: Boolean,
        combinedPolicy: Policy<*, Uncompiled>,
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
        policy: Policy<*, Uncompiled>,
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

    private fun needsLandlock(policy: Policy<*, *>) =
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
        toInstall: Policy<*, Uncompiled>,
        newBlocks: Map<Syscall, SeccompAction>,
        newDefaultAction: SeccompAction,
    ) {
        val arch = io.mazewall.core.Arch
            .current()
        val compiledPolicy = toInstall.compile(arch)
        if (processWide) {
            PureJavaBpfEngine.installOnProcess(compiledPolicy)
            updateProcessState(newBlocks, newDefaultAction, toInstall)
        } else {
            PureJavaBpfEngine.install(compiledPolicy)
            updateThreadState(newBlocks, newDefaultAction, toInstall)
        }
    }

    private fun updateProcessState(
        newBlocks: Map<Syscall, SeccompAction>,
        newDefaultAction: SeccompAction,
        toInstall: Policy<*, Uncompiled>,
    ) {
        ProcessStateRegistry.update { current ->
            current.withNewSeccompPolicy(toInstall, newBlocks, newDefaultAction)
        }
    }

    private fun updateThreadState(
        newBlocks: Map<Syscall, SeccompAction>,
        newDefaultAction: SeccompAction,
        toInstall: Policy<*, Uncompiled>,
    ) {
        ThreadStateRegistry.state = ThreadStateRegistry.state.withNewSeccompPolicy(toInstall, newBlocks, newDefaultAction)
    }
}
