package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.Profiler
import java.nio.file.Files

/** Controlled slow-oracle check for the equivalent file workload. */
public object UserNotifOracleSmoke {
    @JvmStatic
    public fun main(args: Array<String>) {
        val iterations = args.singleOrNull()?.toInt() ?: 1
        var workloadStarted = 0L
        var workloadFinished = 0L
        val result = Profiler.profile(processWide = false, captureStackTraces = true) {
            workloadStarted = System.nanoTime()
            val file = Files.createTempFile("mazewall-user-notif-oracle", ".txt")
            try {
                repeat(iterations) {
                    Files.writeString(file, "stack attribution smoke test")
                    check(Files.readString(file) == "stack attribution smoke test")
                }
            } finally {
                Files.deleteIfExists(file)
                workloadFinished = System.nanoTime()
            }
        }
        val elapsed = workloadFinished - workloadStarted
        val stackCount = result.stackProfile.values.sumOf(List<Array<StackTraceElement>>::size)
        println(
            "user-notif-oracle: observations=${result.observations.size} stacks=$stackCount " +
            "dropped=${result.coverage.droppedEvents} complete=${result.coverage.complete} workloadNs=$elapsed",
        )
        check(result.observations.isNotEmpty()) { "USER_NOTIF oracle observed no syscalls" }
        check(stackCount > 0) { "USER_NOTIF oracle captured no stacks" }
        check(result.coverage.droppedEvents == 0) { "USER_NOTIF oracle dropped events" }
    }
}
