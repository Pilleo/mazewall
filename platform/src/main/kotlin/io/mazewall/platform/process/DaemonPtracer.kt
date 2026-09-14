package io.mazewall.platform.process

import io.mazewall.platform.lifecycle.MazewallInternal
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeProcess
import io.mazewall.platform.syscall.PrctlCommand
import java.util.WeakHashMap

/**
 * Tracks the single Yama exception selected by mazewall daemon managers.
 *
 * An unchanged successful grant does not repeat prctl after a process baseline forbids
 * changing it. Switching daemon processes still requires a successful native call. This
 * records library-owned assignments; it cannot bypass kernel permission checks. Concurrent
 * independent supervisor and profiler sessions cannot share this single exception.
 */
@MazewallInternal
internal object DaemonPtracer {
    private val selected = WeakHashMap<NativeProcess, Process>()

    @Synchronized
    public fun authorize(
        engine: NativeEngine,
        daemon: Process,
    ) {
        require(daemon.pid() > 0L) { "A live daemon PID is required" }
        check(daemon.isAlive) { "Cannot authorize a terminated daemon" }
        val process = engine.process
        if (selected[process] === daemon) return
        process.prctl(PrctlCommand.SetPtracer(daemon.pid())).getOrThrow("prctl(PR_SET_PTRACER)")
        selected[process] = daemon
    }

    /** Forget a retired process identity without revoking a newer daemon's selection. */
    @Synchronized
    public fun retire(
        engine: NativeEngine,
        daemon: Process,
    ) {
        val process = engine.process
        if (selected[process] === daemon) selected.remove(process)
    }
}
