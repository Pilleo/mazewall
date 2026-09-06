package io.mazewall.portal.worker

/**
 * Performs worker setup in the order required by the containment boundary.
 *
 * The filesystem rule is applied before Seccomp, then generated dispatch is
 * constructed only after process-wide containment is in force.  Callers must
 * emit their ready signal only after this method returns successfully.
 */
internal object PortalWorkerStartup {
    fun prepare(
        installFilesystem: () -> Unit,
        createWorkerThreads: () -> Unit,
        installProcessContainment: () -> Unit,
        bootstrapDispatchers: () -> Int,
    ): Int {
        installFilesystem()
        createWorkerThreads()
        installProcessContainment()
        return bootstrapDispatchers()
    }
}
