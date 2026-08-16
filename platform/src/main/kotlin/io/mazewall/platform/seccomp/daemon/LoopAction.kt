package io.mazewall.platform.seccomp.daemon

/**
 * Result of a reactor loop iteration.
 */
public sealed class LoopAction {
    public object Continue : LoopAction()
    public object Break : LoopAction()
    public object Shutdown : LoopAction()
}
