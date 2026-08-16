package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

/**
 * Exception thrown when a contained task violates the configured policy
 * (e.g., attempts to execute a blocked syscall).
 */
class ContainmentViolationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
