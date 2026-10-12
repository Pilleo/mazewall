package io.mazewall.enforcer.diagnostics

import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Checks whether containment would be installed from a virtual thread.
 *
 * Ordinary [java.util.concurrent.ForkJoinWorkerThread] instances are platform threads. Their class
 * and name do not establish that they are Loom's private carrier, so rejecting them would prevent
 * containment from being installed by a regular [java.util.concurrent.ForkJoinPool].
 */
public fun isVirtualOrCarrierThread(thread: Thread = Thread.currentThread()): Boolean = thread.isVirtual

/**
 * Validates that the current thread is not a virtual thread (Loom carrier poisoning protection).
 *
 * This function uses a Kotlin contract to formalize this invariant, allowing the compiler to
 * perform flow analysis under the guarantee that the current thread is a platform thread.
 */
@OptIn(ExperimentalContracts::class)
public fun validateNotVirtual() {
    contract {
        returns()
    }
    requirePlatformThread()
}

/**
 * Seccomp is bound to the OS thread. Installing from a virtual thread contaminates its
 * carrier for every later virtual task on that carrier.
 */
@OptIn(ExperimentalContracts::class)
public fun requirePlatformThread(thread: Thread = Thread.currentThread()) {
    contract {
        returns()
    }
    if (isVirtualOrCarrierThread(thread)) {
        throw IllegalStateException(
            "Cannot install seccomp from a virtual thread: the filter would permanently contaminate " +
                "its carrier and affect unrelated virtual tasks. Use a platform thread, " +
                "ManagedRuntime owned preparation, installOnProcess, or a portal worker.",
        )
    }
}

/**
 * Validates that the current environment is suitable for applying thread-scoped
 * sandboxing rules.
 *
 * This function uses a Kotlin contract to formalize the invariant that the
 * code is running on a standard Linux platform thread (not a virtual thread).
 */
@OptIn(ExperimentalContracts::class)
public fun validateLinuxAndNotVirtual() {
    contract {
        returns()
    }

    Platform.validateLinux()
    validateNotVirtual()
}
