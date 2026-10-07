package io.mazewall.enforcer.internal

import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector

/** The existing detector is shared by borrowed and owned execution boundaries. */
internal fun containmentFailureForCaller(failure: Throwable): Throwable {
    if (failure !is Exception) return failure
    val diagnostic = ContainmentViolationDetector.diagnose(failure) ?: return failure
    val structured = diagnostic.violation
    return if (failure === structured) {
        checkNotNull(structured)
    } else {
        ContainmentViolationException(
        message = "Task reported a containment policy violation",
        cause = failure,
        errno = structured?.errno,
        syscallNr = structured?.syscallNr,
        evidence = diagnostic.evidence,
    )
    }
}
