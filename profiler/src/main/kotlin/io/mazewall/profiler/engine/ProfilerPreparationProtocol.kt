package io.mazewall.profiler.engine

import io.mazewall.core.Tid
import io.mazewall.platform.seccomp.SeccompNotification
import io.mazewall.profiler.internal.TraceEventDecodeResult
import io.mazewall.profiler.internal.TraceEventDecoder
import io.mazewall.profiler.internal.TraceEventEncoder
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Private parent/child pipes, unrelated to authorization or the public profiler wire protocol. */
internal object ProfilerPreparationProtocol {
    private const val MAX_FRAME_BYTES = 128 * 1024
    private const val MAX_NAME_LENGTH = 256
    private const val SYSCALL_ARGUMENT_COUNT = 6

    data class Request(
        val notification: SeccompNotification,
        val syscallName: String,
    )

    fun writeRequest(
        output: DataOutputStream,
        request: Request,
    ) {
        val notification = request.notification
        require(notification.args.size == SYSCALL_ARGUMENT_COUNT && request.syscallName.length <= MAX_NAME_LENGTH)
        output.writeLong(notification.id)
        output.writeInt(notification.pid)
        output.writeInt(notification.arch)
        output.writeInt(notification.nr)
        notification.args.forEach(output::writeLong)
        output.writeUTF(request.syscallName)
        output.flush()
    }

    fun readRequest(input: DataInputStream): Request {
        val notification = SeccompNotification(input.readLong(), input.readInt(), input.readInt(), input.readInt(), LongArray(SYSCALL_ARGUMENT_COUNT) { input.readLong() })
        val name = input.readUTF()
        require(name.length <= MAX_NAME_LENGTH) { "Invalid preparation syscall name" }
        return Request(notification, name)
    }

    fun writeResponse(
        output: DataOutputStream,
        request: Request,
        event: SyscallEvent<SyscallEventState.Resolved>,
    ) {
        val frame = TraceEventEncoder.encode(event)
        require(frame.size <= MAX_FRAME_BYTES)
        output.writeLong(request.notification.id)
        output.writeInt(request.notification.arch)
        output.writeInt(frame.size)
        output.write(frame)
        output.flush()
    }

    fun readResponse(
        input: DataInputStream,
        request: Request,
    ): SyscallEvent<SyscallEventState.Resolved> {
        require(input.readLong() == request.notification.id) { "Preparation response ID mismatch" }
        require(input.readInt() == request.notification.arch) { "Preparation response architecture mismatch" }
        val size = input.readInt()
        require(size in 1..MAX_FRAME_BYTES) { "Invalid preparation response length" }
        val bytes = ByteArray(size).also(input::readFully)
        val frame = DataInputStream(ByteArrayInputStream(bytes))
        val tid = frame.readInt()
        require(tid == request.notification.pid) { "Preparation response TID mismatch" }
        return when (val decoded = TraceEventDecoder.decodeBody(frame, tid)) {
            is TraceEventDecodeResult.Event -> {
                require(decoded.event.syscallName == request.syscallName && frame.available() == 0) { "Preparation response operation mismatch" }
                SyscallEvent.fromResolution(Tid(tid), request.syscallName, request.notification.args.toList(), decoded.event.pathResolution)
            }
            is TraceEventDecodeResult.ProtocolViolation -> throw IOException("Malformed preparation response")
        }
    }
}
