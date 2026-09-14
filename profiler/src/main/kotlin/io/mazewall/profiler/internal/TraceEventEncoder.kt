package io.mazewall.profiler.internal

import io.mazewall.profiler.api.SyscallEvent
import io.mazewall.profiler.api.SyscallEventState
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Heap-only encoding is complete before the native transport borrows these bytes. */
internal object TraceEventEncoder {
    private const val MAX_SYSCALL_NAME_BYTES = 256
    private const val MAX_ARGUMENTS = 6
    private const val MAX_PATHS = 8
    private const val MAX_PATH_BYTES = 4096

    fun encode(event: SyscallEvent<SyscallEventState.Resolved>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(event.tid.value)
            output.writeInt(PathResolutionWire.VERSION)
            writeString(output, event.syscallName, MAX_SYSCALL_NAME_BYTES)
            require(event.args.size <= MAX_ARGUMENTS) { "too many syscall arguments" }
            output.writeInt(event.args.size)
            event.args.forEach(output::writeLong)
            require(event.paths.size <= MAX_PATHS) { "too many syscall paths" }
            output.writeInt(event.paths.size)
            event.paths.forEach { writeString(output, it, MAX_PATH_BYTES) }
            PathResolutionWire.write(output, event.pathResolution)
        }
        return bytes.toByteArray()
    }

    private fun writeString(
        output: DataOutputStream,
        value: String,
        limit: Int,
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= limit) { "trace string exceeds $limit bytes" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }
}
