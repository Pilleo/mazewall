package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KoogSpringSummaryTest {
    private val platform =
        "KOOG_SPRING_V1_OK caller=platform registry=upstream binding=approved worker=platform identity=restored jdbc=commit-and-rollback pre=denied post=rolled-back " +
            "business=preserved callerTransaction=rejected fs=denied cancellation=cooperative retirement=complete fallback=false replay=false"
    private val virtual = platform.replace("caller=platform", "caller=virtual")

    @Test
    fun `requires both fully retired caller variants amid build noise`() {
        assertEquals(setOf(platform, virtual), requireKoogSpringSummaries("build noise\n$platform\nother phase\n$virtual\n"))
    }

    @Test
    fun `rejects missing duplicate truncated and wrong caller summaries`() {
        val invalid = listOf(
            "",
            "OPTIONAL_INTEGRATION_ACCEPTANCE_PASS",
            platform,
            virtual,
            "$platform\n$platform",
            "$virtual\n$virtual",
            "$platform\n$virtual\n$platform",
            "$platform\n${virtual.substringBefore(" retirement=")}",
            "$platform\n${virtual.replace("caller=virtual", "caller=unknown")}",
            "$platform\n$virtual\nKOOG_SPRING_V1_FAIL",
        )
        for (output in invalid) assertThrows<IllegalStateException> { requireKoogSpringSummaries(output) }
    }

    @Test
    fun `rejects false success and incomplete protection fields`() {
        val changes = listOf(
            "KOOG_SPRING_V1_OK" to "KOOG_SPRING_V1_FAIL",
            "registry=upstream" to "registry=fake",
            "binding=approved" to "binding=unbound",
            "worker=platform" to "worker=virtual",
            "identity=restored" to "identity=leaked",
            "jdbc=commit-and-rollback" to "jdbc=commit-only",
            "pre=denied" to "pre=allowed",
            "post=rolled-back" to "post=committed",
            "business=preserved" to "business=cancelled",
            "callerTransaction=rejected" to "callerTransaction=moved",
            "fs=denied" to "fs=allowed",
            "cancellation=cooperative" to "cancellation=ignored",
            "retirement=complete" to "retirement=incomplete",
            "fallback=false" to "fallback=true",
            "replay=false" to "replay=true",
        )
        for ((expected, changed) in changes) {
            assertThrows<IllegalStateException> { requireKoogSpringSummaries("$platform\n${virtual.replace(expected, changed)}") }
        }
    }
}
