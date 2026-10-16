package io.mazewall.profiler.tierE.tools.controller

import java.nio.file.Path

/** Owns a short fixture until creation is settled; unresolved creation cannot trigger deletion. */
public class TierEFixtureLifecycle(
    private val owner: TierEContainerRun,
    private val creationLock: Path,
    private val settle: (Process?, Path) -> Unit = { controller, lock ->
        TierEFixtureController.awaitCreationQuiescence(controller, lock)
    },
    private val extraCleanup: () -> Unit = {},
    private val retainOnFailure: Boolean = false,
) {
    public fun run(exercise: () -> Unit) {
        runScoped(start = { null }) { exercise() }
    }

    public fun run(
        start: () -> Process,
        exercise: (Process) -> Unit,
    ) {
        runScoped(start) { controller -> exercise(requireNotNull(controller)) }
    }

    private fun runScoped(
        start: () -> Process?,
        exercise: (Process?) -> Unit,
    ) {
        var controller: Process? = null
        var primary: Throwable? = null
        try {
            controller = start()
            exercise(controller)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            var interrupted = Thread.interrupted()
            val failures = mutableListOf<Throwable>()

            fun attempt(block: () -> Unit) {
                interrupted = Thread.interrupted() || interrupted
                try {
                    block()
                } catch (failure: Throwable) {
                    if (failure is InterruptedException) interrupted = true
                    failures += failure
                }
            }
            try {
                var settled = false
                attempt {
                    settle(controller, creationLock)
                    settled = true
                }
                if (settled && !(retainOnFailure && primary != null)) {
                    attempt { owner.close() }
                } else {
                    attempt { owner.stopForRecovery() }
                }
                attempt(extraCleanup)
            } finally {
                if (Thread.interrupted() || interrupted) Thread.currentThread().interrupt()
            }
            if (primary != null) {
                failures.forEach(primary::addSuppressed)
            } else if (failures.isNotEmpty()) {
                val first = failures.first()
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }
}
