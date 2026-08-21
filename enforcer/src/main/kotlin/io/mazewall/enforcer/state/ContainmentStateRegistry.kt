package io.mazewall.enforcer.state

import io.mazewall.core.Syscall
import io.mazewall.core.threadLocal
import io.mazewall.seccomp.SeccompInstallationState
import java.util.concurrent.atomic.AtomicReference

/**
 * Internal registry for tracking seccomp and Landlock state applied globally to the process
 * and locally to individual threads.
 */
internal object ContainmentStateRegistry {
    // -------------------------------------------------------------------------
    // Process-wide global state
    // -------------------------------------------------------------------------

    private val processStateRef = AtomicReference(ContainerState())

    /**
     * The current global security state of the process.
     */
    var processState: ContainerState
        get() = processStateRef.get()
        set(value) = processStateRef.set(value)

    /**
     * Atomically updates the global security state.
     */
    fun updateProcessState(block: (ContainerState) -> ContainerState) {
        processStateRef.updateAndGet { block(it) }
    }

    // -------------------------------------------------------------------------
    // Thread-local state
    // -------------------------------------------------------------------------

    // INVARIANT: ThreadLocals are INTENTIONALLY not cleared between tasks.
    // Seccomp filters are permanent for the OS thread lifetime.
    // Do NOT add cleanup in task wrappers; it would give a false sense of
    // isolation between tasks on the same thread. See docs/internals/backlog/
    // issue-102-permanent-thread-pool-contamination-classloader-leaks-and-st.md
    // for the known limitation and correct fix strategy (scope checks, not cleanup).

    private class ThreadStateHolder {
        var state: ContainerState = ContainerState()
        var cachedProcessState: ContainerState? = null
        var cachedMergedState: ContainerState? = null
    }

    private val threadHolder by threadLocal { ThreadStateHolder() }

    /**
     * The current security state of the active thread.
     */
    var threadState: ContainerState
        get() = threadHolder.state
        set(value) {
            val h = threadHolder
            h.state = value
            h.cachedMergedState = null
            h.cachedProcessState = null
        }

    // -------------------------------------------------------------------------
    // State Resolution
    // -------------------------------------------------------------------------

    /**
     * Fast path to resolve the current merged security state of the active thread.
     */
    fun resolveCurrentState(): ContainerState {
        val h = threadHolder
        val ts = h.state
        val ps = processState

        if (h.cachedProcessState === ps && h.cachedMergedState != null) {
            return h.cachedMergedState!!
        }

        val merged = mergeStates(ts, ps)
        h.cachedProcessState = ps
        h.cachedMergedState = merged
        return merged
    }

    private fun mergeStates(ts: ContainerState, ps: ContainerState): ContainerState {
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

        val mergedEngineState = mergeEngineStates(ts.engineState, ps.engineState)

        return ContainerState(
            filterDepth = ts.filterDepth + ps.filterDepth,
            syscallActions = mergedActions,
            defaultAction = mergedDefault,
            allowedSyscalls = mergedAllowed,
            allowsMmapExec = ts.allowsMmapExec && ps.allowsMmapExec,
            allowsNonThreadClone = ts.allowsNonThreadClone && ps.allowsNonThreadClone,
            allowsUnsafePrctl = ts.allowsUnsafePrctl && ps.allowsUnsafePrctl,
            landlockPolicy = ts.landlockPolicy ?: ps.landlockPolicy,
            engineState = mergedEngineState
        )
    }

    private fun mergeEngineStates(
        ts: SeccompInstallationState,
        ps: SeccompInstallationState
    ): SeccompInstallationState {
        val tsRank = stateRank(ts)
        val psRank = stateRank(ps)
        return if (tsRank >= psRank) ts else ps
    }

    private fun stateRank(state: SeccompInstallationState): Int {
        return when (state) {
            is SeccompInstallationState.Uninitialized -> 0
            is SeccompInstallationState.Failed -> 1
            is SeccompInstallationState.FilterBuilt -> 2
            is SeccompInstallationState.PrivilegesLocked -> 3
            is SeccompInstallationState.SystemCallApplied -> 4
            is SeccompInstallationState.FallbackPrctlApplied -> 4
            is SeccompInstallationState.Verified -> 5
        }
    }

    /**
     * Explicitly disables state sanitization.
     * Thread-local states reflect the immutable OS-level restrictions applied
     * to the OS thread (LWP). Clearing them would cause synchronization loss
     * between JVM state and kernel state, leading to redundant filter installations.
     */
    fun sanitizeThreadState(): Nothing {
        throw UnsupportedOperationException(
            "Sanitization of thread state is intentionally disabled. " +
                "OS-level sandbox restrictions are permanent for the thread's lifetime."
        )
    }
}
