package io.mazewall.enforcer.internal.supervisor

/** Independent of the operation thread: owner death releases even a socket blocked in native accept. */
internal object SocketHelperOwner {
    private const val OWNER_EXIT = 125

    fun watch(expectedParentPid: Long) {
        val parent = ProcessHandle.current().parent().orElse(null)
        if (parent == null || parent.pid() != expectedParentPid || !parent.isAlive) {
            ownerExited()
        }
        // ProcessHandle retains process identity, not just a reusable PID. Register before connecting
        // or receiving descriptors; onExit also completes if the parent died during registration.
        parent.onExit().whenComplete { _, _ -> ownerExited() }
    }

    private fun ownerExited(): Nothing {
        Runtime.getRuntime().halt(OWNER_EXIT)
        error("Runtime.halt unexpectedly returned")
    }
}
