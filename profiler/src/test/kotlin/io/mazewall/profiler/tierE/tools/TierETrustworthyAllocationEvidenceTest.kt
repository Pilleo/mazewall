package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierETrustworthyAllocationEvidenceTest {
    @Test
    fun `allocation probes retain lower-bound requested bytes and fork identity`() {
        val directory = Files.createTempDirectory("tier-e-trust-allocation-")
        try {
            Files.writeString(
                directory.resolve("results.jsonl"),
                """{"kind":"fork-output","variant":"dictionary_full_stream","fork":1,"target_pid":31,"workload_correctness":"PASS","whole_session_evidence":"COMPLETE"}
""",
            )
            Files.writeString(
                directory.resolve("allocation-events.txt"),
                "java 31 [000] time: probe_libc:malloc: size=5\njava 31 [000] time: probe_libc:malloc: size=7\n",
            )
            TierETrustworthyAllocationEvidence.write(directory)
            val entry = Json
                .parseToJsonElement(Files.readString(directory.resolve("allocation-summary.json")))
                .jsonObject
                .getValue("entries")
                .jsonArray[0]
                .jsonObject
            assertEquals("2", entry.getValue("allocation_count").toString())
            assertEquals("12", entry.getValue("requested_bytes").toString())
            assertEquals("\"PASS\"", entry.getValue("workload_correctness").toString())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
