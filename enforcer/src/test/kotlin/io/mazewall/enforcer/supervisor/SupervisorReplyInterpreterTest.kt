package io.mazewall.enforcer.supervisor

import io.mazewall.ffi.memory.ManagedSegment
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupervisorReplyInterpreterTest {
    @Test
    fun `reply writer failure is terminal`() {
        val sender = object : SupervisorResponseSender {
            override fun continueNotification(
                id: Long,
                response: ManagedSegment,
            ) = error("write failed")

            override fun abortNotification(
                id: Long,
                errno: Int,
                response: ManagedSegment,
            ) = error("write failed")
        }
        assertFalse(SupervisorReplyInterpreter(sender).execute(1, dummyResponse(), SupervisorReplyInterpreter.Decision.Abort(1)))
    }

    @Test
    fun `continue produces successful decision`() {
        val sender = object : SupervisorResponseSender {
            override fun continueNotification(
                id: Long,
                response: ManagedSegment,
            ) = Unit

            override fun abortNotification(
                id: Long,
                errno: Int,
                response: ManagedSegment,
            ) = Unit
        }
        assertTrue(SupervisorReplyInterpreter(sender).execute(1, dummyResponse(), SupervisorReplyInterpreter.Decision.Continue))
    }

    private fun dummyResponse(): ManagedSegment = ManagedSegment.NULL
}
