package io.mazewall.profiler.internal.engine
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.seccomp.SeccompNotification
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class ProfilerPreparationProtocolTest {
    @Test
    fun `prepared CONNECT endpoints and read failures survive both private frame boundaries`() {
        val request = ProfilerPreparationProtocol.Request(SeccompNotification(123, 456, 0xC000003E.toInt(), 42, longArrayOf(3, 4096, 16, 0, 0, 0)), "CONNECT")
        val resolutions = listOf(
            ConnectResolution.Resolved(NetworkEndpoint("127.0.0.1", 8080)),
            ConnectResolution.Resolved(NetworkEndpoint("unix:/tmp/sock")),
            ConnectResolution.Failed(ConnectResolution.Reason.MEMORY_READ_FAILED, "native inspection errno=13"),
        )
        for (resolution in resolutions) {
            val event = SyscallEvent.fromEvidence<SyscallEventState.Resolved>(Tid(456), "CONNECT", request.notification.args.toList(), PathResolution.NotApplicable, resolution)
            val bytes = ByteArrayOutputStream()
            ProfilerPreparationProtocol.writeResponse(DataOutputStream(bytes), request, event)
            val prepared = ProfilerPreparationProtocol.readResponse(DataInputStream(ByteArrayInputStream(bytes.toByteArray())), request)
            assertEquals(resolution, prepared.connectResolution)
            val frame = DataInputStream(
                ByteArrayInputStream(
                    io.mazewall.profiler.internal.TraceEventEncoder
                .encode(prepared),
                ),
            )
            assertEquals(456, frame.readInt())
            val decoded = kotlin.test
                .assertIs<io.mazewall.profiler.internal.TraceEventDecodeResult.Event>(
                io.mazewall.profiler.internal.TraceEventDecoder
                .decodeBody(frame, 456),
            ).event
            assertEquals(resolution, decoded.connectResolution)
            val observation = ProfileObservation.fromTraceEvent(decoded)
            when (resolution) {
                is ConnectResolution.Resolved -> assertEquals(resolution.endpoint, kotlin.test.assertIs<ProfileObservation.Connect>(observation).endpoint)
                is ConnectResolution.Failed -> kotlin.test.assertIs<ProfileObservation.Syscall>(observation)
                ConnectResolution.NotApplicable -> error("not a CONNECT fixture")
            }
        }
    }

    private val request = ProfilerPreparationProtocol.Request(SeccompNotification(123, 456, 0xC00000B7.toInt(), 2, LongArray(6)), "OPEN")

    @Test
    fun `private preparation preserves request architecture and typed heap evidence`() {
        val bytes = ByteArrayOutputStream()
        ProfilerPreparationProtocol.writeRequest(DataOutputStream(bytes), request)
        assertEquals(request, ProfilerPreparationProtocol.readRequest(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))))
        val event = SyscallEvent<SyscallEventState.Resolved>(Tid(456), "OPEN", List(6) { 0L }, listOf("/private/data"))
        val response = ByteArrayOutputStream()
        ProfilerPreparationProtocol.writeResponse(DataOutputStream(response), request, event)
        assertEquals(event, ProfilerPreparationProtocol.readResponse(DataInputStream(ByteArrayInputStream(response.toByteArray())), request))
    }

    @Test
    fun `private preparation rejects foreign identity architecture and oversized frames`() {
        val event = SyscallEvent<SyscallEventState.Resolved>(Tid(456), "OPEN", List(6) { 0L })
        val response = ByteArrayOutputStream()
        ProfilerPreparationProtocol.writeResponse(DataOutputStream(response), request, event)
        for (different in listOf(request.copy(notification = request.notification.copy(id = 124)), request.copy(notification = request.notification.copy(arch = 0)))) {
            assertThrows(IllegalArgumentException::class.java) { ProfilerPreparationProtocol.readResponse(DataInputStream(ByteArrayInputStream(response.toByteArray())), different) }
        }
        val oversized = ByteArrayOutputStream()
        DataOutputStream(oversized).apply {
            writeLong(123)
            writeInt(request.notification.arch)
            writeInt(Int.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) { ProfilerPreparationProtocol.readResponse(DataInputStream(ByteArrayInputStream(oversized.toByteArray())), request) }
    }
}
