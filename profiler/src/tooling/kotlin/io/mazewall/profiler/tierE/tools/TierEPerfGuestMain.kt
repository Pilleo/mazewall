package io.mazewall.profiler.tierE.tools

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Runs external perf tools inside a disposable analysis container after benchmark completion. */
public object TierEPerfGuestMain {
    @JvmStatic
    public fun main(args: Array<String>) {
    require(args.size == 1) { "Expected perf or allocation analysis mode" }
    val mode = args[0]
    val artifact = Path.of("/artifacts")
    if (mode == "perf") {
        val maps = artifact.resolve("perf-maps")
        if (Files.isDirectory(maps)) {
            Files.list(maps).use { files ->
            files
                .filter { it.fileName.toString().matches(Regex("perf-[0-9]+\\.map")) }
                .forEach { Files.copy(it, Path.of("/tmp").resolve(it.fileName)) }
        }
        }
    }
    val schedule = TierEPerfAnalysisPlan.commands(mode)
    var primary: Throwable? = null
    var probeOwned = false
    try {
        for ((index, command) in schedule.withIndex()) {
            val output = when (mode) {
                "perf" -> when (index) {
                    1 -> artifact.resolve("perf-report.txt")
                    2 -> artifact.resolve("perf-pid-tid-samples.txt")
                    3 -> artifact.resolve("perf-timed-samples.txt")
                    else -> null
                }
                else -> if (index == 1) artifact.resolve("allocation-events.txt") else null
            }
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply {
                if (output != null) redirectOutput(output.toFile()) else inheritIO()
            }.start()
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
                error("Perf analysis timed out: ${command.first()}")
            }
            check(process.exitValue() == 0) { "Perf analysis command failed: $command" }
            if (mode == "allocation" && index == 0) probeOwned = true
        }
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        if (probeOwned) {
            try {
                val cleanup = ProcessBuilder("perf", "probe", "--del", "probe_libc:malloc").inheritIO().start()
                check(cleanup.waitFor(20, TimeUnit.SECONDS) && cleanup.exitValue() == 0) { "Allocation probe cleanup failed" }
            } catch (cleanup: Throwable) {
                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
    }
}
