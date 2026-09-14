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
