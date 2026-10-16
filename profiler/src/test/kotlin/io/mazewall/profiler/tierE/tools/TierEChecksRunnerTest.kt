package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierEChecksRunnerTest {
    @Test
    fun `focused and delivery levels select bounded disjoint verification stages`() {
        assertEquals(listOf("focused-gradle"), TierEChecksRunner.plan("focused"))
        assertEquals(listOf("native-configure", "native-build", "native-ctest"), TierEChecksRunner.plan("native"))
        assertEquals(
            listOf("profiler-test", "native-configure", "native-build", "native-ctest", "backlog-check"),
            TierEChecksRunner.plan("delivery"),
        )
        assertThrows(IllegalArgumentException::class.java) { TierEChecksRunner.plan("paced-long") }
    }
}
