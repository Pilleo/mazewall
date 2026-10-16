package io.mazewall.platform.seccomp.daemon

/**
 * Result of a reactor loop iteration.
 */
internal sealed class LoopAction {
    object Continue : LoopAction()

    object Break : LoopAction()

    object Shutdown : LoopAction()
}
