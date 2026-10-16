package io.mazewall.profiler.tierE.tools

import java.nio.file.Files
import java.nio.file.Path

/** A failed controller is never reclassified as a successful measured arm. */
public object TierEArmAttempt {
    public fun run(
        artifact: Path,
        execute: () -> Unit,
    ): Int =
        try {
        execute()
        0
    } catch (failure: Exception) {
        try {
            Files.writeString(artifact.resolve("runner-failure.txt"), failure.stackTraceToString())
        } catch (recordFailure: Exception) {
            failure.addSuppressed(recordFailure)
            throw failure
        }
        1
    }
}
