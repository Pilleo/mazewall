package io.mazewall.profiler.internal.engine
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
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
