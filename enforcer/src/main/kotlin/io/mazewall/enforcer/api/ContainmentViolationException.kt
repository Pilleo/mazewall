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
 *
 * ### Structured taxonomy (issue-20260823-171958)
 * When mazewall itself observes the kernel decision (supervisor verdicts, raw-syscall wrappers),
 * violations carry [errno] and [syscallNr] so consumers never need to parse messages.
 * [io.mazewall.enforcer.diagnostics.ContainmentViolationDetector] matches this type structurally
 * FIRST; locale-sensitive message regexes are only a fallback for third-party exceptions that
 * cross the boundary from JDK/library internals, where no structured signal exists.
 *
 * @param errno kernel errno of the denied decision, when known (see `NativeConstants`).
 * @param syscallNr syscall number whose execution was denied, when known.
 */
@Suppress("DEPRECATION")
class ContainmentViolationException(
    message: String,
    cause: Throwable? = null,
    val errno: Int? = null,
    val syscallNr: Int? = null,
) : io.mazewall.enforcer.ContainmentViolationException(message, cause)
