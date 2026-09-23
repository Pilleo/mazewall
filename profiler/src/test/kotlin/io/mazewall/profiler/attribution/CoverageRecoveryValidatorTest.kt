package io.mazewall.profiler.internal.attribution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageRecoveryValidatorTest {
    private val session = SessionEpoch(19802)
    private val incarnation = ProcessIncarnation(999)
    private val stackId = StackTraceId(7)
    private val invocationId = InvocationId(55)

    private fun joinerWithDefinitions(captureFailure: CaptureFailure? = null): InvocationDictionaryJoiner {
        val joiner = InvocationDictionaryJoiner(maxPendingRecords = 100)
        joiner.registerStack(StackDefinition(stackId, emptyList(), StackCaptureQuality.COMPLETE))
        joiner.registerInvocation(
            InvocationDefinition(
                invocationId = invocationId,
                parentInvocationId = null,
                stackTraceId = if (captureFailure == null) stackId else null,
                captureFailure = captureFailure,
                executionContext = null,
            ),
        )
        return joiner
    }

    private fun candidate(
        sessionEpoch: SessionEpoch = session,
        processIncarnation: ProcessIncarnation = incarnation,
        stackTraceId: StackTraceId = stackId,
        invocationId: InvocationId = this.invocationId,
    ) = CoverageCandidate(
        sessionEpoch = sessionEpoch,
        canonicalStackTraceId = stackTraceId,
        syscallNumber = 257,
        captureFlags = 0,
        observedKtimeNanos = 123L,
        tgid = 100,
        tid = 101,
        invocationId = invocationId,
        markerSequence = 2,
        args = listOf(-100, 1, 2, 3, 4, 5),
        processIncarnation = processIncarnation,
        attachmentGeneration = 1,
    )

    @Test
    fun `valid historical candidate resolves without touching replay state`() {
        val result = CoverageRecoveryValidator.validate(candidate(), session, incarnation, joinerWithDefinitions())

        assertTrue(result is CoverageRecoveryValidator.Result.Valid)
        assertEquals(stackId, result.stack.stackTraceId)
        assertEquals(invocationId, result.invocation.invocationId)
    }

    @Test
    fun `stale session and incarnation never become recovered coverage`() {
        assertEquals(
            CoverageRecoveryValidator.Result.Invalid(CoverageRecoveryValidator.Reason.SESSION_MISMATCH),
            CoverageRecoveryValidator.validate(candidate(sessionEpoch = SessionEpoch(1)), session, incarnation, joinerWithDefinitions()),
        )
        assertEquals(
            CoverageRecoveryValidator.Result.Invalid(CoverageRecoveryValidator.Reason.INCARNATION_MISMATCH),
            CoverageRecoveryValidator.validate(candidate(processIncarnation = ProcessIncarnation(1)), session, incarnation, joinerWithDefinitions()),
        )
    }

    @Test
    fun `unknown invocation and stack mismatch are rejected`() {
        assertEquals(
            CoverageRecoveryValidator.Result.Invalid(CoverageRecoveryValidator.Reason.UNKNOWN_INVOCATION),
            CoverageRecoveryValidator.validate(candidate(invocationId = InvocationId(404)), session, incarnation, joinerWithDefinitions()),
        )
        assertEquals(
            CoverageRecoveryValidator.Result.Invalid(CoverageRecoveryValidator.Reason.STACK_MISMATCH),
            CoverageRecoveryValidator.validate(candidate(stackTraceId = StackTraceId(9)), session, incarnation, joinerWithDefinitions()),
        )
    }

    @Test
    fun `capture failure never becomes recovered coverage`() {
        val result = CoverageRecoveryValidator.validate(
            candidate(),
            session,
            incarnation,
            joinerWithDefinitions(captureFailure = CaptureFailure.STACK_WALK_FAILED),
        )

        assertEquals(CoverageRecoveryValidator.Result.Invalid(CoverageRecoveryValidator.Reason.CAPTURE_FAILED), result)
    }
}
