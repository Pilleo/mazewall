package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.internal.execution.ScopingPreparation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OwnedLiveScopingTest {
    @Test
    fun `construction stays counted for drain and rejects premature release`() {
        val state = ScopingPreparation.State.Constructing()
        val draining = ScopingPreparation.evaluate(state, ScopingPreparation.Event.DrainRequested)
        assertSame(state, draining.state)
        val releasing = ScopingPreparation.evaluate(state, ScopingPreparation.Event.ReleaseRequested)
        assertSame(state, releasing.state)
        assertIs<ScopingPreparation.Effect.Reject>(releasing.effects.single())
    }

    @Test
    fun `settled claim preserves initial waiter failure without cleanup effects`() {
        val identity = Any()
        val original = IllegalStateException("settled failure")
        val fatal = LinkageError("late waiter")
        val settled = ScopingPreparation.State.PreparedSettled(original, identity)
        val origin = assertIs<ScopingPreparation.ObservationOrigin.Claim>(ScopingPreparation.observationOrigin(settled))
        assertSame(identity, origin.identity)
        val event = ScopingPreparation.Event.PrepareWaitFailed(ScopingPreparation.WaitObservation.Initial(fatal))
        val first = ScopingPreparation.evaluate(settled, event)
        val updated = assertIs<ScopingPreparation.State.PreparedSettled>(first.state)
        assertSame(identity, updated.observationIdentity)
        assertSame(fatal, updated.failure)
        assertEquals(1, fatal.suppressed.count { it === original })
        assertTrue(first.effects.isEmpty())
        val again = ScopingPreparation.evaluate(updated, event)
        assertSame(fatal, assertIs<ScopingPreparation.State.PreparedSettled>(again.state).failure)
        assertEquals(1, fatal.suppressed.count { it === original })
        assertTrue(again.effects.isEmpty())
    }

    @Test
    fun `initial waiter failure before a claim is rejected without effects`() {
        val constructing = ScopingPreparation.State.Constructing()
        val failure = IllegalStateException("unadmitted waiter")
        val result = ScopingPreparation.evaluate(
            constructing,
            ScopingPreparation.Event.PrepareWaitFailed(ScopingPreparation.WaitObservation.Initial(failure)),
        )
        assertSame(constructing, result.state)
        assertIs<ScopingPreparation.Effect.Reject>(result.effects.single())
        assertTrue(failure.suppressed.isEmpty())
    }

    @Test
    fun `settled observation follows suppressed identity through cycles but never causes`() {
        val identity = Any()
        val original = IllegalStateException("original")
        val lookalike = IllegalStateException("original")
        val fatal = LinkageError("retained fatal")
        val intermediate = IllegalStateException("intermediate")
        fatal.addSuppressed(intermediate)
        intermediate.addSuppressed(fatal)
        intermediate.addSuppressed(original)
        val observer = IllegalStateException("observer")
        val settled = ScopingPreparation.State.PreparedSettled(fatal, identity)
        val wrong = ScopingPreparation.Event.PrepareWaitFailed(
            ScopingPreparation.WaitObservation.HistoryObservation(lookalike, observer, ScopingPreparation.ObservationOrigin.Claim(identity)),
        )
        val rejected = ScopingPreparation.evaluate(settled, wrong)
        assertSame(settled, rejected.state)
        assertIs<ScopingPreparation.Effect.Reject>(rejected.effects.single())
        assertEquals(listOf(intermediate), fatal.suppressed.toList())
        assertEquals(listOf(fatal, original), intermediate.suppressed.toList())
        assertTrue(original.suppressed.isEmpty() && lookalike.suppressed.isEmpty() && observer.suppressed.isEmpty())

        var causeReads = 0
        val causeOnly = object : Throwable("cause-only", original) {
            override val cause: Throwable?
                get() {
                    causeReads++
                    throw AssertionError("cause must not be inspected")
                }
        }
        val causeState = ScopingPreparation.State.PreparedSettled(causeOnly, identity)
        val event = ScopingPreparation.Event.PrepareWaitFailed(
            ScopingPreparation.WaitObservation.HistoryObservation(original, observer, ScopingPreparation.ObservationOrigin.Claim(identity)),
        )
        val causeRejected = ScopingPreparation.evaluate(causeState, event)
        assertSame(causeState, causeRejected.state)
        assertIs<ScopingPreparation.Effect.Reject>(causeRejected.effects.single())
        assertEquals(0, causeReads)
        assertTrue(causeOnly.suppressed.isEmpty() && original.suppressed.isEmpty() && observer.suppressed.isEmpty())

        val accepted = ScopingPreparation.evaluate(settled, event)
        assertSame(fatal, assertIs<ScopingPreparation.State.PreparedSettled>(accepted.state).failure)
        assertTrue(accepted.effects.isEmpty())
        assertEquals(1, fatal.suppressed.count { it === observer })
        assertEquals(listOf(fatal, original), intermediate.suppressed.toList())
        val repeated = ScopingPreparation.evaluate(accepted.state, event)
        assertSame(fatal, assertIs<ScopingPreparation.State.PreparedSettled>(repeated.state).failure)
        assertTrue(repeated.effects.isEmpty())
        assertEquals(1, fatal.suppressed.count { it === observer })
        assertEquals(0, causeReads)
    }

    @Test
    fun `settled claim accepts only matching delayed failure observation`() {
        val identity = String(charArrayOf('o', 'w', 'n', 'e', 'r'))
        val equalButDistinct = String(charArrayOf('o', 'w', 'n', 'e', 'r'))
        assertEquals(identity, equalButDistinct)
        assertTrue(identity !== equalButDistinct)
        val original = IllegalStateException("original wait failure")
        val fatal = LinkageError("observer failure")
        val settled = ScopingPreparation.State.PreparedSettled(original, identity)
        val wrongOrigin = ScopingPreparation.Event.PrepareWaitFailed(
            ScopingPreparation.WaitObservation.HistoryObservation(
                original,
                fatal,
                ScopingPreparation.ObservationOrigin.Claim(equalButDistinct),
            ),
        )
        val rejected = ScopingPreparation.evaluate(settled, wrongOrigin)
        assertSame(settled, rejected.state)
        assertIs<ScopingPreparation.Effect.Reject>(rejected.effects.single())
        assertSame(original, settled.failure)
        assertTrue(original.suppressed.isEmpty() && fatal.suppressed.isEmpty())

        val absent = ScopingPreparation.Event.PrepareWaitFailed(
            ScopingPreparation.WaitObservation.HistoryObservation(
                IllegalStateException("not retained"),
                fatal,
                ScopingPreparation.ObservationOrigin.Claim(identity),
            ),
        )
        val missing = ScopingPreparation.evaluate(settled, absent)
        assertSame(settled, missing.state)
        assertIs<ScopingPreparation.Effect.Reject>(missing.effects.single())
        assertSame(original, settled.failure)
        assertTrue(original.suppressed.isEmpty() && fatal.suppressed.isEmpty())

        val valid = ScopingPreparation.Event.PrepareWaitFailed(
            ScopingPreparation.WaitObservation.HistoryObservation(
                original,
                fatal,
                ScopingPreparation.ObservationOrigin.Claim(identity),
            ),
        )
        val accepted = ScopingPreparation.evaluate(settled, valid)
        val updated = assertIs<ScopingPreparation.State.PreparedSettled>(accepted.state)
        assertSame(identity, updated.observationIdentity)
        assertSame(fatal, updated.failure)
        assertTrue(accepted.effects.isEmpty())
        assertEquals(1, fatal.suppressed.count { it === original })
        val repeated = ScopingPreparation.evaluate(updated, valid)
        assertSame(fatal, assertIs<ScopingPreparation.State.PreparedSettled>(repeated.state).failure)
        assertTrue(repeated.effects.isEmpty())
        assertEquals(1, fatal.suppressed.count { it === original })
    }

    @Test
    fun `ownerless construction failure settles without fabricating an owner`() {
        val failure = IllegalStateException("original")
        val result = ScopingPreparation.evaluate(ScopingPreparation.State.Constructing(), ScopingPreparation.Event.ConstructionFailed(failure))
        assertSame(failure, assertIs<ScopingPreparation.State.PreparedSettled>(result.state).failure)
        assertTrue(result.effects.single() is ScopingPreparation.Effect.Notify)
    }
}
