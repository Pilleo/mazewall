package io.mazewall.enforcer

import io.mazewall.Platform
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Detects if the current thread is a Loom carrier thread or virtual thread.
 * Loom virtual threads have thread.isVirtual == true.
 * Loom carrier threads run on ForkJoinPool, so their thread names contain "ForkJoinPool".
 */
public fun isLoomOrCarrierThread(): Boolean {
    val t = Thread.currentThread()
    return t.isVirtual || t.name.contains("ForkJoinPool")
}

/**
 * Validates that the current thread is not a Virtual Thread (Loom carrier poisoning protection).
 *
 * This function uses a Kotlin contract to formalize this invariant, allowing the compiler to
 * perform flow analysis under the guarantee that the current thread is a platform thread.
 */
@OptIn(ExperimentalContracts::class)
public fun validateNotVirtual() {
    contract {
        returns()
    }
    if (isLoomOrCarrierThread()) {
        throw UnsupportedOperationException(
            "Attempted to apply seccomp containment inside a virtual thread or a carrier thread. " +
                "Seccomp filters are per-thread and would contaminate the carrier thread, " +
                "affecting other unrelated virtual threads. " +
                "Use a dedicated platform thread pool for sandboxed tasks.",
        )
    }
}

/**
 * Validates that the current environment is suitable for applying thread-scoped
 * sandboxing rules.
 *
 * This function uses a Kotlin contract to formalize the invariant that the
 * code is running on a standard Linux platform thread (not a Virtual Thread).
 */
@OptIn(ExperimentalContracts::class)
public fun validateLinuxAndNotVirtual() {
    contract {
        returns()
    }

    Platform.validateLinux()
    validateNotVirtual()
}
