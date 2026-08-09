package io.mazewall.enforcer.state

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import java.util.concurrent.atomic.AtomicReference

/**
 * Internal registry for tracking seccomp and Landlock state applied to the entire process.
 */
internal object ProcessStateRegistry {
    private val stateRef = AtomicReference(ContainerState())

    /**
     * The current global security state of the process.
     */
    var state: ContainerState
        get() = stateRef.get()
        set(value) = stateRef.set(value)

    /**
     * Atomically updates the global security state.
     */
    fun update(block: (ContainerState) -> ContainerState) {
        stateRef.updateAndGet { block(it) }
    }
}
