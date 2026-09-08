package io.mazewall.enforcer.api

import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*

/** The strength of evidence behind a containment diagnostic. */
enum class ContainmentViolationEvidence {
    /** Mazewall observed the policy denial at its kernel-facing boundary. */
    OBSERVED_POLICY_DENIAL,

    /** A third-party exception resembled a permission failure but was not authenticated by Mazewall. */
    INFERRED_PERMISSION_FAILURE,

    /** The exception is structured but its origin was not recorded by this library version. */
    UNKNOWN,
}

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
 * @param evidence whether Mazewall observed the denial or only has incomplete provenance.
 */

class ContainmentViolationException(
    message: String,
    cause: Throwable? = null,
    val errno: Int? = null,
    val syscallNr: Int? = null,
    val evidence: ContainmentViolationEvidence = ContainmentViolationEvidence.UNKNOWN,
) : io.mazewall.enforcer.ContainmentViolationException(message, cause)
