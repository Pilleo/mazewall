package io.mazewall.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClarifyStageTest {
    @Test
    fun notReadyAuthorGoesToDoneSkipped() {
        val stage = ClarifyPolicy.afterAuthor(
            ClarifyView(
                ready = false,
                questions = emptyList(),
                hitCount = 0,
                hasSideEffects = null,
                maxWeakRounds = 3,
                reviewVerdict = null,
            ),
        )
        assertEquals(ClarifyStage.Done("skipped"), stage)
    }

    @Test
    fun concreteHitsSkipSideEffectLlm() {
        val stage = ClarifyPolicy.afterAuthor(
            ClarifyView(
                ready = true,
                questions = emptyList(),
                hitCount = 3,
                hasSideEffects = true,
                maxWeakRounds = 3,
                reviewVerdict = null,
            ),
        )
        assertEquals(ClarifyStage.Review(0), stage)
        assertFalse(ClarifyPolicy.needSideEffectsLlm(true, 3))
        assertTrue(ClarifyPolicy.needSideEffectsLlm(true, 0))
        assertTrue(ClarifyPolicy.needSideEffectsLlm(false, 2))
    }

    @Test
    fun noProgressInvestigateGoesToLeftoverOrReview() {
        val view = ClarifyView(
            ready = true,
            questions = listOf("LRU or FIFO?"),
            hitCount = 0,
            hasSideEffects = false,
            maxWeakRounds = 3,
            reviewVerdict = null,
        )
        val stage = ClarifyPolicy.afterInvestigate(
            view,
            round = 1,
            previous = setOf("LRU or FIFO?"),
            next = setOf("LRU or FIFO?"),
        )
        assertEquals(ClarifyStage.Review(0), stage)
        assertTrue(ClarifyPolicy.noProgress(setOf("A"), setOf("A")))
        assertFalse(ClarifyPolicy.noProgress(setOf("A"), setOf("B")))
        assertFalse(ClarifyPolicy.noProgress(setOf("A"), emptySet()))
    }

    @Test
    fun factualLeftoversStayOnLeftoverStage() {
        val view = ClarifyView(
            ready = true,
            questions = listOf("Does Landlock ABI v4 exist on CI?", "LRU or FIFO?"),
            hitCount = 0,
            hasSideEffects = false,
            maxWeakRounds = 3,
            reviewVerdict = null,
        )
        assertEquals(ClarifyStage.Leftover, ClarifyPolicy.leftoverOrReview(view))
        assertEquals(
            ClarifyStage.Review(0),
            ClarifyPolicy.leftoverOrReview(view.copy(questions = listOf("LRU or FIFO?"))),
        )
    }

    @Test
    fun skipStrongReviewOnCheapPathOnly() {
        val cheap = ClarifyView(
            ready = true,
            questions = listOf("LRU or FIFO?"),
            hitCount = 0,
            hasSideEffects = false,
            maxWeakRounds = 1,
            reviewVerdict = null,
        )
        assertTrue(ClarifyPolicy.skipStrongReview(cheap))
        assertFalse(cheap.copy(needsKernel = true).let { ClarifyPolicy.skipStrongReview(it) })
        assertFalse(cheap.copy(coreLock = true).let { ClarifyPolicy.skipStrongReview(it) })
        assertFalse(cheap.copy(hasSideEffects = true).let { ClarifyPolicy.skipStrongReview(it) })
        assertFalse(
            cheap.copy(questions = listOf("Does Landlock ABI v4 exist on CI?"))
                .let { ClarifyPolicy.skipStrongReview(it) },
        )
    }
}
