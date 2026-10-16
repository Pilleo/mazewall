package io.mazewall.profiler.internal
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.api.PathResolution
import io.mazewall.profiler.api.PathResolutionFailure
import io.mazewall.profiler.api.ProfileObservation
import io.mazewall.profiler.api.SyscallEvent
import io.mazewall.profiler.api.SyscallEventState
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.test.assertIs

class TraceEventDecoderTest {
    @Test
    fun `endpoint snapshots remain distinct and copies cannot bind them to changed registers`() {
        fun event(port: Int) =
            SyscallEvent.fromEvidence<SyscallEventState.Resolved>(
            Tid(7),
                "CONNECT",
                listOf(3, 4096, 16),
                PathResolution.NotApplicable,
            ConnectResolution.Resolved(NetworkEndpoint("127.0.0.1", port)),
        )
        val first = event(80)
        val second = event(443)
        kotlin.test.assertNotEquals(first, second)
        val trace = assertIs<TraceEvent.Generic>(TraceEvent.fromSyscallEvent(first))
        kotlin.test.assertNotEquals(trace, TraceEvent.fromSyscallEvent(second))
        assertEquals(trace, trace.copy())
        assertIs<ProfileObservation.Connect>(ProfileObservation.fromTraceEvent(trace.copy()))
        assertIs<ProfileObservation.Syscall>(ProfileObservation.fromTraceEvent(trace.copy(args = listOf(4, 8192, 16))))
        val rebound = SyscallEvent<SyscallEventState.Resolved>(Tid(7), "CONNECT", listOf(4, 8192, 16), first.paths)
        assertIs<ProfileObservation.Syscall>(ProfileObservation.fromSyscallEvent(rebound))
        val retained = SyscallEvent<SyscallEventState.Resolved>(Tid(7), "CONNECT", first.args, first.paths)
        assertEquals(first, retained)
    }

    @Test
    fun `malformed network evidence never turns into a typed CONNECT`() {
        fun frame(
            name: String = "CONNECT",
            write: (java.io.DataOutputStream) -> Unit,
        ): ByteArray {
            val bytes = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(bytes).use {
                it.writeInt(2)
                it.writeInt(name.length)
                it.writeBytes(name)
                it.writeInt(0)
                it.writeInt(0)
                it.writeByte(0)
                it.writeInt(0)
                write(it)
            }
            return bytes.toByteArray()
        }
        val malformed = listOf(
            frame { it.writeByte(0) },
            frame { it.writeByte(99) },
            frame {
                it.writeByte(1)
                it.writeInt(Int.MAX_VALUE)
            },
            frame {
                it.writeByte(1)
                it.writeInt(1)
                it.writeByte(0x80)
                it.writeInt(80)
            },
            frame {
                it.writeByte(1)
                it.writeInt(0)
                it.writeInt(80)
            },
            frame {
                it.writeByte(1)
                it.writeInt(1)
                it.writeByte('x'.code)
                it.writeInt(65536)
            },
            frame {
                it.writeByte(2)
                it.writeByte(99)
            },
            frame {
                it.writeByte(2)
                it.writeByte(4)
                it.writeInt(10)
                it.writeByte(1)
            },
            frame("OPEN") {
                it.writeByte(1)
                it.writeInt(1)
                it.writeByte('x'.code)
                it.writeInt(80)
            },
        )
        for (bytes in malformed) {
            assertIs<TraceEventDecodeResult.ProtocolViolation>(TraceEventDecoder.decodeBody(DataInputStream(ByteArrayInputStream(bytes)), 7))
        }
    }

