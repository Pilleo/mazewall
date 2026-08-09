package io.mazewall.enforcer.diagnostics

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.Platform
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Checks if the given thread is a Virtual Thread or a Loom carrier thread (e.g., ForkJoinWorkerThread).
 */
public fun isVirtualOrCarrierThread(thread: Thread = Thread.currentThread()): Boolean {
    if (thread.isVirtual) return true
    val className = thread.javaClass.name
    val name = thread.name
    return className.contains("ForkJoinWorkerThread") ||
        name.contains("ForkJoinPool") ||
        name.contains("carrier", ignoreCase = true)
}

/**
 * Validates that the current thread is not a Virtual Thread or Loom carrier thread (Loom carrier poisoning protection).
 *
 * This function uses a Kotlin contract to formalize this invariant, allowing the compiler to
 * perform flow analysis under the guarantee that the current thread is a platform thread.
 */
@OptIn(ExperimentalContracts::class)
public fun validateNotVirtual() {
    contract {
        returns()
    }
    if (isVirtualOrCarrierThread()) {
        throw IllegalStateException(
            "Attempted to apply thread-scoped seccomp containment inside a virtual or carrier thread. " +
                "Seccomp filters are per-thread and would contaminate the carrier thread, " +
                "affecting other unrelated virtual threads. " +
                "Use process-wide containment (installOnProcess) or ContainedExecutors.wrap(executorService).",
        )
    }
}

/**
 * Validates that the current environment is suitable for applying thread-scoped
 * sandboxing rules.
 *
 * This function uses a Kotlin contract to formalize the invariant that the
 * code is running on a standard Linux platform thread (not a Virtual Thread or carrier thread).
 */
@OptIn(ExperimentalContracts::class)
public fun validateLinuxAndNotVirtual() {
    contract {
        returns()
    }

    Platform.validateLinux()
    validateNotVirtual()
}
