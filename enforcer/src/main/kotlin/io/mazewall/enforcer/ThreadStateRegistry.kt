package io.mazewall.enforcer

/**
 * Internal registry for tracking seccomp and Landlock state bound to the current thread.
 *
 * It captures the current [ContainerState] reflecting the immutable OS-level
 * restrictions applied to the thread.
 */
internal object ThreadStateRegistry {
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

    private val holder = ThreadLocal.withInitial { ThreadStateHolder() }

    /**
     * The current security state of the active thread.
     */
    var state: ContainerState
        get() = holder.get().state
        set(value) {
            val h = holder.get()
            h.state = value
            h.cachedMergedState = null
            h.cachedProcessState = null
        }

    /**
     * Fast path to resolve the current merged security state of the active thread.
     */
    fun resolveCurrentState(processState: ContainerState): ContainerState {
        val h = holder.get()
        val ts = h.state
        if (h.cachedProcessState === processState && h.cachedMergedState != null) {
            return h.cachedMergedState!!
        }

        val merged = mergeStates(ts, processState)
        h.cachedProcessState = processState
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

        return ContainerState(
            filterDepth = ts.filterDepth + ps.filterDepth,
            syscallActions = mergedActions,
            defaultAction = mergedDefault,
            allowedSyscalls = mergedAllowed,
            allowsMmapExec = ts.allowsMmapExec && ps.allowsMmapExec,
            allowsNonThreadClone = ts.allowsNonThreadClone && ps.allowsNonThreadClone,
            allowsUnsafePrctl = ts.allowsUnsafePrctl && ps.allowsUnsafePrctl,
            landlockPolicy = ts.landlockPolicy ?: ps.landlockPolicy
        )
    }

    /**
     * Explicitly disables state sanitization.
     * Thread-local states reflect the immutable OS-level restrictions applied
     * to the OS thread (LWP). Clearing them would cause synchronization loss
     * between JVM state and kernel state, leading to redundant filter installations.
     */
    fun sanitize() {
        throw UnsupportedOperationException(
            "Sanitization of ThreadStateRegistry is intentionally disabled. " +
                "OS-level sandbox restrictions are permanent for the thread's lifetime."
        )
    }
}
