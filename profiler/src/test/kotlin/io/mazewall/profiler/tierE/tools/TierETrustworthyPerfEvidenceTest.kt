package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierETrustworthyPerfEvidenceTest {
    @Test
    fun `perf sample counts stay bound to exact collector and workload PIDs`() {
        val directory = Files.createTempDirectory("tier-e-trust-perf-")
        try {
            Files.writeString(
                directory.resolve("results.jsonl"),
                """{"kind":"fork-output","variant":"dictionary_full_stream","fork":1,"target_pid":31,"output":"collector_pid=31 workload_pid=32 tier-e-perf-window: pid=31"}
""",
            )
            Files.writeString(directory.resolve("perf-pid-tid-samples.txt"), "java 31/31 cycles: 0x1 symbol dso\njava 31/32 cycles: 0x2 symbol dso\n")
            TierETrustworthyPerfEvidence.write(directory)
            val report = Json.parseToJsonElement(Files.readString(directory.resolve("perf-process-attribution.json"))).jsonObject
            val entries = report.getValue("entries").jsonArray
            assertEquals("2", entries[0].jsonObject.getValue("samples").toString())
            assertEquals("0", entries[1].jsonObject.getValue("samples").toString())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
