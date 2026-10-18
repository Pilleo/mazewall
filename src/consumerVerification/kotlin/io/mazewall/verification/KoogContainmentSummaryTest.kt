package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KoogContainmentSummaryTest {
    private val line = "KOOG_CONTAINMENT_V1_OK read=denied write=denied symlink=denied network=denied process=denied controls=pass retirement=pass"

    @Test
    fun `accepts one complete native summary`() {
        assertEquals(line, requireKoogContainmentSummary("build noise\n$line\n"))
    }

    @Test
    fun `rejects missing duplicate partial and contradictory native summaries`() {
        val invalid = listOf(
            "", "KOOG_NATIVE_FRESH_OK", "$line\n$line",
            line.substringBefore(" retirement="),
            line.replace("controls=pass", "controls=fail"),
            line.replace("retirement=pass", "retirement=fail"),
            "$line\nKOOG_CONTAINMENT_V1_FAIL",
        )
        for (output in invalid) {
            assertThrows<IllegalStateException> { requireKoogContainmentSummary(output) }
        }
        for (field in listOf("read", "write", "symlink", "network", "process")) {
            assertThrows<IllegalStateException> {
                requireKoogContainmentSummary(line.replace("$field=denied", "$field=allowed"))
            }
        }
    }
}
