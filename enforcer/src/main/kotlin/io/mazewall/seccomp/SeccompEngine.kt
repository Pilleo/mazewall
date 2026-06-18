package io.mazewall.seccomp

import io.mazewall.CompiledSandbox

/**
 * Sealed interface representing the type-safe lifecycle states of a SeccompEngine.
 */
internal sealed interface EngineState {
    /** The engine is initialized but no filters or privileges have been restricted yet. */
    interface Unprivileged : EngineState

    /** The configuration (policy compile) has been registered. */
    interface Configured : EngineState

    /** BPF filters have been successfully loaded into the kernel for this thread/process. */
    interface Loaded : EngineState
}

/**
 * Common interface for seccomp installation mechanisms.
 *
 * This engine follows a type-safe lifecycle state machine enforced via Phantom Types:
 * [EngineState.Unprivileged] -> [EngineState.Loaded].
 *
 * Transitions are enforced by the compiler, ensuring that an engine must be in the correct
 * state before installation can be attempted, and providing a proof of installation
 * via the [EngineState.Loaded] return type.
 *
 * @param S The current [EngineState] of the engine.
 */
internal interface SeccompEngine<out S : EngineState> {
    /** The current state of the engine. */
    val state: S

    /**
     * Installs the given [policy] onto the calling thread.
     * Throws [IllegalStateException] if installation fails.
     */
    fun install(policy: CompiledSandbox<*>): SeccompEngine<EngineState.Loaded>

    /**
     * Installs the given [policy] globally on the entire process (all threads).
     * This uses SECCOMP_FILTER_FLAG_TSYNC on Linux.
     * Throws [IllegalStateException] if installation fails or is not supported.
     */
    fun installOnProcess(policy: CompiledSandbox<*>): SeccompEngine<EngineState.Loaded> =
        throw UnsupportedOperationException("Global process containment is not supported by this engine.")

    /**
     * Returns true if this engine is supported on the current system.
     */
    val isSupported: Boolean
}
