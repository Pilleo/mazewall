package io.mazewall.enforcer

import io.mazewall.Platform
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Validates that the current environment is suitable for applying thread-scoped
 * sandboxing rules.
 *
 * This function uses a Kotlin contract to formalize the invariant that the
 * code is running on a standard Linux platform thread (not a Virtual Thread).
 */
@OptIn(ExperimentalContracts::class)
internal fun validateLinuxAndNotVirtual() {
    contract {
        returns() implies true
    }

    if (!Platform.isLinux) {
        throw UnsupportedOperationException("Mazewall requires Linux for kernel-level containment.")
    }

    if (Thread.currentThread().isVirtual) {
        throw IllegalStateException(
            "Attempted to apply seccomp containment inside a virtual thread. " +
                "Seccomp filters are per-thread and would contaminate the carrier thread, " +
                "affecting other unrelated virtual threads. " +
                "Use a dedicated platform thread pool for sandboxed tasks.",
        )
    }
}
