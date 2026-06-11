package io.mazewall.seccomp

import java.lang.foreign.MemorySegment

/**
 * States representing the progress of a Seccomp program installation.
 */
internal sealed interface SeccompInstallationState {
    /** The Seccomp installation process has not started. */
    data object Uninitialized : SeccompInstallationState

    /** The thread or process has set `no_new_privs`. */
    data object PrivilegesLocked : SeccompInstallationState

    /** The BPF program filter has been successfully constructed in memory. */
    data class FilterBuilt(
        val program: MemorySegment,
    ) : SeccompInstallationState

    /** The Seccomp filter was successfully applied via the modern `seccomp(2)` syscall. */
    data object SystemCallApplied : SeccompInstallationState

    /** The Seccomp filter was successfully applied via the fallback `prctl(2)` command. */
    data object FallbackPrctlApplied : SeccompInstallationState

    /** The Seccomp installation was verified successfully. */
    data object Verified : SeccompInstallationState

    /** The installation process failed at a specific step. */
    data class Failed(
        val step: String,
        val errno: Int,
        val error: Throwable,
    ) : SeccompInstallationState
}
