package io.mazewall.enforcer.internal.execution

internal enum class GenerationPhase { PREPARING, READY, DRAINING, FAILED, CLOSED }

internal enum class GenerationEvent { PREPARED, DRAIN, FAILED, RETIRED }

/** Pure lifecycle decisions; native stages remain owned by the canonical installer. */
internal object GenerationLifecycle {
    fun evaluate(
        phase: GenerationPhase,
        event: GenerationEvent,
    ): GenerationPhase =
        when (event) {
        GenerationEvent.PREPARED -> when (phase) {
            GenerationPhase.PREPARING -> GenerationPhase.READY
            GenerationPhase.READY, GenerationPhase.DRAINING, GenerationPhase.FAILED, GenerationPhase.CLOSED -> phase
        }
        GenerationEvent.DRAIN -> when (phase) {
            GenerationPhase.PREPARING, GenerationPhase.READY -> GenerationPhase.DRAINING
            GenerationPhase.DRAINING, GenerationPhase.FAILED, GenerationPhase.CLOSED -> phase
        }
        GenerationEvent.FAILED -> when (phase) {
            GenerationPhase.PREPARING, GenerationPhase.READY, GenerationPhase.DRAINING, GenerationPhase.FAILED -> GenerationPhase.FAILED
            GenerationPhase.CLOSED -> GenerationPhase.CLOSED
        }
        GenerationEvent.RETIRED -> GenerationPhase.CLOSED
    }
}