    @Test
    fun `malformed UTF8 diagnostic fails the frame instead of altering evidence`() {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { output ->
            output.writeInt(1) // frame version
            output.writeInt(4)
            output.writeBytes("OPEN")
            output.writeInt(0) // arguments
            output.writeInt(0) // paths
            output.writeByte(3) // Failed
            output.writeInt(1) // failures
            output.writeInt(0) // first operand
            output.writeByte(0) // VmReadDenied
            output.writeInt(1) // diagnostic length
            output.writeByte(0x80) // lone UTF8 continuation byte
            output.writeBoolean(false) // no cause
        }
        assertIs<TraceEventDecodeResult.ProtocolViolation>(TraceEventDecoder.decodeBody(DataInputStream(ByteArrayInputStream(bytes.toByteArray())), 7))
    }

    @Test
    fun `private profiler frames carry every resolution outcome and failure diagnostic`() {
        val missing = PathResolutionFailure(1, PathResolutionFailure.Reason.Missing, "missing second operand")
        for (reason in listOf(
            PathResolutionFailure.Reason.VmReadDenied,
            PathResolutionFailure.Reason.Missing,
            PathResolutionFailure.Reason.DirFdUnresolved,
            PathResolutionFailure.Reason.Truncated,
        )) {
            val failure = PathResolutionFailure(
                0,
                reason,
                "TID 7 addr=0x1000",
                io.mazewall.profiler.api
                    .PathResolutionCause("original.type", "original message"),
            )
            val cases = listOf(
                PathResolution.NotApplicable,
                PathResolution.Resolved(listOf("/tmp/a", "/tmp/b")),
                PathResolution.Partial(listOf("/tmp/b"), listOf(failure)),
                PathResolution.Failed(listOf(failure, missing)),
            )
            for (resolution in cases) {
                val event = SyscallEvent.fromResolution<SyscallEventState.Resolved>(Tid(7), "RENAME", listOf(100L, 200L), resolution)
                val input = DataInputStream(ByteArrayInputStream(TraceEventEncoder.encode(event)))
                assertEquals(7, input.readInt())
                val decoded = assertIs<TraceEventDecodeResult.Event>(TraceEventDecoder.decodeBody(input, 7)).event
                assertEquals(resolution, decoded.pathResolution)
                assertEquals(resolution, ProfileObservation.fromTraceEvent(decoded).pathResolution)
                assertEquals(-1, input.read())
            }
        }
    }

    @Test
    fun `unknown profiler version and resolution tag fail decoding`() {
        val event = SyscallEvent.fromResolution<SyscallEventState.Resolved>(Tid(7), "READ", emptyList(), PathResolution.NotApplicable)
        val frame = TraceEventEncoder.encode(event)
        val badVersion = frame.copyOf().also { it[7] = 99 }
        val badTag = frame.copyOf().also { it[it.size - 5] = 99 }
        for (bytes in listOf(badVersion, badTag, frame.copyOf(frame.size - 1))) {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            input.readInt()
            assertIs<TraceEventDecodeResult.ProtocolViolation>(TraceEventDecoder.decodeBody(input, 7))
        }
    }

    @Test
    fun `preserves a native command-write errno as a typed outcome`() {
        val outcome = TraceCommandWriteResult.from(
            io.mazewall.platform.native.LinuxNative.SyscallResult
            .Error<io.mazewall.platform.native.LinuxNative.SyscallHandledState.Unhandled>(32, -1L),
        )

        assertEquals(32, assertInstanceOf(TraceCommandWriteResult.NativeFailure::class.java, outcome).errno)
    }

    @Test
    fun `rejects negative syscall name length`() {
        val input = DataInputStream(ByteArrayInputStream(byteArrayOf(0, 0, 0, 1, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())))

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
                    1, // profiler frame version
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
                    1, // profiler frame version
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
                    1,
                    0,
                    0,
                    0,
                    0, // Resolved, zero failures
                ),
            ),
        )

        val decoded = assertInstanceOf(TraceEventDecodeResult.Event::class.java, TraceEventDecoder.decodeBody(input, tidValue = 7))

        assertEquals("READ", decoded.event.syscallName)
        assertEquals(listOf("ok"), decoded.event.paths)
    }
}
