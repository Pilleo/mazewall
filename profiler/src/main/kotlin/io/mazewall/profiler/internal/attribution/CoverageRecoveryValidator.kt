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
        val invocation = joiner.knownInvocation(candidate.invocationId)
        val stack = invocation?.let(joiner::knownStack)
        return when {
            candidate.sessionEpoch != expectedSession -> Result.Invalid(Reason.SESSION_MISMATCH)
            candidate.processIncarnation != expectedIncarnation -> Result.Invalid(Reason.INCARNATION_MISMATCH)
            candidate.attachmentGeneration <= 0 || candidate.captureFlags and 1.inv() != 0 -> Result.Invalid(Reason.STALE_DEFINITION)
            invocation == null -> Result.Invalid(Reason.UNKNOWN_INVOCATION)
            invocation.captureFailure != null -> Result.Invalid(Reason.CAPTURE_FAILED)
            invocation.stackTraceId != candidate.canonicalStackTraceId -> Result.Invalid(Reason.STACK_MISMATCH)
            stack == null -> Result.Invalid(Reason.UNKNOWN_STACK)
            stack.stackTraceId != candidate.canonicalStackTraceId -> Result.Invalid(Reason.STALE_DEFINITION)
            else -> Result.Valid(stack, invocation)
        }
    }
}
