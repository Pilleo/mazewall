package io.mazewall.orchestrator

internal sealed interface ClarifyStage {
    data object Author : ClarifyStage
    data object SideEffectLlm : ClarifyStage
    data class Investigate(val round: Int) : ClarifyStage
    data object Leftover : ClarifyStage
    data class Review(val loop: Int) : ClarifyStage
    data class Done(val verdict: String?) : ClarifyStage
}

internal data class ClarifyView(
    val ready: Boolean,
    val questions: List<String>,
    val hitCount: Int,
    val hasSideEffects: Boolean?,
    val maxWeakRounds: Int,
    val reviewVerdict: String?,
    val needsKernel: Boolean = false,
    val coreLock: Boolean = false,
)

internal object ClarifyPolicy {
    fun afterAuthor(view: ClarifyView): ClarifyStage {
        if (!view.ready) return ClarifyStage.Done("skipped")
        if (needSideEffectsLlm(view.hasSideEffects, view.hitCount)) return ClarifyStage.SideEffectLlm
        return afterImpact(view, investigateRound = 1)
    }

    fun afterImpact(view: ClarifyView, investigateRound: Int): ClarifyStage {
        if (view.questions.isNotEmpty()) {
            return if (investigateRound <= view.maxWeakRounds) {
                ClarifyStage.Investigate(investigateRound)
            } else {
                ClarifyStage.Leftover
            }
        }
        return leftoverOrReview(view)
    }

    fun afterInvestigate(view: ClarifyView, round: Int, previous: Set<String>, next: Set<String>): ClarifyStage {
        if (noProgress(previous, next)) return leftoverOrReview(view)
        if (next.isNotEmpty() && round < view.maxWeakRounds) {
            return ClarifyStage.Investigate(round + 1)
        }
        return leftoverOrReview(view)
    }

    fun leftoverOrReview(view: ClarifyView): ClarifyStage {
        val factual = view.questions.filter { questionKind(it) == QuestionKind.FACTUAL }
        return if (factual.isNotEmpty()) ClarifyStage.Leftover else ClarifyStage.Review(0)
    }

    fun skipStrongReview(view: ClarifyView): Boolean {
        if (!view.ready) return true
        if (view.needsKernel || view.coreLock) return false
        if (view.hasSideEffects == true) return false
        return view.questions.none { questionKind(it) == QuestionKind.FACTUAL }
    }

    fun needSideEffectsLlm(hasSideEffects: Boolean?, hitCount: Int): Boolean =
        (hasSideEffects == true && hitCount == 0) || (hasSideEffects == false && hitCount > 0)

    fun noProgress(previous: Set<String>, next: Set<String>): Boolean =
        (previous - next).isEmpty() && next.isNotEmpty()
}
