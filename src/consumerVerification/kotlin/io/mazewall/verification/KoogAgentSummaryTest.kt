package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KoogAgentSummaryTest {
    private val thread = "KOOG_AGENT_V1_OK boundary=thread loop=real validation=rejected denial=preserved overload=rejected " +
        "cancellation=cooperative deadline=propagated retirement=complete replay=false"
    private val portal = "KOOG_AGENT_V1_OK boundary=portal loop=real validation=rejected denial=preserved unknown=terminal " +
        "cancellation=cooperative deadline=propagated retirement=complete replay=false"
    private val threadSequence = "KOOG_AGENT_SEQUENCE_V1_OK boundary=thread history=ordered turns=3 deniedThenAllowed=pass " +
        "repeated=two-explicit-calls queue=bounded queuedCancellation=not-started retirement=complete"
    private val portalSequence = "KOOG_AGENT_SEQUENCE_V1_OK boundary=portal history=ordered turns=3 deniedThenAllowed=pass " +
        "repeated=two-explicit-calls unknown=terminal queue=bounded queuedCancellation=not-started retirement=complete"
    private val threadOutput get() = "$thread\n$threadSequence"
    private val portalOutput get() = "$portal\n$portalSequence"

    @Test
    fun `requires both actual agent modes rather than direct tool evidence`() {
        assertEquals(setOf(thread, portal, threadSequence, portalSequence), requireKoogAgentSummaries("noise\n$threadOutput", portalOutput))
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
            assertThrows<IllegalStateException> { requireKoogAgentSummaries("$bad\n$threadSequence", portalOutput) }
        }
        assertThrows<IllegalStateException> { requireKoogAgentSummaries(threadOutput, "${portal.replace("unknown=terminal", "unknown=retried")}\n$portalSequence") }
    }

    @Test
    fun `single call evidence cannot substitute for sequences or vice versa`() {
        for ((left, right) in listOf(
            thread to portal,
                threadOutput to portal,
                thread to portalOutput,
            threadSequence to portalSequence,
                portalOutput to threadOutput,
        )) {
            assertThrows<IllegalStateException> { requireKoogAgentSummaries(left, right) }
        }
    }

    @Test
    fun `sequence evidence must be unique complete ordered and physically retired`() {
        for (bad in listOf(
            "$threadOutput\n$threadSequence",
                "$threadOutput\nKOOG_AGENT_SEQUENCE_V1_FAIL",
            "$thread\n${threadSequence.substringBefore(" retirement=")}",
                threadOutput.replace("turns=3", "turns=4"),
            threadOutput.replace("history=ordered", "history=ignored"),
                threadOutput.replace("queue=bounded", "queue=unbounded"),
            threadOutput.replace("queuedCancellation=not-started", "queuedCancellation=entered"),
            "$thread\n${threadSequence.replace("retirement=complete", "retirement=incomplete")}",
        )) {
            assertThrows<IllegalStateException> { requireKoogAgentSummaries(bad, portalOutput) }
        }
        assertThrows<IllegalStateException> { requireKoogAgentSummaries(threadOutput, "$portalOutput\n$portalSequence") }
        assertThrows<IllegalStateException> { requireKoogAgentSummaries(threadOutput, "$portal\n${portalSequence.replace("unknown=terminal", "unknown=retried")}") }
    }
}
