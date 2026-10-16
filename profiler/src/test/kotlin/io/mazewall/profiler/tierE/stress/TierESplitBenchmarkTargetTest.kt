package io.mazewall.profiler.tierE.stress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TierESplitBenchmarkTargetTest {
    @Test
    fun `all seven modes share positive benchmark dimensions`() {
        for (mode in listOf(
            "baseline",
                "dictionary-full-stream",
                "dictionary-unique-edge",
                "inline-full-stream",
            "inline-unique-edge",
                "user-notif-no-stack",
                "user-notif-stack",
        )) {
            val arguments = arrayOf(mode, "3", "10", "100") +
                if (mode == "baseline" || mode.startsWith("user-notif")) {
                    emptyArray()
                } else {
                    arrayOf("/tmp/gate", "/tmp/pid", "/tmp/socket", "/tmp/marker", "20", "4d5a", "/tmp/oracle")
                }
            val parsed = TierESplitBenchmarkTarget.Arguments.parse(arguments)
            assertEquals(3, parsed.warmups)
            assertEquals(10, parsed.batches)
            assertEquals(100, parsed.iterations)
        }
    }

    @Test
    fun `tier e arm cannot start without registration and oracle handoff`() {
        assertFailsWith<IllegalArgumentException> {
            TierESplitBenchmarkTarget.Arguments.parse(arrayOf("dictionary-full-stream", "3", "10", "100"))
        }
    }
}
