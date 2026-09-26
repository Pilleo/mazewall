package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierERootfulSourceTransferTest {
    @Test
    fun `tracked and untracked scoped source are both transferred to the guest tar`() {
        val root = Files.createTempDirectory("tier-e-source-transfer-")
        try {
            check(ProcessBuilder("git", "init", "-q", root.toString()).start().waitFor() == 0)
            val tracked = Files.createDirectories(root.resolve("profiler")).resolve("entry.kt")
            val untracked = Files.createDirectories(root.resolve("scripts")).resolve("scenario.sh")
            Files.writeString(tracked, "tracked")
            Files.writeString(untracked, "untracked")
            check(ProcessBuilder("git", "-C", root.toString(), "add", "profiler/entry.kt").start().waitFor() == 0)
            val tar = root.resolve("source.tar")
            TierERootfulSourceTransfer.archive(root, tar)
            val listing = ProcessBuilder("tar", "-tf", tar.toString())
                .start()
                .inputStream
                .bufferedReader()
                .readText()
            assertTrue("profiler/entry.kt" in listing)
            assertTrue("scripts/scenario.sh" in listing)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
