package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.NativeMemory
import io.mazewall.core.ErrnoMapping
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readByte
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * EINTR-safe reader over an externally owned descriptor and caller-owned confined arena.
 * The public primary constructor captures the current native memory dependency once;
 * the additive constructor captures an explicitly supplied dependency instead.
 */
public open class NativeDescriptorInputStream(
    private val socketFd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
    private val arena: NativeArena,
    private val description: String = "native descriptor",
) : InputStream() {
    // Assigned only during construction: an extra primary parameter would break Kotlin metadata.
    private var memory: NativeMemory = LinuxNative.snapshot().memory

    public constructor(
        socketFd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
        arena: NativeArena,
        description: String,
        memory: NativeMemory,
    ) : this(socketFd, arena, description) {
        this.memory = memory
    }

    private val readBuf = arena.allocate(1)
    private val multiBuf = arena.allocate(BUFFER_SIZE.toLong())

    override fun read(): Int = readWithRetry(readBuf, 1, "read") { readBuf.readByte(0).toInt() and BYTE_MASK }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        java.util.Objects.checkFromIndexSize(off, len, b.size)
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
            when (val result = memory.read(socketFd, buffer, count)) {
                is LinuxNative.SyscallResult.Success -> {
                    if (result.value < 0 || result.value > count) throw java.io.IOException("Invalid native read result ${result.value} for count $count")
                    if (result.value == 0L) return -1
                    return onRead(result.value)
                }
                is LinuxNative.SyscallResult.Error -> {
                    if (result.errno != EINTR) {
                        val symbol = ErrnoMapping.getSymbolicName(result.errno)?.let { " ($it)" }.orEmpty()
                        throw IOException(
                            "$description $operation failed with errno=${result.errno}$symbol (raw return=${result.rawValue})",
                        )
                    }
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
