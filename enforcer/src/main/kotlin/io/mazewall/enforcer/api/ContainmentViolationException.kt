package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

/**
 * Exception thrown when a contained task violates the configured policy
 * (e.g., attempts to execute a blocked syscall).
 *
 * Extends the historical `io.mazewall.enforcer.ContainmentViolationException` so
 * existing `catch` blocks on that type still match library throws.
 */
@Suppress("DEPRECATION")
class ContainmentViolationException(
    message: String,
    cause: Throwable? = null,
) : io.mazewall.enforcer.ContainmentViolationException(message, cause)
