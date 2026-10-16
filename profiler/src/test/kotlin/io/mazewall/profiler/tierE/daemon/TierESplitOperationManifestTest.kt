package io.mazewall.profiler.tierE.daemon

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TierESplitOperationManifestTest {
    @TempDir lateinit var root: Path

    @Test
    fun `atomic benchmark manifest binds session and exact operation ids`() {
        TierESplitOperationManifestFileSink(root).accept(
            TierESplitOperationManifest("orders", 19802, "FULL_STREAM", listOf(14, 19), 0),
        )

        val json = Files.readString(root.resolve("orders-19802.tier-e-operations.json"))
        assertTrue(json.contains("\"exact_directory_fds\":[14,19]"))
        assertTrue(json.contains("\"representative_only\":false"))
        assertTrue(json.contains("\"policy_eligibility\":false"))
    }

    @Test
    fun `rejects unsafe identity and oversized rows`() {
        assertFailsWith<IllegalArgumentException> {
            TierESplitOperationManifest("../escape", 19802, "FULL_STREAM", emptyList(), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            TierESplitOperationManifest("orders", 19802, "FULL_STREAM", List(100_001) { it.toLong() }, 0)
        }
    }
}
