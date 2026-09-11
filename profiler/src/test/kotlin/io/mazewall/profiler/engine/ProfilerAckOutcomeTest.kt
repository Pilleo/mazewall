package io.mazewall.profiler.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProfilerAckOutcomeTest {
    @Test
    fun `ACK parser preserves valid command precedence and rejects mixed unknown bytes`() {
        val cases = listOf(
            byteArrayOf(0xAC.toByte()) to ProfilerAckOutcome.Acknowledged(false),
            byteArrayOf(0xAC.toByte(), 0x53) to ProfilerAckOutcome.Acknowledged(true),
            byteArrayOf(0x54) to ProfilerAckOutcome.PassThrough,
            byteArrayOf(0x53) to ProfilerAckOutcome.Rejected(ProfilerHandshakeFailure.Shutdown),
            byteArrayOf() to ProfilerAckOutcome.Rejected(ProfilerHandshakeFailure.MalformedReply),
            byteArrayOf(0xAC.toByte(), 0x00) to ProfilerAckOutcome.Rejected(ProfilerHandshakeFailure.MalformedReply),
        )
        for ((bytes, expected) in cases) assertEquals(expected, ProfilerAckOutcome.decode(bytes))
    }
}
