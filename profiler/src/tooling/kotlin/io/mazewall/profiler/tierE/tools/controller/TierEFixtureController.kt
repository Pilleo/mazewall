package io.mazewall.profiler.tierE.tools.controller

import io.mazewall.testing.containers.runContainerCommand
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Settles a killed fixture's in-flight, lock-guarded container creations before name reconciliation. */
public object TierEFixtureController {
    public fun awaitCreationQuiescence(
        controller: Process?,
        creationLock: Path,
        timeout: Duration = Duration.ofSeconds(30),
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        val creators = controller
            ?.toHandle()
            ?.descendants()
            ?.use { descendants ->
            descendants
                .filter {
                it
                .info()
                .commandLine()
                .orElse("")
                .contains(creationLock.toString())
            }.toList()
        }.orEmpty()
        controller?.destroyForcibly()
        if (controller != null && !controller.waitFor(2, TimeUnit.SECONDS)) {
            error("Tier E controller could not be stopped; creation unresolved: $creationLock")
        }
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) error("Tier E container creation lock could not be acquired: $creationLock")
        val seconds = (TimeUnit.NANOSECONDS.toSeconds(remaining) + 1).coerceAtLeast(1)
        val barrier = runContainerCommand(
            listOf(
                "flock",
                "-x",
                "-w",
                seconds.toString(),
                creationLock.toString(),
                "bash",
                "-c",
                "[[ \$(cat \"\$1\") == open ]] || exit 87; printf 'closed\\n' > \"\$1\"",
                "--",
                creationLock.resolveSibling("${creationLock.fileName}.state").toString(),
            ),
            Duration.ofNanos(remaining).plusSeconds(2),
        )
        check(barrier.exitCode == 0) { "Tier E container creation unresolved at $creationLock: ${barrier.output}" }
        for (creator in creators) {
            val until = deadline - System.nanoTime()
            if (until <= 0 ||
                !creator.onExit().let { future ->
                    try {
                        future.get(until, TimeUnit.NANOSECONDS)
                        true
                    } catch (_: java.util.concurrent.TimeoutException) {
                        false
                    }
                }
            ) {
                error("Tier E container creation still in flight; reconcile names before cleanup: $creationLock")
            }
        }
    }
}
