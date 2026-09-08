package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readByte
import java.io.InputStream
import java.io.InterruptedIOException

/** EINTR-safe reader over an externally owned descriptor and caller-owned confined arena. */
public open class NativeDescriptorInputStream(
    private val socketFd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
    private val arena: NativeArena,
    private val description: String = "native descriptor",
) : InputStream() {
    private val readBuf = arena.allocate(1)
    private val multiBuf = arena.allocate(BUFFER_SIZE.toLong())

    override fun read(): Int = readWithRetry(readBuf, 1, "read") { readBuf.readByte(0).toInt() and BYTE_MASK }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        return readWithRetry(multiBuf, minOf(len, BUFFER_SIZE).toLong(), "bulk read") { read ->
            ManagedSegment.copy(multiBuf, 0, b, off, read.toInt())
            read.toInt()
        }
    }

    private fun readWithRetry(
        buffer: ManagedSegment,
        count: Long,
        operation: String,
        onRead: (Long) -> Int,
    ): Int {
        var eintrCount = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Thread [${Thread.currentThread().name}] interrupted during $description $operation")
            }
            when (val result = LinuxNative.memory.read(socketFd, buffer, count)) {
                is LinuxNative.SyscallResult.Success -> if (result.value <= 0) return -1 else return onRead(result.value)
                is LinuxNative.SyscallResult.Error -> {
                    if (result.errno != EINTR) return -1
                    eintrCount++
                    backoff(eintrCount)
                }
            }
        }
    }

    private fun backoff(eintrCount: Int) {
        if (eintrCount <= 1) return
        if (eintrCount > 3) {
            try {
                Thread.sleep(1)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Thread [${Thread.currentThread().name}] interrupted during EINTR backoff sleep")
            }
        } else {
            Thread.yield()
        }
    }

    override fun close() = Unit

    private companion object {
        const val BUFFER_SIZE = 8192
        const val BYTE_MASK = 0xFF
        const val EINTR = 4
    }
}
