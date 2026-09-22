package io.mazewall.profiler.internal.attribution

/**
 * Validates a retained-edge candidate as an observation admitted at capture time.
 *
 * Validation is historical: it checks the immutable session/process binding, the
 * recorded admission provenance, and the exact dictionary definitions. It never
 * reads or advances current task replay state, and a candidate is never replayed
 * as a current marker transition.
 */
internal object CoverageRecoveryValidator {
    sealed interface Result {
        data class Valid(
            val stack: StackDefinition,
            val invocation: InvocationDefinition,
        ) : Result

        data class Invalid(
            val reason: Reason,
        ) : Result
    }

    enum class Reason {
        SESSION_MISMATCH,
        INCARNATION_MISMATCH,
        UNKNOWN_INVOCATION,
        STACK_MISMATCH,
        UNKNOWN_STACK,
        CAPTURE_FAILED,
        STALE_DEFINITION,
    }

    fun validate(
        candidate: CoverageCandidate,
        expectedSession: SessionEpoch,
        expectedIncarnation: ProcessIncarnation,
        joiner: InvocationDictionaryJoiner,
    ): Result {
        if (candidate.sessionEpoch != expectedSession) return Result.Invalid(Reason.SESSION_MISMATCH)
        if (candidate.processIncarnation != expectedIncarnation) return Result.Invalid(Reason.INCARNATION_MISMATCH)
        val invocation = joiner.knownInvocation(candidate.invocationId)
            ?: return Result.Invalid(Reason.UNKNOWN_INVOCATION)
        if (invocation.captureFailure != null) return Result.Invalid(Reason.CAPTURE_FAILED)
        if (invocation.stackTraceId != candidate.canonicalStackTraceId) {
            return Result.Invalid(Reason.STACK_MISMATCH)
        }
        val stack = joiner.knownStack(invocation) ?: return Result.Invalid(Reason.UNKNOWN_STACK)
        if (stack.stackTraceId != candidate.canonicalStackTraceId) {
            return Result.Invalid(Reason.STALE_DEFINITION)
        }
        return Result.Valid(stack, invocation)
    }
}
