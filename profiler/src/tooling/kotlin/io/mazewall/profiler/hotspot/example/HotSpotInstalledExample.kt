package io.mazewall.profiler.hotspot.example

import io.mazewall.profiler.api.HotSpotOptions
import io.mazewall.profiler.api.MazewallProfiler
import io.mazewall.profiler.api.ProfileOptions
import io.mazewall.profiler.api.ProfileStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path

/** Installed public-facade example; the operator supplies the literal helper argv prefix. */
internal object HotSpotInstalledExample {
    private const val OPERATIONS = 20
    private const val WARMUPS = 40
    private const val PACE_MILLIS = 100L

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Usage: HotSpotInstalledExample <assets> <output-directory> <prefix-json-file>" }
        val assets = Path.of(args[0]).toAbsolutePath()
        val output = Path.of(args[1]).toAbsolutePath()
        val prefix = Json.parseToJsonElement(Files.readString(Path.of(args[2]))).jsonArray.map { it.jsonPrimitive.content }
        Files.createDirectories(output)
        val paths = List(OPERATIONS) { index ->
            output.resolve("operation-$index-${if (index % 2 == 0) "A" else "B"}").also { Files.write(it, byteArrayOf(1)) }
        }
        val warm = output.resolve("warm").also { Files.write(it, byteArrayOf(1)) }
        val settings = ProfileOptions(strategy = ProfileStrategy.HOTSPOT_AGENTLESS, processWide = true)
        MazewallProfiler.open(settings, HotSpotOptions(assets, prefix)).use { profiler ->
            val result = profiler.profile {
                repeat(WARMUPS) { index ->
                    call(index, warm)
                    Thread.sleep(PACE_MILLIS)
                }
                paths.forEachIndexed { index, path ->
                    call(index, path)
                    Thread.sleep(PACE_MILLIS)
                }
                "workload-value"
            }
            val evidence = requireNotNull(result.structuredEvidence)
            check(result.value == "workload-value" && !result.coverage.complete)
            check(evidence.finalized && evidence.summary.physicalDrainComplete)
            check(evidence.records.any { record -> record.evidence.frames.any { it.functionName.contains("HotSpotInstalledExample") } })
            Files.writeString(output.resolve("evidence.json"), evidence.toJson())
            Files.writeString(output.resolve("result.txt"), "${result.value}\nrecords=${evidence.records.size}\ncoverage=incomplete\n")
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
