package io.mazewall.profiler.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class TraceEventDecoderTest {
    @Test
    fun `preserves a native command-write errno as a typed outcome`() {
        val outcome = TraceCommandWriteResult.from(
            io.mazewall.LinuxNative.SyscallResult
            .Error<io.mazewall.LinuxNative.SyscallHandledState.Unhandled>(32, -1L),
        )

        assertEquals(32, assertInstanceOf(TraceCommandWriteResult.NativeFailure::class.java, outcome).errno)
    }

    @Test
    fun `rejects negative syscall name length`() {
        val input = DataInputStream(ByteArrayInputStream(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())))

        val result = TraceEventDecoder.decodeBody(input, tidValue = 7)

        assertInstanceOf(TraceEventDecodeResult.ProtocolViolation::class.java, result)
    }

    @Test
    fun `rejects a path count above the protocol limit`() {
        val input = DataInputStream(
            ByteArrayInputStream(
                byteArrayOf(
                    0,
                    0,
                    0,
                    4,
                    'R'.code.toByte(),
                    'E'.code.toByte(),
                    'A'.code.toByte(),
                    'D'.code.toByte(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                ),
            ),
        )

        val result = TraceEventDecoder.decodeBody(input, tidValue = 7)

        assertInstanceOf(TraceEventDecodeResult.ProtocolViolation::class.java, result)
    }

    @Test
    fun `decodes a bounded trace event body`() {
        val input = DataInputStream(
            ByteArrayInputStream(
                byteArrayOf(
                    0,
                    0,
                    0,
                    4,
                    'R'.code.toByte(),
                    'E'.code.toByte(),
                    'A'.code.toByte(),
                    'D'.code.toByte(),
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    9,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    0,
                    2,
                    'o'.code.toByte(),
                    'k'.code.toByte(),
                ),
            ),
        )

        val decoded = assertInstanceOf(TraceEventDecodeResult.Event::class.java, TraceEventDecoder.decodeBody(input, tidValue = 7))

        assertEquals("READ", decoded.event.syscallName)
        assertEquals(listOf("ok"), decoded.event.paths)
    }
}
