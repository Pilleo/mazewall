package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierETrustworthySourceStateTest {
    @Test
    fun `docs only dirt does not become a benchmark input change`() {
        val root = Files.createTempDirectory("tier-e-source-state-")
        try {
            check(ProcessBuilder("git", "init", "-q", root.toString()).start().waitFor() == 0)
            val docs = Files.createDirectories(root.resolve("docs")).resolve("notes.md")
            Files.writeString(docs, "operator-notes")
            val state = TierETrustworthySourceState.read(root)
            assertEquals("1", state.getValue("all_dirty_count").toString())
            assertEquals("0", state.getValue("benchmark_input_dirty_count").toString())
            Files.writeString(Files.createDirectories(root.resolve("profiler")).resolve("run.kt"), "source")
            assertEquals("1", TierETrustworthySourceState.read(root).getValue("benchmark_input_dirty_count").toString())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
