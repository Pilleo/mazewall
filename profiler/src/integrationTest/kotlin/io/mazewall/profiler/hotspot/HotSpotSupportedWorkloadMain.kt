package io.mazewall.profiler.hotspot

import io.mazewall.profiler.api.HotSpotOptions
import io.mazewall.profiler.api.HotSpotProfilingException
import io.mazewall.profiler.api.MazewallProfiler
import io.mazewall.profiler.api.ProfileOptions
import io.mazewall.profiler.api.ProfileStrategy
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path

/** Runs only in the explicitly selected pinned interpreter/Serial fixture JVM. */
object HotSpotSupportedWorkloadMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val assets = Path.of(args[0])
        val output = Path.of(args[1])
        val socket = args[2]
        val name = args[3]
        val prefix = listOf(
            "docker",
            "--host",
            socket,
            "run",
            "--pull=never",
            "--rm",
            "-i",
            "--name",
            name,
            "--privileged",
            "--pid=host",
            "--mount",
            "type=bind,src=$assets,dst=$assets,readonly",
            "--mount",
            "type=bind,src=/sys,dst=/sys,readonly",
            HotSpotAcceptanceHarness.GO,
        )
        val paths = (0 until 20).map { index -> output.resolve("current-$index-${if (index % 2 == 0) "A" else "B"}").also { Files.write(it, byteArrayOf(1)) } }
        val warm = output.resolve("warm").also { Files.write(it, byteArrayOf(1)) }
        val uid = Files
            .readString(Path.of("/proc/self/status"))
            .lineSequence()
            .first { it.startsWith("Uid:") }
            .split(Regex("\\s+"))[1]
            .toInt()
        check(uid != 0) { "Current-JVM target must remain ordinary user" }
        val options = ProfileOptions(strategy = ProfileStrategy.HOTSPOT_AGENTLESS, processWide = true)
        val admissionStart = System.nanoTime()
        Files.writeString(output.resolve("current-progress.txt"), "admitting\n")
        try {
            MazewallProfiler.open(options, HotSpotOptions(assets, prefix)).use { profiler ->
            val result = profiler.profile {
                Files.writeString(output.resolve("current-progress.txt"), "ready admission_ns=${System.nanoTime() - admissionStart}\n")
                repeat(40) { index ->
                    call(index, warm)
                    Thread.sleep(100)
                }
                paths.forEachIndexed { index, path ->
                    call(index, path)
                    Thread.sleep(100)
                }
                "workload-value"
            }
            check(result.value == "workload-value" && !result.coverage.complete)
            val evidence = requireNotNull(result.structuredEvidence)
            check(evidence.finalized && evidence.summary.physicalDrainComplete)
            Files.writeString(output.resolve("current-evidence.json"), evidence.toJson())
            check(evidence.records.count { Regex("/current-[0-9]+-[AB]$").containsMatchIn(it.event.filename) } == 20)
            check(evidence.records.any { record -> record.evidence.frames.any { it.functionName.contains("HotSpotSupportedWorkloadMain") } })
            Files.writeString(output.resolve("current-result.txt"), "${result.value}\nuid=$uid\nrecords=${evidence.records.size}\n")
        }
        } catch (failure: HotSpotProfilingException) {
            failure.partialResult?.structuredEvidence?.let { Files.writeString(output.resolve("current-failed-evidence.json"), it.toJson()) }
            Files.writeString(output.resolve("current-failure.txt"), "pending=${failure.pendingOwnership}\n${failure.diagnostics}\n")
            throw failure
        }
    }

    private fun call(
        index: Int,
        path: Path,
    ) {
        if (index % 2 == 0) pathA(path) else pathB(path)
    }

    private fun pathA(path: Path) = leaf(path)

    private fun pathB(path: Path) = bridge(path)

    private fun bridge(path: Path) = leaf(path)

    private fun leaf(path: Path) {
        FileInputStream(path.toFile()).use { check(it.read() == 1) }
    }
}
