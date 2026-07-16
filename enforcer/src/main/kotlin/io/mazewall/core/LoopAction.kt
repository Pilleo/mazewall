package io.mazewall.core

/**
 * Common loop control actions for reactor-style engines.
 */
public sealed interface LoopAction {
    public data object Continue : LoopAction
    public data object Break : LoopAction
    public data object Shutdown : LoopAction
}
