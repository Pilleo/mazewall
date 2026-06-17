package io.mazewall.seccomp

import io.mazewall.Policy
import io.mazewall.Compiled

/**
 * Sealed interface representing the type-safe lifecycle states of a SeccompEngine.
 */
public sealed interface EngineState {
    /** The engine is initialized but no filters or privileges have been restricted yet. */
    public interface Unprivileged : EngineState

    /** The configuration (policy compile) has been registered. */
    public interface Configured : EngineState

    /** BPF filters have been successfully loaded into the kernel for this thread/process. */
    public interface Loaded : EngineState
}

/**
 * Common interface for seccomp installation mechanisms.
 * Parameterized by S to enforce type-safe states at compile time.
 */
public interface SeccompEngine<out S : EngineState> {
    /** The current state of the engine. */
    public val state: S

    /**
     * Installs the given [policy] onto the calling thread.
     * Throws [IllegalStateException] if installation fails.
     */
    public fun install(policy: Policy<*, Compiled>): SeccompEngine<EngineState.Loaded>

    /**
     * Installs the given [policy] globally on the entire process (all threads).
     * This uses SECCOMP_FILTER_FLAG_TSYNC on Linux.
     * Throws [IllegalStateException] if installation fails or is not supported.
     */
    public fun installOnProcess(policy: Policy<*, Compiled>): SeccompEngine<EngineState.Loaded> =
        throw UnsupportedOperationException("Global process containment is not supported by this engine.")

    /**
     * Returns true if this engine is supported on the current system.
     */
    public val isSupported: Boolean
}
