package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KoogAgentSummaryTest {
    private val thread = "KOOG_AGENT_V1_OK boundary=thread loop=real validation=rejected denial=preserved overload=rejected " +
        "cancellation=cooperative deadline=propagated retirement=complete replay=false"
    private val portal = "KOOG_AGENT_V1_OK boundary=portal loop=real validation=rejected denial=preserved unknown=terminal " +
        "cancellation=cooperative deadline=propagated retirement=complete replay=false"

    @Test
    fun `requires both actual agent modes rather than direct tool evidence`() {
        assertEquals(setOf(thread, portal), requireKoogAgentSummaries("noise\n$thread", portal))
        for ((left, right) in listOf("" to portal, thread to "", portal to thread, "KOOG_THREAD_V1_OK" to portal, thread to "KOOG_NATIVE_FRESH_OK")) {
            assertThrows<IllegalStateException> { requireKoogAgentSummaries(left, right) }
        }
    }

    @Test
    fun `rejects duplicate partial contradictory and weakened agent evidence`() {
        for (bad in listOf(
            "$thread\n$thread",
                "$thread\nKOOG_AGENT_V1_FAIL",
                thread.substringBefore(" retirement="),
            thread.replace("loop=real", "loop=direct"),
                thread.replace("retirement=complete", "retirement=incomplete"),
            thread.replace("replay=false", "replay=true"),
                thread.replace("deadline=propagated", "deadline=ignored"),
        )) {
            assertThrows<IllegalStateException> { requireKoogAgentSummaries(bad, portal) }
        }
        assertThrows<IllegalStateException> { requireKoogAgentSummaries(thread, portal.replace("unknown=terminal", "unknown=retried")) }
    }
}
